package tech.mrzeapple.ciphercodex.data

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import tech.mrzeapple.ciphercodex.data.db.BookEntity
import tech.mrzeapple.ciphercodex.epub.EpubDocument

data class BookWithProgress(
    val book: BookEntity,
    /** Whole-book percentage 0f..1f, null if never opened. */
    val percentage: Float?,
)

sealed interface ImportResult {
    data class Imported(val bookId: Long) : ImportResult
    /** Same digest already in the library. */
    data class Duplicate(val bookId: Long) : ImportResult
    data class Failed(val message: String) : ImportResult
}

/** Owns the books directory under filesDir, the Room tables, and cover cache.
 *  Implemented by [BookRepository]. */
interface LibraryRepository {
    fun observeLibrary(): Flow<List<BookWithProgress>>

    /** Copies the document at [uri] into app storage, computes the kosync
     *  partial-MD5 digest, extracts metadata + cover, inserts the row. */
    suspend fun importEpub(uri: Uri): ImportResult

    /** Removes the row, its progress, the imported file and cached cover. */
    suspend fun deleteBook(bookId: Long)

    suspend fun bookById(bookId: Long): BookEntity?

    suspend fun markOpened(bookId: Long)

    suspend fun saveProgress(bookId: Long, spineIndex: Int, charOffset: Int, percentage: Float)
}
