package tech.mrzeapple.ciphercodex.ui.reader

import android.app.Application
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.mrzeapple.ciphercodex.CipherCodexApp
import tech.mrzeapple.ciphercodex.data.db.BookEntity
import tech.mrzeapple.ciphercodex.data.db.BookmarkEntity
import tech.mrzeapple.ciphercodex.data.prefs.ReadingTheme
import tech.mrzeapple.ciphercodex.data.stats.SessionRecorder
import tech.mrzeapple.ciphercodex.data.prefs.Settings
import tech.mrzeapple.ciphercodex.epub.Block
import tech.mrzeapple.ciphercodex.epub.Epub
import tech.mrzeapple.ciphercodex.epub.EpubChapter
import tech.mrzeapple.ciphercodex.epub.EpubDocument
import tech.mrzeapple.ciphercodex.epub.EpubParseException
import tech.mrzeapple.ciphercodex.epub.EpubTocEntry
import tech.mrzeapple.ciphercodex.sync.PullResult
import java.io.File
import kotlin.math.max

data class ReaderPosition(val spineIndex: Int, val charOffset: Int)

sealed interface ReaderUiState {
    data object Loading : ReaderUiState
    data class Ready(val title: String, val spineCount: Int) : ReaderUiState
    data class Error(val message: String) : ReaderUiState
}

private const val SAVE_DEBOUNCE_MS = 500L
private const val PAGE_CACHE_LIMIT = 8

