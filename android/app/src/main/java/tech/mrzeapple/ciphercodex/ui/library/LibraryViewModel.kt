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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.mrzeapple.ciphercodex.CipherCodexApp
import tech.mrzeapple.ciphercodex.data.BookWithProgress

sealed interface ImportUiState {
    data object Idle : ImportUiState
    /** [current] is the 1-based index of the file being imported. */
    data class Working(val current: Int, val total: Int) : ImportUiState
    data class Done(val message: String) : ImportUiState
    data class Error(val message: String) : ImportUiState
}

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as CipherCodexApp).repository

    val books: StateFlow<List<BookWithProgress>> =
        repository.observeLibrary()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
