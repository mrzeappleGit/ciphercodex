package tech.mrzeapple.ciphercodex.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tech.mrzeapple.ciphercodex.CipherCodexApp
import tech.mrzeapple.ciphercodex.appScope
import tech.mrzeapple.ciphercodex.data.db.StrokeEntity
import tech.mrzeapple.ciphercodex.sync.webdav.InkPoint
import tech.mrzeapple.ciphercodex.sync.webdav.InkPoints

enum class EditorTool { PEN, ERASE }

class InkEditorViewModel(private val app: CipherCodexApp, val pageGuid: String) : ViewModel() {
    private val author = app.inkAuthor

    val strokes: StateFlow<List<StrokeEntity>> =
        app.database.notesDao().observeLiveStrokesForPage(pageGuid)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tool = MutableStateFlow(EditorTool.PEN)

    private sealed interface Op { val s: StrokeEntity }
    private data class Add(override val s: StrokeEntity) : Op
    private data class Erase(override val s: StrokeEntity) : Op

    private val undoStack = ArrayDeque<Op>()
    private val redoStack = ArrayDeque<Op>()
    // Serializes DB call + stack mutation per op; kotlinx Mutex is fair (FIFO),
    // so ops apply in initiation order even when a DB call suspends.
    private val opMutex = Mutex()
    val canUndo = MutableStateFlow(false)
    val canRedo = MutableStateFlow(false)
    private fun bump() { canUndo.value = undoStack.isNotEmpty(); canRedo.value = redoStack.isNotEmpty() }

    fun commitStroke(points: List<InkPoint>, onCommitted: (() -> Unit)? = null) {
        viewModelScope.launch {
            opMutex.withLock {
                author.commitStroke(pageGuid, points)?.let {
                    undoStack.addLast(Add(it)); redoStack.clear(); bump()
                }
            }
            // InProgressStrokesView.removeFinishedStrokes must run on the UI thread;
            // viewModelScope is already Main.immediate but hop explicitly so this holds
            // even if the Room suspend call ever resumes on another dispatcher.
            withContext(Dispatchers.Main) { onCommitted?.invoke() }
        }
    }

    fun eraseAt(x: Float, y: Float) {
        val candidates = strokes.value.map { it.guid to InkPoints.decode(it.pointsB64) }
        val hit = InkHitTest.strokeAt(candidates, x, y, tol = 0.012f) ?: return
        viewModelScope.launch {
            opMutex.withLock {
                author.eraseStroke(hit)?.let { undoStack.addLast(Erase(it)); redoStack.clear(); bump() }
            }
        }
    }

    fun undo() {
        viewModelScope.launch {
            opMutex.withLock {
                val op = undoStack.removeLastOrNull() ?: return@withLock
                when (op) {
                    // eraseStroke == null: stroke already gone (e.g. sync merge) — drop the op.
                    is Add -> if (author.eraseStroke(op.s.guid) != null) redoStack.addLast(op)
                    is Erase -> { author.restoreStroke(op.s); redoStack.addLast(op) }
                }
                bump()
            }
        }
    }

    fun redo() {
        viewModelScope.launch {
            opMutex.withLock {
                val op = redoStack.removeLastOrNull() ?: return@withLock
                when (op) {
                    is Add -> { author.restoreStroke(op.s); undoStack.addLast(op) }
                    // eraseStroke == null: stroke already gone — drop the op.
                    is Erase -> if (author.eraseStroke(op.s.guid) != null) undoStack.addLast(op)
                }
                bump()
            }
        }
    }

    /** Survives VM death: render + push on the app scope (the rM2's Home-return twin). */
    fun onEditorClosed() {
        appScope.launch {
            author.renderPageNow(pageGuid)
            app.webdavSync.syncNow()
        }
    }

    companion object {
        fun factory(app: CipherCodexApp, pageGuid: String) = viewModelFactory {
            initializer { InkEditorViewModel(app, pageGuid) }
        }
    }
}