class ReaderViewModel(application: Application, private val bookId: Long) :
    AndroidViewModel(application) {

    private val app = application as CipherCodexApp
    private val repository = app.repository
    private val dao = app.database.bookDao()
    private val prefs = app.prefs
    private val syncManager = app.syncManager

    private val _uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val uiState: StateFlow<ReaderUiState> = _uiState

    val settings: StateFlow<Settings?> =
        prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _position = MutableStateFlow(ReaderPosition(0, 0))
    val position: StateFlow<ReaderPosition> = _position

    private val _percentage = MutableStateFlow(0f)
    val percentage: StateFlow<Float> = _percentage

    private val _syncPrompt = MutableStateFlow<PullResult.RemoteNewer?>(null)
    val syncPrompt: StateFlow<PullResult.RemoteNewer?> = _syncPrompt

    private val _toc = MutableStateFlow<List<EpubTocEntry>>(emptyList())
    val toc: StateFlow<List<EpubTocEntry>> = _toc

    val bookmarks: StateFlow<List<BookmarkEntity>> =
        dao.observeBookmarks(bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var book: BookEntity? = null
    private var doc: EpubDocument? = null
    private val recorder = SessionRecorder(app.database.statsDao(), bookId)

    private data class PageCacheKey(
        val spineIndex: Int,
        val widthPx: Int,
        val heightPx: Int,
        val fontScale: Float,
        // Line spacing, font family and justification all change the measured
        // layout, so each is part of the page-cut identity.
        val lineSpacing: Float,
        val fontFamily: String,
        val justify: Boolean,
        // System font scale / display density are part of the layout identity:
        // this ViewModel outlives config changes, so a system font-size change
        // must not serve page cuts measured under the old Density.
        val sysFontScale: Float,
        val sysDensity: Float,
    )

    private val pageCache = LinkedHashMap<PageCacheKey, PaginatedChapter>()

    private data class PendingSave(val spineIndex: Int, val charOffset: Int, val percentage: Float)

    private var pendingSave: PendingSave? = null
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val b = repository.bookById(bookId)
                if (b == null) {
                    _uiState.value = ReaderUiState.Error("BOOK NOT FOUND")
                    return@launch
                }
                val d = withContext(Dispatchers.IO) { Epub.open(File(b.filePath)) }
                if (d.spineCount == 0) {
                    d.close()
                    _uiState.value = ReaderUiState.Error("EMPTY BOOK")
                    return@launch
                }
                book = b
                doc = d
                _toc.value = d.toc
                repository.markOpened(bookId)
                val saved = dao.progressFor(bookId)
                if (saved != null) {
                    _position.value = ReaderPosition(
                        spineIndex = saved.spineIndex.coerceIn(0, d.spineCount - 1),
                        charOffset = max(0, saved.charOffset),
                    )
                    _percentage.value = saved.percentage
                }
                _uiState.value = ReaderUiState.Ready(title = b.title, spineCount = d.spineCount)
                recorder.onSessionStart(_percentage.value)
                val pull = try {
                    syncManager.pullOnOpen(b)
                } catch (e: Exception) {
                    PullResult.Failed(e.message ?: "sync failed")
                }
                if (pull is PullResult.RemoteNewer) _syncPrompt.value = pull
            } catch (e: Exception) {
                _uiState.value = ReaderUiState.Error(e.message ?: "FAILED TO OPEN BOOK")
            }
        }
    }

    /** Measures + cuts the chapter, memoized on (spine, size, fontScale,
     *  system font scale/density) — theme only recolors, it never reflows.
     *  Blocking; call off the main thread. */
    fun paginated(
        spineIndex: Int,
        widthPx: Int,
        heightPx: Int,
        fontScale: Float,
        lineSpacing: Float,
        fontFamily: String,
        justify: Boolean,
        sysFontScale: Float,
        sysDensity: Float,
        measurer: TextMeasurer,
        style: TextStyle,
    ): PaginatedChapter {
        val key = PageCacheKey(
            spineIndex, widthPx, heightPx, fontScale, lineSpacing, fontFamily, justify,
            sysFontScale, sysDensity,
        )
        synchronized(pageCache) { pageCache[key]?.let { return it } }
        // Any failure paginates as a visible placeholder page: a silent failure
        // here is an unrecoverable blank reader, the worst possible outcome.
        val chapter = try {
            checkNotNull(doc) { "document not open" }.chapter(spineIndex)
        } catch (e: EpubParseException) {
            placeholderChapter(spineIndex, "[ CHAPTER UNREADABLE — MALFORMED CONTENT ]")
        } catch (e: Exception) {
            placeholderChapter(spineIndex, "[ CHAPTER FAILED TO LOAD — ${e.javaClass.simpleName} ]")
        }
        val result = try {
            paginate(chapter, measurer, style, widthPx, heightPx, justify)
        } catch (e: Exception) {
            paginate(
                placeholderChapter(spineIndex, "[ PAGE LAYOUT FAILED — ${e.javaClass.simpleName} ]"),
                measurer, style, widthPx, heightPx, justify,
            )
        }
        synchronized(pageCache) {
            if (pageCache.size >= PAGE_CACHE_LIMIT) {
                pageCache.keys.firstOrNull()?.let { pageCache.remove(it) }
            }
            pageCache[key] = result
        }
        return result
    }

    private fun placeholderChapter(spineIndex: Int, message: String) =
        EpubChapter(spineIndex, listOf(Block.Paragraph(AnnotatedString(message))))

    /** Raw bytes of an in-flow image (Page.imagePath); null when unavailable. */
    fun imageBytes(zipPath: String): ByteArray? =
        try {
            doc?.imageBytes(zipPath)
        } catch (e: Exception) {
            null
        }

    fun moveTo(spineIndex: Int, charOffset: Int) {
        val d = doc ?: return
        _position.value = ReaderPosition(
            spineIndex = spineIndex.coerceIn(0, d.spineCount - 1),
            charOffset = max(0, charOffset),
        )
    }

    /** Called whenever a page lands on screen: updates the whole-book
     *  percentage and schedules a debounced progress save. */
    fun onPageShown(spineIndex: Int, page: Page, chapterCharCount: Int) {
        val pct = wholeBookPercentage(spineIndex, page.startChar, chapterCharCount)
        _percentage.value = pct
        recorder.onPageShown(pct)
        pendingSave = PendingSave(spineIndex, page.startChar, pct)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            flushSave()
        }
    }

    private fun wholeBookPercentage(spineIndex: Int, startChar: Int, chapterCharCount: Int): Float {
        val weights = doc?.spineWeights ?: return 0f
        if (weights.isEmpty()) return 0f
        val total = weights.sum().toDouble().coerceAtLeast(1.0)
        var before = 0.0
        for (i in 0 until spineIndex.coerceIn(0, weights.size)) before += weights[i]
        val weight = weights.getOrElse(spineIndex) { 1L }.toDouble()
        val within = (startChar.toDouble() / max(1, chapterCharCount)).coerceIn(0.0, 1.0)
        return ((before + weight * within) / total).toFloat().coerceIn(0f, 1f)
    }

    private fun takePendingSave(): PendingSave? {
        val p = pendingSave
        pendingSave = null
        return p
    }

    private suspend fun flushSave() {
        val p = takePendingSave() ?: return
        repository.saveProgress(bookId, p.spineIndex, p.charOffset, p.percentage)
    }

    /** Immediate save + kosync push. NonCancellable so leaving the screen
     *  (and clearing this ViewModel) doesn't kill it. */
    fun flushAndPush() {
        saveJob?.cancel()
        val p = takePendingSave()
        val b = book ?: return
        val pct = _percentage.value
        viewModelScope.launch(NonCancellable + Dispatchers.IO) {
            if (p != null) repository.saveProgress(bookId, p.spineIndex, p.charOffset, p.percentage)
            recorder.flush(pct)
            try {
                syncManager.pushProgress(b)
            } catch (_: Exception) {
                // fire-and-forget: a failed push stays dirty and retries later
            }
        }
    }

    /** ON_RESUME: restart the reading session the ON_PAUSE flush closed.
     *  No-op while a session is already active or before the book is ready. */
    fun onResumed() {
        if (_uiState.value is ReaderUiState.Ready) {
            recorder.onSessionStart(_percentage.value)
        }
    }

    fun jumpToRemote() {
        val prompt = _syncPrompt.value ?: return
        _syncPrompt.value = null
        val d = doc ?: return
        val spine = prompt.spineIndex
        val offset = prompt.charOffset
        if (spine != null && offset != null) {
            moveTo(spine, offset)
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            val weights = d.spineWeights
            if (weights.isEmpty()) return@launch
            val total = weights.sum().toDouble().coerceAtLeast(1.0)
            val target = prompt.percentage.toDouble().coerceIn(0.0, 1.0) * total
            val index: Int
            val within: Double
            if (spine != null) {
                // A KOReader xpointer names the exact chapter but not the char
                // offset; keep that chapter (clamped — a foreign index can
                // exceed our spine) and derive only the within-chapter fraction
                // from percentage.
                index = spine.coerceIn(0, weights.lastIndex)
                var before = 0.0
                for (i in 0 until index) before += weights[i]
                within = ((target - before) / weights[index].toDouble().coerceAtLeast(1.0))
                    .coerceIn(0.0, 1.0)
            } else {
                var before = 0.0
                var idx = weights.lastIndex
                for (i in weights.indices) {
                    if (before + weights[i] >= target) {
                        idx = i
                        break
                    }
                    before += weights[i]
                }
                index = idx
                within = ((target - before) / weights[idx].toDouble().coerceAtLeast(1.0))
                    .coerceIn(0.0, 1.0)
            }
            val length = try {
                buildChapterText(d.chapter(index)).text.length
            } catch (e: Exception) {
                0
            }
            moveTo(index, (within * length).toInt())
        }
    }

    fun dismissSyncPrompt() {
        _syncPrompt.value = null
    }

    /** Maps a whole-book fraction to a concrete position, parsing the target
     *  chapter for its char length. Blocking; call off the main thread. */
    private fun positionForFraction(fraction: Float): ReaderPosition? {
        val d = doc ?: return null
        val weights = d.spineWeights
        if (weights.isEmpty()) return null
        val total = weights.sum().toDouble().coerceAtLeast(1.0)
        val target = fraction.toDouble().coerceIn(0.0, 1.0) * total
        var before = 0.0
        var idx = weights.lastIndex
        for (i in weights.indices) {
            if (before + weights[i] >= target) {
                idx = i
                break
            }
            before += weights[i]
        }
        val within = ((target - before) / weights[idx].toDouble().coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
        val length = try {
            buildChapterText(d.chapter(idx)).text.length
        } catch (e: Exception) {
            0
        }
        return ReaderPosition(idx, (within * length).toInt())
    }

    /** Seek to a whole-book fraction (0f..1f) — drives the scrubber. */
    fun seekToFraction(fraction: Float) {
        viewModelScope.launch(Dispatchers.Default) {
            val pos = positionForFraction(fraction) ?: return@launch
            moveTo(pos.spineIndex, pos.charOffset)
        }
    }

    fun addBookmark(spineIndex: Int, charOffset: Int, percentage: Float, label: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertBookmark(
                BookmarkEntity(
                    bookId = bookId,
                    spineIndex = spineIndex,
                    charOffset = charOffset,
                    percentage = percentage,
                    label = label.trim().take(80),
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { dao.deleteBookmark(id) }
    }

    fun setTheme(theme: ReadingTheme) {
        viewModelScope.launch { prefs.setReadingTheme(theme) }
    }

    fun stepFontScale(delta: Float) {
        viewModelScope.launch {
            val current = settings.value?.fontScale ?: prefs.current().fontScale
            prefs.setFontScale(current + delta)
        }
    }

    override fun onCleared() {
        saveJob?.cancel()
        val p = takePendingSave()
        val b = book
        val d = doc
        doc = null
        val pct = _percentage.value
        // viewModelScope is already cancelled here; NonCancellable re-parents
        // the coroutine so the final flush + push still run.
        viewModelScope.launch(NonCancellable + Dispatchers.IO) {
            if (p != null) repository.saveProgress(bookId, p.spineIndex, p.charOffset, p.percentage)
            recorder.flush(pct)
            if (b != null) {
                try {
                    syncManager.pushProgress(b)
                } catch (_: Exception) {
                }
            }
            try {
                d?.close()
            } catch (_: Exception) {
            }
        }
    }
}
