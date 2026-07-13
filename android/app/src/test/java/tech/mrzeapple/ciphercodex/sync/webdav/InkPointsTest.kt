package tech.mrzeapple.ciphercodex.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class InkPointsTest {

    /** Packs points exactly like rM2 storage.cpp PackedPoint (18B LE). */
    private fun pack(vararg pts: Triple<Float, Float, Int>): String {
        val buf = ByteBuffer.allocate(pts.size * 18).order(ByteOrder.LITTLE_ENDIAN)
        for ((x, y, pressure) in pts) {
            buf.putFloat(x); buf.putFloat(y)
            buf.putShort(pressure.toShort())
            buf.putShort(0); buf.putShort(0) // tilt
            buf.putInt(0) // tMs
        }
        return Base64.getEncoder().encodeToString(buf.array())
    }

    @Test
    fun `decodes 18-byte LE records`() {
        val pts = InkPoints.decode(pack(Triple(0.25f, 0.5f, 4095), Triple(0.26f, 0.51f, 2000)))
        assertEquals(2, pts.size)
        assertEquals(0.25f, pts[0].x, 1e-6f)
        assertEquals(0.5f, pts[0].y, 1e-6f)
        assertEquals(4095, pts[0].pressure)
        assertEquals(2000, pts[1].pressure)
    }

    @Test
    fun `malformed input decodes to empty, never throws`() {
        assertTrue(InkPoints.decode("not-base64!!!").isEmpty())
        // valid base64, but 17 bytes — not a multiple of 18
        assertTrue(InkPoints.decode(Base64.getEncoder().encodeToString(ByteArray(17))).isEmpty())
        assertTrue(InkPoints.decode("").isEmpty())
    }

    @Test
    fun `segments follow the rM2 pressure curve`() {
        val pts = listOf(InkPoint(0f, 0f, 4095), InkPoint(0.1f, 0f, 4095))
        val seg = InkGeometry.strokeSegments(pts, baseWidth = 9f).single()
        assertEquals(9f, seg.width, 1e-3f) // full pressure = full baseWidth
        val soft = InkGeometry.strokeSegments(
            listOf(InkPoint(0f, 0f, 0), InkPoint(0.1f, 0f, 0)), 9f).single()
        assertEquals(1f, soft.width, 1e-3f) // below floor = minW
    }

    @Test
    fun `baseWidth is ignored, device always uses maxW 9`() {
        // wire baseWidth is a constant 2.0 struct default the device never reads
        // (inkitem.cpp uses a fixed maxW of 9.0); Android must match, not the wire value.
        val seg = InkGeometry.strokeSegments(
            listOf(InkPoint(0f, 0f, 4095), InkPoint(0.1f, 0f, 4095)), baseWidth = 2f).single()
        assertEquals(9f, seg.width, 1e-3f)
    }

    @Test
    fun `single point becomes a dot segment`() {
        val seg = InkGeometry.strokeSegments(listOf(InkPoint(0.5f, 0.5f, 4095)), 9f).single()
        assertEquals(seg.x0, seg.x1, 0f)
        assertEquals(seg.y0, seg.y1, 0f)
    }

    @Test
    fun `decode carries the relative timestamp`() {
        val buf = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
        buf.putFloat(0.25f); buf.putFloat(0.5f); buf.putShort(2000); buf.putShort(0); buf.putShort(0)
        buf.putInt(0)          // first point at t=0
        buf.putFloat(0.26f); buf.putFloat(0.5f); buf.putShort(2100); buf.putShort(0); buf.putShort(0)
        buf.putInt(17)         // 17 ms later
        val b64 = Base64.getEncoder().encodeToString(buf.array())
        val pts = InkPoints.decode(b64)
        assertEquals(2, pts.size)
        assertEquals(0L, pts[0].t)
        assertEquals(17L, pts[1].t)
    }

    @Test
    fun `ink snapshot decodes wire json and ignores unknown keys`() {
        val s = InkSnapshotJson.decode("""
        {"deviceId":"aabb01","generatedAt":1,"books":[{"digest":"d1"}],
         "notebooks":[{"guid":"n1","title":"ink","createdAt":6,"deleted":0,"updatedAt":16}],
         "pages":[{"guid":"p1","notebookGuid":"n1","seq":0,"deleted":0,"updatedAt":17}],
         "strokes":[{"guid":"s1","pageGuid":"p1","tool":0,"baseWidth":2.0,"points_b64":"AAAA",
                     "createdAt":7,"deleted":0,"updatedAt":18}]}
        """.trimIndent())
        assertEquals("aabb01", s.deviceId)
        assertEquals("ink", s.notebooks.single().title)
        assertEquals("n1", s.pages.single().notebookGuid)
        assertEquals(2.0f, s.strokes.single().baseWidth, 1e-6f)
        assertEquals("AAAA", s.strokes.single().pointsB64)
        // missing arrays parse as empty
        assertTrue(InkSnapshotJson.decode("""{"deviceId":"x"}""").notebooks.isEmpty())
    }
}
