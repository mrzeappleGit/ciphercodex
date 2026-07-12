package tech.mrzeapple.ciphercodex.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.mrzeapple.ciphercodex.CipherCodexApp
import tech.mrzeapple.ciphercodex.data.BookWithProgress
import tech.mrzeapple.ciphercodex.data.db.BookCollectionCrossRef
import tech.mrzeapple.ciphercodex.data.db.CollectionEntity
import tech.mrzeapple.ciphercodex.data.prefs.LibrarySort

sealed interface ImportUiState {
    data object Idle : ImportUiState
    /** [current] is the 1-based index of the file being imported. */
    data class Working(val current: Int, val total: Int) : ImportUiState
    data class Done(val message: String) : ImportUiState
    data class Error(val message: String) : ImportUiState
}

enum class LibraryFilter { ALL, UNREAD, READING, FINISHED }

/** A book at/above this whole-book fraction reads as finished. */
private const val FINISHED_THRESHOLD = 0.98f

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CipherCodexApp
    private val repository = app.repository
    private val prefs = app.prefs
    private val dao = app.database.bookDao()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _filter = MutableStateFlow(LibraryFilter.ALL)
    val filter: StateFlow<LibraryFilter> = _filter

    val collections: StateFlow<List<CollectionEntity>> =
        dao.observeCollections()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bookCollections: StateFlow<List<BookCollectionCrossRef>> =
        dao.observeBookCollections()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedCollection = MutableStateFlow<Long?>(null)
    val selectedCollection: StateFlow<Long?> = _selectedCollection.asStateFlow()

    val sort: StateFlow<LibrarySort> =
        prefs.settings.map { it.librarySort }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibrarySort.RECENT)

    /** Library rows after search + reading-state filter + chosen sort. The
     *  repository already emits RECENT order, so that sort is a passthrough. */
    val books: StateFlow<List<BookWithProgress>> =
        combine(
            repository.observeLibrary(),
            _query,
            _filter,
            sort,
            // Book ids in the selected shelf, or null when no shelf is selected.
            combine(_selectedCollection, bookCollections) { selected, refs ->
                selected?.let { id -> refs.filter { it.collectionId == id }.mapTo(HashSet()) { it.bookId } }
            },
        ) { all, query, filter, sort, inCollection ->
            all.asSequence()
                .filter { matchesQuery(it, query) && matchesFilter(it, filter) }
                .filter { inCollection == null || it.book.id in inCollection }
                .toList()
                .let { sortBooks(it, sort) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** True when the library holds no books at all (vs. an empty search/filter
     *  result), so the screen shows the import prompt rather than "no matches". */
    val isLibraryEmpty: StateFlow<Boolean> =
        repository.observeLibrary().map { it.isEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Book count per reading-state filter, over the full library, for the chips. */
    val filterCounts: StateFlow<Map<LibraryFilter, Int>> =
        repository.observeLibrary()
            .map { all -> LibraryFilter.entries.associateWith { f -> all.count { matchesFilter(it, f) } } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setFilter(value: LibraryFilter) {
        _filter.value = value
    }

    fun setSort(value: LibrarySort) {
        viewModelScope.launch { prefs.setLibrarySort(value) }
    }

    fun setCollectionFilter(collectionId: Long?) {
        _selectedCollection.value = collectionId
    }

    fun createCollection(name: String, addBookId: Long? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val id = dao.insertCollection(CollectionEntity(name = trimmed, createdAt = System.currentTimeMillis()))
            if (addBookId != null) dao.addBookToCollection(BookCollectionCrossRef(id, addBookId))
        }
    }

    fun deleteCollection(collectionId: Long) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            dao.deleteCollectionMembers(collectionId, now)
            dao.deleteCollection(collectionId, now)
        }
        if (_selectedCollection.value == collectionId) _selectedCollection.value = null
    }

    fun setBookInCollection(bookId: Long, collectionId: Long, inShelf: Boolean) {
        viewModelScope.launch {
            if (inShelf) dao.addBookToCollection(BookCollectionCrossRef(collectionId, bookId))
            else dao.removeBookFromCollection(collectionId, bookId, System.currentTimeMillis())
        }
    }

    private fun matchesQuery(entry: BookWithProgress, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return entry.book.title.contains(q, ignoreCase = true) ||
            (entry.book.author?.contains(q, ignoreCase = true) == true)
    }

    private fun matchesFilter(entry: BookWithProgress, filter: LibraryFilter): Boolean {
        val p = entry.percentage
        return when (filter) {
            LibraryFilter.ALL -> true
            LibraryFilter.UNREAD -> p == null
            LibraryFilter.READING -> p != null && p < FINISHED_THRESHOLD
            LibraryFilter.FINISHED -> p != null && p >= FINISHED_THRESHOLD
        }
    }

    private fun sortBooks(books: List<BookWithProgress>, sort: LibrarySort): List<BookWithProgress> =
        when (sort) {
            LibrarySort.RECENT -> books // repository already emits recent-first
            LibrarySort.TITLE -> books.sortedBy { it.book.title.lowercase() }
            LibrarySort.AUTHOR -> books.sortedWith(compareBy(nullsLast<String>()) { it.book.author?.lowercase() })
            LibrarySort.ADDED -> books.sortedByDescending { it.book.addedAt }
            LibrarySort.PROGRESS -> books.sortedByDescending { it.percentage ?: -1f }
        }

    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val importState: StateFlow<ImportUiState> = _importState

    private var resetJob: Job? = null

    fun importEpubs(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (_importState.value is ImportUiState.Working) return
        resetJob?.cancel()
        _importState.value = ImportUiState.Working(current = 1, total = uris.size)
        viewModelScope.launch {
            // Sequential on purpose: the repository sweeps stale import temps
            // at each import start, which is only safe with no live sibling.
            val results = uris.mapIndexed { index, uri ->
                _importState.value = ImportUiState.Working(current = index + 1, total = uris.size)
                repository.importEpub(uri)
            }
            _importState.value = summarizeImports(results)
            resetJob = launch {
                delay(RESET_DELAY_MS)
                _importState.value = ImportUiState.Idle
            }
        }
    }

    fun delete(bookId: Long) {
        viewModelScope.launch { repository.deleteBook(bookId) }
    }

    private companion object {
        const val RESET_DELAY_MS = 3_000L
    }
}
