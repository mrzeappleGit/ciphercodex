package tech.mrzeapple.ciphercodex.ui.kept

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.mrzeapple.ciphercodex.CipherCodexApp
import tech.mrzeapple.ciphercodex.data.db.HighlightWithBook

/** Backs the KEPT tab: every highlight across the library, newest first. */
class KeptViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = (application as CipherCodexApp).database.bookDao()

    val highlights: StateFlow<List<HighlightWithBook>> =
        dao.observeAllHighlights()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(id: Long) {
        viewModelScope.launch { dao.deleteHighlight(id) }
    }
}
