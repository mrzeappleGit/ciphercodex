package tech.mrzeapple.ciphercodex.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mrzeapple.ciphercodex.sync.webdav.InkPoint

class InkHitTestTest {
    private fun pts(vararg xy: Pair<Float, Float>) = xy.map { InkPoint(it.first, it.second, 2000) }

    @Test
    fun `hits the nearest stroke within tolerance, misses outside`() {
        val strokes = listOf(
            "a" to pts(0.1f to 0.5f, 0.4f to 0.5f),   // horizontal segment
            "b" to pts(0.5f to 0.52f),                  // single dot, slightly closer to probe 2
        )
        assertEquals("a", InkHitTest.strokeAt(strokes, 0.25f, 0.505f, tol = 0.012f)) // near mid-segment
        assertEquals("b", InkHitTest.strokeAt(strokes, 0.5f, 0.53f, tol = 0.012f))   // near the dot
        assertNull(InkHitTest.strokeAt(strokes, 0.9f, 0.9f, tol = 0.012f))            // far from both
        assertNull(InkHitTest.strokeAt(emptyList(), 0.5f, 0.5f, tol = 0.012f))
    }
}
