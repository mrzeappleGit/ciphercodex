package tech.mrzeapple.ciphercodex.ui.opds

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.mrzeapple.ciphercodex.CipherCodexApp
import tech.mrzeapple.ciphercodex.data.ImportResult
import tech.mrzeapple.ciphercodex.opds.OpdsClient
import tech.mrzeapple.ciphercodex.opds.OpdsEntry
import tech.mrzeapple.ciphercodex.opds.OpdsFeed
import tech.mrzeapple.ciphercodex.opds.OpdsResult

data class OpdsUiState(
    val url: String = OpdsViewModel.DEFAULT_CATALOG,
    val loading: Boolean = false,
    val feed: OpdsFeed? = null,
    val error: String? = null,
    val downloadStatus: String? = null,
    val canGoBack: Boolean = false,
)

class OpdsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as CipherCodexApp).repository
    private val client = OpdsClient()
    private val history = ArrayDeque<String>()

    private val _state = MutableStateFlow(OpdsUiState())
    val state: StateFlow<OpdsUiState> = _state.asStateFlow()

    fun setUrl(value: String) = _state.update { it.copy(url = value) }

    /** Fetch [url]; a fresh navigation pushes history, Back does not. */
    fun open(url: String, pushHistory: Boolean = true) {
        val target = url.trim()
        if (target.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, url = target) }
            when (val result = client.fetch(target)) {
                is OpdsResult.Ok -> {
                    if (pushHistory && history.lastOrNull() != target) history.addLast(target)
                    _state.update {
                        it.copy(loading = false, feed = result.feed, error = null, canGoBack = history.size > 1)
                    }
                }
                is OpdsResult.Err ->
                    _state.update { it.copy(loading = false, error = result.message) }
            }
        }
    }

    fun back() {
        if (history.size > 1) {
            history.removeLast()
            open(history.last(), pushHistory = false)
        }
    }

    fun download(entry: OpdsEntry) {
        val href = entry.acquireHref ?: return
        viewModelScope.launch {
            _state.update { it.copy(downloadStatus = "DOWNLOADING ${entry.title.uppercase()}") }
            val message = when (val result = repository.importEpubFromUrl(href)) {
                is ImportResult.Imported -> "IMPORTED ${entry.title.uppercase()}"
                is ImportResult.Duplicate -> "ALREADY IN LIBRARY: ${entry.title.uppercase()}"
                is ImportResult.Failed -> result.message
            }
            _state.update { it.copy(downloadStatus = message) }
        }
    }

    companion object {
        // Project Gutenberg's public OPDS catalog (no auth). Slow at times, so
        // the client uses a generous read timeout.
        const val DEFAULT_CATALOG = "https://m.gutenberg.org/ebooks.opds/"
    }
}
