package tech.mrzeapple.ciphercodex.sync.recognition

/** Whole-page orchestration: segment -> recognize per line -> join. The recognize lambda
 *  is the ML Kit boundary so this stays a pure-JVM unit. */
class RecognitionPass(private val recognize: (List<RecStroke>, String) -> String) {
    fun textFor(strokes: List<RecStroke>): String {
        val sb = StringBuilder()
        for (line in InkLines.segment(strokes)) {
            val t = recognize(line, sb.toString().takeLast(20))
            if (t.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(t)
            }
        }
        return sb.toString().trim()
    }
}
