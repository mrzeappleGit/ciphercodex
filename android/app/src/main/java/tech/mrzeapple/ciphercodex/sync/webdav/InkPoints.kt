package tech.mrzeapple.ciphercodex.sync.webdav

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** One pen sample. x/y are page-normalized 0..1; pressure is raw 0..4095. */
data class InkPoint(val x: Float, val y: Float, val pressure: Int)

/** A drawable segment in normalized page coords; width is in page pixels. */
data class InkSegment(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val width: Float)

object InkPoints {
    private const val BYTES_PER_POINT = 18 // rM2 storage.cpp PackedPoint, little-endian

    /** Malformed input (bad base64 / not a multiple of 18 bytes) → empty list, never throws. */
    fun decode(b64: String): List<InkPoint> {
        val bytes = try { Base64.getDecoder().decode(b64) } catch (_: IllegalArgumentException) { return emptyList() }
        if (bytes.isEmpty() || bytes.size % BYTES_PER_POINT != 0) return emptyList()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<InkPoint>(bytes.size / BYTES_PER_POINT)
        while (buf.remaining() >= BYTES_PER_POINT) {
            val x = buf.float
            val y = buf.float
            val pressure = buf.short.toInt() and 0xFFFF
            buf.short; buf.short; buf.int // tiltX, tiltY, tMs — unused in v1
            out.add(InkPoint(x, y, pressure))
        }
        return out
    }
}

object InkGeometry {
    private const val PRESSURE_MAX = 4095f
    private const val FLOOR = 0.35f // mirrors rM2 inkStrokeWidth (inkitem.cpp:38)
    private const val MIN_W = 1f
    private const val DEFAULT_MAX_W = 9f

    private fun width(pressure: Int, maxW: Float): Float {
        val p = (((pressure / PRESSURE_MAX) - FLOOR) / (1f - FLOOR)).coerceIn(0f, 1f)
        return MIN_W + p * p * (maxW - MIN_W)
    }

    /** baseWidth is wire provenance only: the rM2's own renderer ignores it and always
     *  uses a fixed maxW of 9.0 (inkitem.cpp:44-48, env-tunable there but not on the wire).
     *  Matching that keeps Android's render device-true instead of the ~1-2px the old
     *  baseWidth-driven curve produced. */
    fun strokeSegments(points: List<InkPoint>, @Suppress("UNUSED_PARAMETER") baseWidth: Float): List<InkSegment> {
        val maxW = DEFAULT_MAX_W
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) {
            val p = points[0]
            return listOf(InkSegment(p.x, p.y, p.x, p.y, width(p.pressure, maxW)))
        }
        return points.zipWithNext { a, b ->
            InkSegment(a.x, a.y, b.x, b.y, width(b.pressure, maxW))
        }
    }
}
