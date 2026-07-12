package tech.mrzeapple.ciphercodex.ui.reader

import android.app.Application
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.mrzeapple.ciphercodex.CipherCodexApp
import tech.mrzeapple.ciphercodex.data.db.BookEntity
import tech.mrzeapple.ciphercodex.data.db.BookmarkEntity
import tech.mrzeapple.ciphercodex.data.db.HighlightEntity
import tech.mrzeapple.ciphercodex.data.prefs.ReadingTheme
import tech.mrzeapple.ciphercodex.data.stats.SessionRecorder
import tech.mrzeapple.ciphercodex.data.prefs.Settings
import tech.mrzeapple.ciphercodex.epub.Block
import tech.mrzeapple.ciphercodex.epub.Epub
import tech.mrzeapple.ciphercodex.epub.EpubChapter
import tech.mrzeapple.ciphercodex.epub.textLength
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
private const val MIN_SEARCH_LEN = 2
private const val MAX_SEARCH_HITS = 300
private const val SEARCH_CONTEXT = 36
// Reading-speed fallback (pages/min) until the reader has enough turns of its
// own to estimate a personal pace; page size is user-specific, so this is only
// a first-open placeholder that self-corrects once sessions accumulate.
private const val DEFAULT_PAGES_PER_MIN = 1.8f
private const val MIN_PAGES_FOR_SPEED = 10
private const val RETURN_STACK_MAX = 10
private const val NOTE_MAX_CHARS = 600

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

    /** Personal reading speed (pages/min) derived from past sessions; a default
     *  until enough pages have been turned. Drives the time-left readout. */
    private val _pagesPerMinute = MutableStateFlow(DEFAULT_PAGES_PER_MIN)
    val pagesPerMinute: StateFlow<Float> = _pagesPerMinute

    /** Total character weight of the whole book (0 until the document opens). */
    private val _bookChars = MutableStateFlow(0L)
    val bookChars: StateFlow<Long> = _bookChars

    /** Whole-book start fraction of each spine item — the Book Map's chapter ticks. */
    private val _chapterFractions = MutableStateFlow<List<Float>>(emptyList())
    val chapterFractions: StateFlow<List<Float>> = _chapterFractions

    private val _syncPrompt = MutableStateFlow<PullResult.RemoteNewer?>(null)
    val syncPrompt: StateFlow<PullResult.RemoteNewer?> = _syncPrompt

    private val _toc = MutableStateFlow<List<EpubTocEntry>>(emptyList())
    val toc: StateFlow<List<EpubTocEntry>> = _toc

    val bookmarks: StateFlow<List<BookmarkEntity>> =
        dao.observeBookmarks(bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val highlights: StateFlow<List<HighlightEntity>> =
        dao.observeHighlights(bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** One full-text search match. [charOffset] is in the same char space as
     *  [moveTo], so a hit jumps straight to the word. */
    data class SearchHit(
        val spineIndex: Int,
        val charOffset: Int,
        val chapterLabel: String,
        val snippet: AnnotatedString,
    )

    private val _searchResults = MutableStateFlow<List<SearchHit>>(emptyList())
    val searchResults: StateFlow<List<SearchHit>> = _searchResults

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    private var searchJob: Job? = null

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
                val weights = d.spineWeights
                _bookChars.value = weights.sum()
                val total = weights.sum().toDouble().coerceAtLeast(1.0)
                var acc = 0L
                _chapterFractions.value = weights.map { w ->
                    val f = (acc.toDouble() / total).toFloat()
                    acc += w
                    f
                }
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
        // Personal reading pace from history (all books), for the time-left readout.
        viewModelScope.launch(Dispatchers.IO) {
            val statsDao = app.database.statsDao()
            val pages = statsDao.totalPagesTurned()
            val ms = statsDao.totalReadingMs()
            if (pages >= MIN_PAGES_FOR_SPEED && ms > 0) {
                _pagesPerMinute.value = (pages.toFloat() / (ms.toFloat() / 60_000f)).coerceIn(0.3f, 12f)
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

    // Exploratory jumps (TOC / search / bookmark / scrubber) remember where you
    // were so the RETURN pill can bring you back; page turns do not.
    private val returnStack = ArrayDeque<ReaderPosition>()
    private val _canReturn = MutableStateFlow(false)
    val canReturn: StateFlow<Boolean> = _canReturn

    private fun pushReturn() {
        returnStack.addLast(_position.value)
        while (returnStack.size > RETURN_STACK_MAX) returnStack.removeFirst()
        _canReturn.value = true
    }

    /** A jump that can be undone: records the current position, then moves. */
    fun jumpTo(spineIndex: Int, charOffset: Int) {
        pushReturn()
        moveTo(spineIndex, charOffset)
    }

    /** Pop back to the position before the last jump. */
    fun returnBack() {
        val prev = returnStack.removeLastOrNull() ?: return
        _canReturn.value = returnStack.isNotEmpty()
        moveTo(prev.spineIndex, prev.charOffset)
    }

    private val _footnote = MutableStateFlow<String?>(null)
    val footnote: StateFlow<String?> = _footnote

    /** Follows a tapped internal link: a short, same-chapter target opens as a
     *  footnote popup; anything else (cross-file, a heading, a long block) is a
     *  navigation jump, so it lands on the RETURN stack. No-op for external URLs. */
    fun followLink(spineIndex: Int, href: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val d = doc ?: return@launch
            val target = d.resolveLink(spineIndex, href) ?: return@launch
            val chapter = try {
                d.chapter(target.spineIndex)
            } catch (e: Exception) {
                return@launch
            }
            val blockIndex = target.anchor?.let { chapter.anchors[it] }
            val noteText = when (val block = blockIndex?.let { chapter.blocks.getOrNull(it) }) {
                is Block.Paragraph -> block.text.text
                is Block.Heading -> block.text.text
                else -> null
            }?.trim()
            if (noteText != null && target.spineIndex == spineIndex && noteText.length <= NOTE_MAX_CHARS) {
                _footnote.value = noteText.take(1200)
            } else {
                val charOffset = blockIndex?.let { charOffsetOfBlock(chapter, it) } ?: 0
                withContext(Dispatchers.Main.immediate) { jumpTo(target.spineIndex, charOffset) }
            }
        }
    }

    /** Char offset where [blockIndex] begins in the built chapter text, matching
     *  buildChapterText's one-separator-per-block layout (approximate landing). */
    private fun charOffsetOfBlock(chapter: EpubChapter, blockIndex: Int): Int {
        var offset = 0
        val end = blockIndex.coerceIn(0, chapter.blocks.size)
        for (i in 0 until end) offset += chapter.blocks[i].textLength + 1
        return offset
    }

    fun dismissFootnote() {
        _footnote.value = null
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
            try {
                app.webdavSync.syncIfDue(5 * 60_000L)
            } catch (_: Exception) {
                // fire-and-forget, same as the kosync push above
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
        pushReturn()
        viewModelScope.launch(Dispatchers.Default) {
            val pos = positionForFraction(fraction) ?: return@launch
            moveTo(pos.spineIndex, pos.charOffset)
        }
    }

    /** Full-text search across the whole book, off the main thread. A new query
     *  supersedes the previous one; results are capped at [MAX_SEARCH_HITS]. */
    fun search(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.length < MIN_SEARCH_LEN) {
            _searchResults.value = emptyList()
            _searching.value = false
            return
        }
        val d = doc ?: return
        _searching.value = true
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            val hits = ArrayList<SearchHit>()
            for (spine in 0 until d.spineCount) {
                ensureActive()
                val text = try {
                    buildChapterText(d.chapter(spine)).text.text
                } catch (e: Exception) {
                    continue
                }
                var from = 0
                while (from <= text.length - q.length) {
                    val idx = text.indexOf(q, from, ignoreCase = true)
                    if (idx < 0) break
                    hits += SearchHit(spine, idx, "CH ${spine + 1}", searchSnippet(text, idx, q.length))
                    if (hits.size >= MAX_SEARCH_HITS) {
                        _searchResults.value = hits
                        _searching.value = false
                        return@launch
                    }
                    from = idx + q.length
                }
            }
            _searchResults.value = hits
            _searching.value = false
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchResults.value = emptyList()
        _searching.value = false
    }

    /** A one-line context snippet around a match, with the match itself bolded. */
    private fun searchSnippet(text: String, matchStart: Int, matchLen: Int): AnnotatedString {
        val start = (matchStart - SEARCH_CONTEXT).coerceAtLeast(0)
        val end = (matchStart + matchLen + SEARCH_CONTEXT).coerceAtMost(text.length)
        val body = text.substring(start, end).replace('\n', ' ')
        val relStart = (if (start > 0) 1 else 0) + (matchStart - start)  // +1 for the leading ellipsis
        return buildAnnotatedString {
            if (start > 0) append('…')
            append(body)
            if (end < text.length) append('…')
            addStyle(
                SpanStyle(fontWeight = FontWeight.Bold),
                relStart.coerceIn(0, length),
                (relStart + matchLen).coerceIn(0, length),
            )
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
        viewModelScope.launch(Dispatchers.IO) { dao.deleteBookmark(id, System.currentTimeMillis()) }
    }

    fun addHighlight(spineIndex: Int, startChar: Int, endChar: Int, text: String, colorId: Int = 0) {
        if (endChar <= startChar) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertHighlight(
                HighlightEntity(
                    bookId = bookId,
                    spineIndex = spineIndex,
                    startChar = startChar,
                    endChar = endChar,
                    text = text.trim().take(200),
                    createdAt = System.currentTimeMillis(),
                    colorId = colorId,
                )
            )
        }
    }

    fun deleteHighlight(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { dao.deleteHighlight(id, System.currentTimeMillis()) }
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
