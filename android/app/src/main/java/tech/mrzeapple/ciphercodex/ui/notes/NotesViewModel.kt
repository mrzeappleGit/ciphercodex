package tech.mrzeapple.ciphercodex.ui.notes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import tech.mrzeapple.ciphercodex.CipherCodexApp
import tech.mrzeapple.ciphercodex.data.db.NotebookEntity
import tech.mrzeapple.ciphercodex.data.db.NotebookPageEntity

/** One card in the NOTES grid: a notebook plus its rendered pages (by seq). */
data class NotebookCard(
    val notebook: NotebookEntity,
    val pages: List<NotebookPageEntity>,
) {
    val coverPath: String? get() = pages.firstOrNull { it.imagePath.isNotEmpty() }?.imagePath
}

class NotesViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = (application as CipherCodexApp).database.notesDao()

    val notebooks: StateFlow<List<NotebookCard>> =
        combine(dao.observeNotebooks(), dao.observeAllPages()) { nbs, pages ->
            val bynb = pages.groupBy { it.notebookGuid }
            nbs.map { NotebookCard(it, bynb[it.guid].orEmpty().sortedBy { p -> p.seq }) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
