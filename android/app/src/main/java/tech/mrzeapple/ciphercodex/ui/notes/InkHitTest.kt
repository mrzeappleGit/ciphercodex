package tech.mrzeapple.ciphercodex.ui.notes

import kotlin.math.hypot
import tech.mrzeapple.ciphercodex.sync.webdav.InkPoint

/** Eraser hit-test in normalized page coords. tol mixes the x/y scales slightly
 *  (0..1 spans 1404 px in x, 1872 px in y) — acceptable for a fingertip-sized eraser. */
object InkHitTest {
    fun strokeAt(strokes: List<Pair<String, List<InkPoint>>>, x: Float, y: Float, tol: Float): String? {
        var best: String? = null
        var bestD = tol
        for ((guid, pts) in strokes) {
            if (pts.isEmpty()) continue
            if (pts.size == 1) {
                val d = hypot(pts[0].x - x, pts[0].y - y)
                if (d <= bestD) { bestD = d; best = guid }
                continue
            }
            for (i in 0 until pts.size - 1) {
                val d = segDist(x, y, pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y)
                if (d <= bestD) { bestD = d; best = guid }
            }
        }
        return best
    }

    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
