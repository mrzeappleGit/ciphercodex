package tech.mrzeapple.ciphercodex.ui.nav

object Routes {
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val READER = "reader/{bookId}"
    fun reader(bookId: Long) = "reader/$bookId"
}
