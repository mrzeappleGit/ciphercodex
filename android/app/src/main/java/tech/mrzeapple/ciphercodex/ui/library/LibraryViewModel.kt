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
import tech.mrzeapple.ciphercodex.data.ImportResult

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data object Working : ImportUiState
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

    fun importEpub(uri: Uri) {
        if (_importState.value is ImportUiState.Working) return
        resetJob?.cancel()
        _importState.value = ImportUiState.Working
        viewModelScope.launch {
            _importState.value = when (val result = repository.importEpub(uri)) {
                is ImportResult.Imported -> ImportUiState.Done("IMPORTED")
                is ImportResult.Duplicate -> ImportUiState.Done("ALREADY IN LIBRARY")
                is ImportResult.Failed -> ImportUiState.Error(result.message)
            }
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
