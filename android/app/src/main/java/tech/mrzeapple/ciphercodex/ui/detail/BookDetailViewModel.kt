package tech.mrzeapple.ciphercodex.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.mrzeapple.ciphercodex.CipherCodexApp
import tech.mrzeapple.ciphercodex.data.db.BookCollectionCrossRef
import tech.mrzeapple.ciphercodex.data.db.BookEntity
import tech.mrzeapple.ciphercodex.data.db.CollectionEntity
import tech.mrzeapple.ciphercodex.epub.Epub
import java.io.File

/** Backs the Book Detail hero screen: the book's metadata, progress, annotation
 *  counts, shelf membership, its dc:description (parsed on demand), and the
 *  per-book actions (shelf toggle, new shelf, delete). */
class BookDetailViewModel(application: Application, private val bookId: Long) :
    AndroidViewModel(application) {

    private val app = application as CipherCodexApp
    private val repository = app.repository
    private val dao = app.database.bookDao()

    private val _book = MutableStateFlow<BookEntity?>(null)
    val book: StateFlow<BookEntity?> = _book

    private val _percentage = MutableStateFlow<Float?>(null)
    val percentage: StateFlow<Float?> = _percentage

    private val _description = MutableStateFlow<String?>(null)
    val description: StateFlow<String?> = _description

    val bookmarkCount: StateFlow<Int> =
        dao.observeBookmarks(bookId).map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val highlightCount: StateFlow<Int> =
        dao.observeHighlights(bookId).map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val collections: StateFlow<List<CollectionEntity>> =
        dao.observeCollections()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Ids of the shelves this book belongs to. */
    val memberOf: StateFlow<Set<Long>> =
        dao.observeBookCollections()
            .map { refs -> refs.filter { it.bookId == bookId }.mapTo(HashSet()) { it.collectionId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    init {
        viewModelScope.launch {
            val b = repository.bookById(bookId)
            _book.value = b
            _percentage.value = dao.progressFor(bookId)?.percentage
            if (b != null) {
                // The blurb isn't stored in the library row; read it off the EPUB
                // once, off the main thread, tolerating any parse failure.
                _description.value = withContext(Dispatchers.IO) {
                    runCatching { Epub.open(File(b.filePath)).use { it.metadata.description } }.getOrNull()
                }
            }
        }
    }

    fun setInShelf(collectionId: Long, inShelf: Boolean) {
        viewModelScope.launch {
            if (inShelf) dao.addBookToCollection(BookCollectionCrossRef(collectionId, bookId))
            else dao.removeBookFromCollection(collectionId, bookId, System.currentTimeMillis())
        }
    }

    fun createShelf(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val id = dao.insertCollection(CollectionEntity(name = trimmed, createdAt = System.currentTimeMillis()))
            dao.addBookToCollection(BookCollectionCrossRef(id, bookId))
        }
    }

    /** Deletes the book (and its shelf memberships, per the repository), then
     *  invokes [onDone] on the main thread so the caller can leave the screen. */
    fun deleteBook(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deleteBook(bookId)
            onDone()
        }
    }
}
