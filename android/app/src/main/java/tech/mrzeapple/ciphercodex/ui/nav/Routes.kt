package tech.mrzeapple.ciphercodex.ui.nav

object Routes {
    /** The tabbed home (Library / Kept / Stats / Settings) behind the bottom bar. */
    const val MAIN = "main"
    const val OPDS = "opds"
    const val READER = "reader/{bookId}"
    fun reader(bookId: Long) = "reader/$bookId"
    const val DETAIL = "detail/{bookId}"
    fun detail(bookId: Long) = "detail/$bookId"
}
