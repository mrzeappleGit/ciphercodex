package tech.mrzeapple.ciphercodex.sync.recognition

import org.junit.Assert.*
import org.junit.Test
import tech.mrzeapple.ciphercodex.sync.webdav.InkPoint

class RecognitionPassTest {
    private fun stroke(y: Float, at: Long) =
        RecStroke(listOf(InkPoint(0.1f, y, 2000, 0), InkPoint(0.4f, y + 0.03f, 2000, 40)), at)

    @Test
    fun `lines are recognized top-down with rolling preContext and joined by newline`() {
        val calls = mutableListOf<Pair<Int, String>>()  // (line stroke count, preContext)
        val pass = RecognitionPass { line, pre -> calls.add(line.size to pre); "line${calls.size}" }
        val text = pass.textFor(listOf(stroke(0.6f, 1L), stroke(0.1f, 2L)))
        assertEquals("line1\nline2", text)
        assertEquals("", calls[0].second)          // first line: no context
        assertEquals("line1", calls[1].second)     // second line sees prior text
    }

    @Test
    fun `no ink yields empty text without invoking the recognizer`() {
        var called = false
        val pass = RecognitionPass { _, _ -> called = true; "x" }
        assertEquals("", pass.textFor(emptyList()))
        assertFalse(called)
    }
}
