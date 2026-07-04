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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.mrzeapple.ciphercodex.CipherCodexApp
import tech.mrzeapple.ciphercodex.data.BookWithProgress
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

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _filter = MutableStateFlow(LibraryFilter.ALL)
    val filter: StateFlow<LibraryFilter> = _filter

    val sort: StateFlow<LibrarySort> =
        prefs.settings.map { it.librarySort }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibrarySort.RECENT)

    /** Library rows after search + reading-state filter + chosen sort. The
     *  repository already emits RECENT order, so that sort is a passthrough. */
    val books: StateFlow<List<BookWithProgress>> =
        combine(repository.observeLibrary(), _query, _filter, sort) { all, query, filter, sort ->
            val matched = all.filter { matchesQuery(it, query) && matchesFilter(it, filter) }
            sortBooks(matched, sort)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** True when the library holds no books at all (vs. an empty search/filter
     *  result), so the screen shows the import prompt rather than "no matches". */
    val isLibraryEmpty: StateFlow<Boolean> =
        repository.observeLibrary().map { it.isEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setFilter(value: LibraryFilter) {
        _filter.value = value
    }

    fun setSort(value: LibrarySort) {
        viewModelScope.launch { prefs.setLibrarySort(value) }
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
