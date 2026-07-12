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
import tech.mrzeapple.ciphercodex.ui.theme.HighlightPalette

/** Backs the KEPT tab: every highlight across the library, newest first. */
class KeptViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = (application as CipherCodexApp).database.bookDao()

    val highlights: StateFlow<List<HighlightWithBook>> =
        dao.observeAllHighlights()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(id: Long) {
        viewModelScope.launch { dao.deleteHighlight(id, System.currentTimeMillis()) }
    }

    fun updateAnnotation(id: Long, note: String?, colorId: Int) {
        viewModelScope.launch {
            dao.setHighlightAnnotation(id, note, colorId.coerceIn(0, HighlightPalette.lastIndex), System.currentTimeMillis())
        }
    }
}
