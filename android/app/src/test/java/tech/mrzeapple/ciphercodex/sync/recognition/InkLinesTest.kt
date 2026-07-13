package tech.mrzeapple.ciphercodex.sync.recognition

import org.junit.Assert.*
import org.junit.Test
import tech.mrzeapple.ciphercodex.sync.webdav.InkPoint

class InkLinesTest {
    private fun stroke(yTop: Float, yBottom: Float, at: Long) = RecStroke(
        listOf(InkPoint(0.1f, yTop, 2000, 0), InkPoint(0.3f, yBottom, 2000, 30)), at)

    @Test
    fun `two vertically separated rows become two lines in top-down order`() {
        // written bottom row first — order in must not dictate line order out
        val lines = InkLines.segment(listOf(stroke(0.50f, 0.53f, 1000), stroke(0.10f, 0.13f, 2000)))
        assertEquals(2, lines.size)
        assertEquals(2000L, lines[0][0].createdAt)  // top line
        assertEquals(1000L, lines[1][0].createdAt)
    }

    @Test
    fun `a delayed i-dot overlapping a line's band joins that line`() {
        val body = stroke(0.30f, 0.36f, 1000)
        val dot = stroke(0.29f, 0.295f, 5000)  // above but within tolerance of the band
        val lines = InkLines.segment(listOf(body, stroke(0.60f, 0.66f, 2000), dot))
        assertEquals(2, lines.size)
        assertEquals(listOf(1000L, 5000L), lines[0].map { it.createdAt })
    }

    @Test
    fun `empty strokes are dropped and empty input yields no lines`() {
        assertTrue(InkLines.segment(emptyList()).isEmpty())
        assertTrue(InkLines.segment(listOf(RecStroke(emptyList(), 1L))).isEmpty())
    }
}
