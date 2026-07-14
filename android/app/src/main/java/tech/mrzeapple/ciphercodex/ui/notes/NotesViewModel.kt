package tech.mrzeapple.ciphercodex.ui.notes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tech.mrzeapple.ciphercodex.CipherCodexApp
import tech.mrzeapple.ciphercodex.data.db.NotebookEntity
import tech.mrzeapple.ciphercodex.data.db.NotebookPageEntity

/** One card in the NOTES grid: a notebook plus its rendered pages (by seq). */
data class NotebookCard(
    val notebook: NotebookEntity,
    val pages: List<NotebookPageEntity>,
) {
    val coverPage: NotebookPageEntity? get() = pages.firstOrNull { it.imagePath.isNotEmpty() }
}

class NotesViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = (application as CipherCodexApp).database.notesDao()
    private val author = (application as CipherCodexApp).inkAuthor

    /** Search box above the grid; empty = show everything. */
    val query = MutableStateFlow("")

    /** Unfiltered notebook count, so the search box stays visible (and usable)
     *  even when a query filters the grid down to zero matches. */
    val hasNotebooks: StateFlow<Boolean> =
        dao.observeNotebooks()
            .map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val pageTexts: StateFlow<Map<String, String>> =
        dao.observeAllPageTexts()
            .map { texts -> texts.associate { it.pageGuid to it.text } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val notebooks: StateFlow<List<NotebookCard>> =
        combine(dao.observeNotebooks(), dao.observeAllPages(), pageTexts, query) { nbs, pages, texts, q ->
            val bynb = pages.groupBy { it.notebookGuid }
            val cards = nbs.map { NotebookCard(it, bynb[it.guid].orEmpty().sortedBy { p -> p.seq }) }
            val needle = q.trim()
            if (needle.isEmpty()) {
                cards
            } else {
                cards.filter { card ->
                    card.notebook.title.contains(needle, ignoreCase = true) ||
                        card.pages.any { texts[it.guid]?.contains(needle, ignoreCase = true) == true }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Returns the new page guid so the UI can jump straight into the editor. */
    suspend fun newNotebook(title: String): String {
        val nb = author.createNotebook(title.ifBlank { "Notebook" })
        return author.createPage(nb)
    }

    suspend fun newPage(notebookGuid: String): String = author.createPage(notebookGuid)
}
