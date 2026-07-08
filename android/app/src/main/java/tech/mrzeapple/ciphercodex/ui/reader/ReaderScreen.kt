package tech.mrzeapple.ciphercodex.ui.reader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.WindowManager
import android.provider.Settings as SystemSettings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import tech.mrzeapple.ciphercodex.ui.components.CipherShapeSmall
import tech.mrzeapple.ciphercodex.ui.theme.HighlightPalette
import tech.mrzeapple.ciphercodex.ui.theme.highlightTint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.mrzeapple.ciphercodex.MainActivity
import tech.mrzeapple.ciphercodex.epub.HREF_TAG
import tech.mrzeapple.ciphercodex.data.prefs.ReadingTheme
import tech.mrzeapple.ciphercodex.data.prefs.Settings
import tech.mrzeapple.ciphercodex.data.db.BookmarkEntity
import tech.mrzeapple.ciphercodex.data.db.HighlightEntity
import tech.mrzeapple.ciphercodex.epub.EpubTocEntry
import tech.mrzeapple.ciphercodex.sync.PullResult
import tech.mrzeapple.ciphercodex.ui.components.CipherButton
import tech.mrzeapple.ciphercodex.ui.components.CipherCaption
import tech.mrzeapple.ciphercodex.ui.components.CipherPanel
import tech.mrzeapple.ciphercodex.ui.components.CipherProgressBar
import tech.mrzeapple.ciphercodex.ui.components.CipherTextField
import tech.mrzeapple.ciphercodex.ui.theme.CipherCyan
import tech.mrzeapple.ciphercodex.ui.theme.CipherMagenta
import tech.mrzeapple.ciphercodex.ui.theme.CipherMuted
import tech.mrzeapple.ciphercodex.ui.theme.CipherPhosphor
import tech.mrzeapple.ciphercodex.ui.theme.CipherVoid
import tech.mrzeapple.ciphercodex.ui.theme.ReadingBlackBackground
import tech.mrzeapple.ciphercodex.ui.theme.ReadingBlackText
import tech.mrzeapple.ciphercodex.ui.theme.ReadingBodyStyle
import tech.mrzeapple.ciphercodex.ui.theme.ReadingContrastBackground
import tech.mrzeapple.ciphercodex.ui.theme.ReadingContrastText
import tech.mrzeapple.ciphercodex.ui.theme.ReadingNightBackground
import tech.mrzeapple.ciphercodex.ui.theme.ReadingNightText
import tech.mrzeapple.ciphercodex.ui.theme.ReadingPaperBackground
import tech.mrzeapple.ciphercodex.ui.theme.ReadingPaperText
import tech.mrzeapple.ciphercodex.ui.theme.ReadingSepiaBackground
import tech.mrzeapple.ciphercodex.ui.theme.ReadingSepiaText
import tech.mrzeapple.ciphercodex.ui.theme.readingFontFamily
import kotlin.math.roundToInt

/** The page currently on screen, pinned to the pagination + style that
 *  produced it so page offsets and rendering never mix generations. */
private data class PageSpec(
    val spineIndex: Int,
    val pageIndex: Int,
    val paginated: PaginatedChapter,
    val style: TextStyle,
)

/** A pagination result stamped with the inputs it was measured for. */
private data class PaginationResult(
    val widthPx: Int,
    val heightPx: Int,
    val fontScale: Float,
    val lineSpacing: Float,
    val fontFamily: String,
    val justify: Boolean,
    val sysFontScale: Float,
    val sysDensity: Float,
    val chapter: PaginatedChapter,
)

private enum class NavTab { CHAPTERS, BOOKMARKS, HIGHLIGHTS, SEARCH }

private fun nextReadingTheme(current: ReadingTheme): ReadingTheme {
    val values = ReadingTheme.entries
    return values[(current.ordinal + 1) % values.size]
}

// Amber wash for the warm-light overlay; strength scales with the warmth pref.
private val WarmthColor = Color(0xFFFF7A1A)
private const val WARMTH_MAX_ALPHA = 0.45f

// The active-selection tint (translucent so the reading theme shows through);
// saved highlights use their own colorId via highlightTint().
private val SelectionColor = Color(0x66FF2A93)

/** A long-press word selection: char range into the chapter's built text. */
private data class WordSelection(val spineIndex: Int, val start: Int, val end: Int, val text: String)

@Composable
fun ReaderScreen(bookId: Long, onBack: () -> Unit) {
    val vm: ReaderViewModel = viewModel(key = "reader-$bookId") {
        val application = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
        ReaderViewModel(application, bookId)
    }
    val uiState by vm.uiState.collectAsState()
    val settings by vm.settings.collectAsState()

    val context = LocalContext.current
    // Read once: 0 means the user asked for reduced motion, so page turns snap.
    val reducedMotion = remember {
        SystemSettings.Global.getFloat(
            context.contentResolver,
            SystemSettings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }

    val leave: () -> Unit = {
        vm.flushAndPush()
        onBack()
    }
    BackHandler(onBack = leave)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> vm.flushAndPush()
                Lifecycle.Event.ON_RESUME -> vm.onResumed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (val state = uiState) {
        ReaderUiState.Loading -> ReadingBlank(settings?.readingTheme)
        is ReaderUiState.Error -> ReaderError(state.message, leave)
        is ReaderUiState.Ready -> {
            val s = settings
            if (s == null) {
                ReadingBlank(null)
            } else {
                ReaderContent(
                    vm = vm,
                    state = state,
                    settings = s,
                    reducedMotion = reducedMotion,
                    onLeave = leave,
                )
            }
        }
    }
}

@Composable
private fun ReaderContent(
    vm: ReaderViewModel,
    state: ReaderUiState.Ready,
    settings: Settings,
    reducedMotion: Boolean,
    onLeave: () -> Unit,
) {
    val theme = settings.readingTheme
    val (background, ink) = when (theme) {
        ReadingTheme.NIGHT -> ReadingNightBackground to ReadingNightText
        ReadingTheme.SEPIA -> ReadingSepiaBackground to ReadingSepiaText
        ReadingTheme.BLACK -> ReadingBlackBackground to ReadingBlackText
        ReadingTheme.PAPER -> ReadingPaperBackground to ReadingPaperText
        ReadingTheme.CONTRAST -> ReadingContrastBackground to ReadingContrastText
    }
    // Light reading surfaces (Sepia, Paper) need dark system-bar icons; the
    // dark ones (Night, Black) keep the app's default light icons, restored on
    // leave (MainActivity forces the app chrome dark).
    val lightTheme = theme == ReadingTheme.SEPIA || theme == ReadingTheme.PAPER ||
        theme == ReadingTheme.CONTRAST
    val view = LocalView.current
    DisposableEffect(lightTheme) {
        val window = (view.context as Activity).window
        val insets = WindowCompat.getInsetsController(window, view)
        insets.isAppearanceLightStatusBars = lightTheme
        insets.isAppearanceLightNavigationBars = lightTheme
        onDispose {
            insets.isAppearanceLightStatusBars = false
            insets.isAppearanceLightNavigationBars = false
        }
    }

    // Hold the screen awake while reading (prefs-gated); released on leave.
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Override screen brightness while reading (prefs-gated); restored to the
    // system value (BRIGHTNESS_OVERRIDE_NONE) on leave.
    DisposableEffect(settings.brightnessOverride, settings.brightness) {
        val window = (view.context as Activity).window
        fun apply(value: Float) {
            window.attributes = window.attributes.apply { screenBrightness = value }
        }
        apply(
            if (settings.brightnessOverride) settings.brightness.coerceIn(0.01f, 1f)
            else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
        )
        onDispose { apply(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) }
    }

    val position by vm.position.collectAsState()
    val percentage by vm.percentage.collectAsState()
    val syncPrompt by vm.syncPrompt.collectAsState()
    val toc by vm.toc.collectAsState()
    val bookmarks by vm.bookmarks.collectAsState()
    val highlights by vm.highlights.collectAsState()
    val canReturn by vm.canReturn.collectAsState()
    val chapterFractions by vm.chapterFractions.collectAsState()
    val footnote by vm.footnote.collectAsState()
    val searchResults by vm.searchResults.collectAsState()
    val searching by vm.searching.collectAsState()
    val pagesPerMin by vm.pagesPerMinute.collectAsState()
    val bookChars by vm.bookChars.collectAsState()

    var chromeVisible by remember { mutableStateOf(false) }
    var turnDirection by remember { mutableIntStateOf(1) }
    var current by remember { mutableStateOf<PageSpec?>(null) }
    var navTab by remember { mutableStateOf<NavTab?>(null) }
    var selection by remember { mutableStateOf<WordSelection?>(null) }
    // Current page's text layout + its origin in root coords, so the root Box's
    // long-press can hit-test a word without a competing gesture on the page.
    var pageLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var textBoxOrigin by remember { mutableStateOf(Offset.Zero) }
    val context = LocalContext.current

    // Snapshot the visible page into a bookmark: its start offset + a short
    // preview snippet taken from the page's first characters.
    val addBookmark: () -> Unit = add@{
        val spec = current ?: return@add
        val page = spec.paginated.pages[spec.pageIndex]
        val text = spec.paginated.text
        val start = page.startChar.coerceIn(0, text.length)
        val end = (start + 60).coerceAtMost(text.length)
        val snippet = text.subSequence(start, end).toString().replace('\n', ' ').trim()
        vm.addBookmark(spec.spineIndex, page.startChar, percentage, snippet)
    }

    fun goNext() {
        val spec = current ?: return
        selection = null
        if (spec.pageIndex < spec.paginated.pages.lastIndex) {
            turnDirection = 1
            vm.moveTo(spec.spineIndex, spec.paginated.pages[spec.pageIndex + 1].startChar)
        } else if (spec.spineIndex + 1 < state.spineCount) {
            turnDirection = 1
            vm.moveTo(spec.spineIndex + 1, 0)
        }
    }

    fun goPrev() {
        val spec = current ?: return
        selection = null
        if (spec.pageIndex > 0) {
            turnDirection = -1
            vm.moveTo(spec.spineIndex, spec.paginated.pages[spec.pageIndex - 1].startChar)
        } else if (spec.spineIndex > 0) {
            // MAX_VALUE clamps to the previous chapter's last page once paginated.
            turnDirection = -1
            vm.moveTo(spec.spineIndex - 1, Int.MAX_VALUE)
        }
    }

    // Volume-key page turns (prefs-gated): register a handler on the host
    // activity while enabled. rememberUpdatedState keeps the registered handler
    // calling the latest goNext/goPrev without re-registering each recomposition.
    val turnAction = rememberUpdatedState<(Boolean) -> Unit> { next -> if (next) goNext() else goPrev() }
    val activity = context as? MainActivity
    DisposableEffect(activity, settings.volumeKeyTurn) {
        if (activity != null && settings.volumeKeyTurn) {
            activity.volumeKeyTurnHandler = { next -> turnAction.value(next) }
        }
        onDispose { activity?.volumeKeyTurnHandler = null }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .pointerInput(Unit) {
                // ONE gesture detector for tap, long-press-to-select, and
                // long-press-then-drag-to-extend. A single detector (rather than two
                // competing ones) is what keeps tap-to-turn from being stolen.

                // Char offset under [pos] on the current text page, or null if off
                // the visible page / on an image page (stale layout would otherwise
                // resolve to an invisible word).
                fun charAt(pos: Offset): Int? {
                    val spec = current ?: return null
                    val l = pageLayout ?: return null
                    val pg = spec.paginated.pages.getOrNull(spec.pageIndex) ?: return null
                    if (pg.imagePath != null) return null
                    val local = pos - textBoxOrigin
                    val c = l.getOffsetForPosition(Offset(local.x, local.y + pg.topPx))
                    return if (c in pg.startChar until pg.endChar) c else null
                }

                // Selects the word under [pos] as the anchor; returns its char range.
                fun beginSelection(pos: Offset): IntRange? {
                    val spec = current ?: return null
                    val l = pageLayout ?: return null
                    val pg = spec.paginated.pages.getOrNull(spec.pageIndex) ?: return null
                    val c = charAt(pos) ?: return null
                    val range = l.getWordBoundary(c)
                    val s = range.start.coerceIn(pg.startChar, pg.endChar)
                    val e = range.end.coerceIn(pg.startChar, pg.endChar)
                    if (e <= s) return null
                    selection = WordSelection(spec.spineIndex, s, e, spec.paginated.text.text.substring(s, e))
                    return s until e
                }

                // Grows the selection to cover the word under [pos], keeping [anchor].
                fun extendSelection(anchor: IntRange, pos: Offset) {
                    val spec = current ?: return
                    val l = pageLayout ?: return
                    val pg = spec.paginated.pages.getOrNull(spec.pageIndex) ?: return
                    val c = charAt(pos) ?: return
                    val range = l.getWordBoundary(c)
                    val s = minOf(anchor.first, range.start).coerceIn(pg.startChar, pg.endChar)
                    val e = maxOf(anchor.last + 1, range.end).coerceIn(pg.startChar, pg.endChar)
                    if (e > s) selection = WordSelection(spec.spineIndex, s, e, spec.paginated.text.text.substring(s, e))
                }

                fun handleTap(pos: Offset) {
                    // A tap while a word is selected just dismisses the selection.
                    if (selection != null) {
                        selection = null
                        return
                    }
                    // Footnote/internal link: a tap on a link span follows it instead
                    // of turning the page.
                    val spec = current
                    val c = charAt(pos)
                    val href = if (spec != null && c != null) {
                        spec.paginated.text.getStringAnnotations(HREF_TAG, c, c).firstOrNull()?.item
                    } else {
                        null
                    }
                    if (href != null && spec != null &&
                        !href.startsWith("http", ignoreCase = true) &&
                        !href.startsWith("mailto:", ignoreCase = true)
                    ) {
                        vm.followLink(spec.spineIndex, href)
                    } else {
                        val third = size.width / 3f
                        when {
                            pos.x < third -> goPrev()
                            pos.x > third * 2 -> goNext()
                            else -> chromeVisible = !chromeVisible
                        }
                    }
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val longPress = awaitLongPressOrCancellation(down.id)
                    if (longPress == null) {
                        // Released before the long-press timeout: treat as a tap.
                        handleTap(down.position)
                    } else {
                        val anchor = beginSelection(down.position)
                        longPress.consume()
                        if (anchor != null) {
                            // Extend the selection as the finger drags, until release.
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                extendSelection(anchor, change.position)
                                change.consume()
                            }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                val threshold = 48.dp.toPx()
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (total <= -threshold) goNext()
                        else if (total >= threshold) goPrev()
                    },
                ) { _, dragAmount -> total += dragAmount }
            },
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = settings.readerMargin.dp.dp, vertical = 32.dp),
        ) {
            val widthPx = constraints.maxWidth
            val heightPx = constraints.maxHeight
            val fontScale = settings.fontScale
            val lineSpacing = settings.lineSpacing
            val fontFamilyName = settings.readingFont.name
            val justify = settings.justify
            // The ViewModel's page cache outlives config changes; the system
            // font scale (and density) must invalidate it or cached page cuts
            // no longer match how the text renders.
            val density = LocalDensity.current
            val sysFontScale = density.fontScale
            val sysDensity = density.density
            val spineIndex = position.spineIndex
            val measurer = rememberTextMeasurer()
            val pageStyle = remember(theme, ink, fontScale, lineSpacing, settings.readingFont) {
                ReadingBodyStyle.copy(
                    color = ink,
                    fontFamily = readingFontFamily(settings.readingFont),
                    fontSize = ReadingBodyStyle.fontSize * fontScale,
                    lineHeight = ReadingBodyStyle.lineHeight * fontScale * lineSpacing,
                    // E-INK reads darker with a heavier stroke on color e-ink.
                    fontWeight = if (theme == ReadingTheme.CONTRAST) FontWeight.Medium
                        else ReadingBodyStyle.fontWeight,
                )
            }

            val result by produceState<PaginationResult?>(
                initialValue = null,
                spineIndex, widthPx, heightPx, fontScale, lineSpacing, fontFamilyName, justify,
                sysFontScale, sysDensity, theme, // theme: CONTRAST's heavier weight re-breaks lines
            ) {
                value = try {
                    withContext(Dispatchers.Default) {
                        PaginationResult(
                            widthPx = widthPx,
                            heightPx = heightPx,
                            fontScale = fontScale,
                            lineSpacing = lineSpacing,
                            fontFamily = fontFamilyName,
                            justify = justify,
                            sysFontScale = sysFontScale,
                            sysDensity = sysDensity,
                            chapter = vm.paginated(
                                spineIndex, widthPx, heightPx, fontScale, lineSpacing,
                                fontFamilyName, justify, sysFontScale, sysDensity, measurer, pageStyle,
                            ),
                        )
                    }
                } catch (e: CancellationException) {
                    throw e // a cancelled stale producer must not clobber a fresh value
                } catch (e: Exception) {
                    null
                }
            }

            // Only fresh pagination replaces the visible page; stale results
            // (mid-repagination) keep the previous spec so layout and style
            // always match.
            val fresh = result
            LaunchedEffect(fresh, position, pageStyle, widthPx, heightPx) {
                if (fresh != null &&
                    fresh.widthPx == widthPx &&
                    fresh.heightPx == heightPx &&
                    fresh.fontScale == fontScale &&
                    fresh.lineSpacing == lineSpacing &&
                    fresh.fontFamily == fontFamilyName &&
                    fresh.justify == justify &&
                    fresh.sysFontScale == sysFontScale &&
                    fresh.sysDensity == sysDensity &&
                    fresh.chapter.spineIndex == position.spineIndex
                ) {
                    current = PageSpec(
                        spineIndex = fresh.chapter.spineIndex,
                        pageIndex = fresh.chapter.pageIndexFor(position.charOffset),
                        paginated = fresh.chapter,
                        style = pageStyle,
                    )
                }
            }

            LaunchedEffect(current) {
                val spec = current ?: return@LaunchedEffect
                vm.onPageShown(
                    spineIndex = spec.spineIndex,
                    page = spec.paginated.pages[spec.pageIndex],
                    chapterCharCount = spec.paginated.text.length,
                )
            }

            AnimatedContent(
                targetState = current,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    if (reducedMotion) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        val dir = turnDirection
                        (slideInHorizontally(tween(220)) { it * dir } + fadeIn(tween(220)))
                            .togetherWith(slideOutHorizontally(tween(220)) { -it * dir } + fadeOut(tween(220)))
                    }
                },
                // Theme/font changes that keep the same visible page must
                // recolor/reflow in place, not replay the page-turn slide.
                contentKey = { spec ->
                    spec?.let { Triple(it.spineIndex, it.pageIndex, it.paginated.pages.size) }
                },
                label = "pageTurn",
            ) { spec ->
                if (spec == null) {
                    Box(Modifier.fillMaxSize())
                } else {
                    val page = spec.paginated.pages[spec.pageIndex]
                    if (page.imagePath != null) {
                        ImagePage(vm = vm, path = page.imagePath, ink = ink)
                    } else {
                        // Clip to the page's OWN content height, not the box: a
                        // page cut early (by a following image) still has later
                        // lines that would fit the box vertically.
                        val contentHeight =
                            with(LocalDensity.current) { page.contentHeightPx.toDp() }
                        // Saved highlights on this chapter (+ the active selection)
                        // render as translucent background spans. Backgrounds never
                        // change layout, so the measured page offsets stay valid.
                        val base = spec.paginated.text
                        val spineHighlights = highlights.filter { it.spineIndex == spec.spineIndex }
                        val activeSel = selection?.takeIf { it.spineIndex == spec.spineIndex }
                        val displayText = remember(base, spineHighlights, activeSel) {
                            if (spineHighlights.isEmpty() && activeSel == null) {
                                base
                            } else {
                                buildAnnotatedString {
                                    append(base)
                                    spineHighlights.forEach {
                                        addStyle(
                                            SpanStyle(background = highlightTint(it.colorId)),
                                            it.startChar.coerceIn(0, base.length),
                                            it.endChar.coerceIn(0, base.length),
                                        )
                                    }
                                    activeSel?.let {
                                        addStyle(
                                            SpanStyle(background = SelectionColor),
                                            it.start.coerceIn(0, base.length),
                                            it.end.coerceIn(0, base.length),
                                        )
                                    }
                                }
                            }
                        }
                        Box(Modifier.fillMaxSize()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(contentHeight)
                                    .align(Alignment.TopStart)
                                    // Record the text box origin in root coords so the
                                    // root Box's long-press can map a screen tap to a char.
                                    .onGloballyPositioned { textBoxOrigin = it.positionInRoot() }
                                    .clipToBounds(),
                            ) {
                                // Same text, same style, same width as the measure
                                // pass; unbounded height so it reflows identically,
                                // shifted up to this page's first line.
                                Text(
                                    text = displayText,
                                    style = spec.style,
                                    onTextLayout = { pageLayout = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .wrapContentHeight(align = Alignment.Top, unbounded = true)
                                        .offset { IntOffset(0, -page.topPx.roundToInt()) },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Warm-light wash over the page (no pointer input, so it never blocks
        // page-turn gestures); drawn under the chrome so chrome stays untinted.
        if (settings.warmth > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(WarmthColor.copy(alpha = settings.warmth * WARMTH_MAX_ALPHA)),
            )
        }

        if (chromeVisible) {
            val spec = current
            val timeLeftLabel = if (spec != null && pagesPerMin > 0f && spec.paginated.pages.isNotEmpty()) {
                val chapterPages = spec.paginated.pages.size
                val pagesLeftChapter = (chapterPages - (spec.pageIndex + 1)).coerceAtLeast(0)
                val chapterPart = "~${fmtDuration(pagesLeftChapter / pagesPerMin)} LEFT IN CHAPTER"
                val chapterChars = spec.paginated.text.length
                val charsPerPage = chapterChars.toFloat() / chapterPages
                // Only extrapolate a book estimate from a text-representative chapter;
                // an image/cover chapter is ~1 char over 1 page, which would blow the
                // pages-per-page density up and yield a nonsense book time.
                if (bookChars > 0 && charsPerPage >= MIN_CHARS_PER_PAGE) {
                    val estTotalPages = bookChars / charsPerPage
                    val pagesLeftBook = (estTotalPages * (1f - percentage)).coerceAtLeast(0f)
                    "$chapterPart · ~${fmtDuration(pagesLeftBook / pagesPerMin)} LEFT IN BOOK"
                } else {
                    chapterPart
                }
            } else {
                null
            }
            ReaderChrome(
                title = state.title,
                chapterLabel = "CH ${position.spineIndex + 1}/${state.spineCount}",
                percentage = percentage,
                timeLeftLabel = timeLeftLabel,
                theme = theme,
                fontScale = settings.fontScale,
                onBack = onLeave,
                onCycleTheme = { vm.setTheme(nextReadingTheme(theme)) },
                onFontDown = { vm.stepFontScale(-0.125f) },
                onFontUp = { vm.stepFontScale(0.125f) },
                onSeek = { fraction -> vm.seekToFraction(fraction) },
                chapterMarks = chapterFractions,
                bookmarkMarks = bookmarks.map { it.percentage },
                highlightMarks = highlights.map { chapterFractions.getOrElse(it.spineIndex) { 0f } },
                onOpenToc = { navTab = NavTab.CHAPTERS },
                onOpenBookmarks = { navTab = NavTab.BOOKMARKS },
                onOpenSearch = { navTab = NavTab.SEARCH },
            )
        }

        // After an exploratory jump (TOC/search/bookmark/scrubber), a small pill
        // offers to hop back to where you were. Hidden while other chrome is up.
        if (canReturn && !chromeVisible && navTab == null && selection == null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp)
                    .clip(CipherShapeSmall)
                    .background(CipherVoid.copy(alpha = 0.9f))
                    .border(1.dp, CipherCyan.copy(alpha = 0.6f), CipherShapeSmall)
                    .clickable { vm.returnBack() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("↩ RETURN", style = MaterialTheme.typography.labelSmall, color = CipherCyan)
            }
        }

        navTab?.let { tab ->
            ReaderNavOverlay(
                tab = tab,
                toc = toc,
                bookmarks = bookmarks,
                currentSpine = position.spineIndex,
                onSwitchTab = { navTab = it },
                onSelectChapter = { spine ->
                    vm.jumpTo(spine, 0)
                    navTab = null
                    chromeVisible = false
                },
                onSelectBookmark = { spine, offset ->
                    vm.jumpTo(spine, offset)
                    navTab = null
                    chromeVisible = false
                },
                onAddBookmark = addBookmark,
                onDeleteBookmark = { vm.deleteBookmark(it) },
                highlights = highlights,
                onSelectHighlight = { spine, startChar ->
                    vm.jumpTo(spine, startChar)
                    navTab = null
                    chromeVisible = false
                },
                onDeleteHighlight = { vm.deleteHighlight(it) },
                searchResults = searchResults,
                searching = searching,
                onSearch = { vm.search(it) },
                onClearSearch = { vm.clearSearch() },
                onSelectSearchHit = { spine, offset ->
                    vm.jumpTo(spine, offset)
                    navTab = null
                    chromeVisible = false
                },
                onDismiss = { navTab = null },
            )
        }

        selection?.let { sel ->
            SelectionToolbar(
                onDefine = { defineText(context, sel.text); selection = null },
                onHighlight = { colorId ->
                    vm.addHighlight(sel.spineIndex, sel.start, sel.end, sel.text, colorId)
                    selection = null
                },
                onCopy = { copyText(context, sel.text); selection = null },
                onShare = { shareText(context, sel.text); selection = null },
                onDismiss = { selection = null },
            )
        }

        footnote?.let { note ->
            FootnotePanel(text = note, onDismiss = { vm.dismissFootnote() })
        }

        syncPrompt?.let { prompt ->
            SyncPromptPanel(
                prompt = prompt,
                onJump = { vm.jumpToRemote() },
                onStay = { vm.dismissSyncPrompt() },
            )
        }
    }
}

@Composable
private fun BoxScope.SelectionToolbar(
    onDefine: () -> Unit,
    onHighlight: (Int) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    CipherPanel(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CipherButton("DEFINE", onClick = onDefine)
            // Highlight in one of the palette colors (tap a swatch).
            HighlightPalette.forEachIndexed { colorId, tint ->
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CipherShapeSmall)
                        .background(tint.copy(alpha = 1f))
                        .border(1.dp, CipherMuted.copy(alpha = 0.4f), CipherShapeSmall)
                        .clickable { onHighlight(colorId) },
                )
            }
            CipherButton("COPY", onClick = onCopy)
            CipherButton("SHARE", onClick = onShare)
            CipherCaption(
                "CLOSE",
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(8.dp),
            )
        }
    }
}

private fun defineText(context: Context, text: String) {
    val process = Intent(Intent.ACTION_PROCESS_TEXT).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    }
    if (process.resolveActivity(context.packageManager) != null) {
        context.startActivity(Intent.createChooser(process, "DEFINE"))
    } else {
        // No dictionary/translate app: fall back to a web "define" search.
        val query = Uri.encode("define $text")
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$query"))
        try {
            context.startActivity(web)
        } catch (e: android.content.ActivityNotFoundException) {
            // No browser either — nothing to do.
        }
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("CipherCodex", text))
}

// A chapter thinner than this (chars per page) is treated as non-text (image /
// cover) and excluded from the whole-book time extrapolation.
private const val MIN_CHARS_PER_PAGE = 200f

/** Compact reading-time label: "<1 MIN", "9 MIN", or "2H 40M". */
private fun fmtDuration(minutes: Float): String {
    val m = minutes.roundToInt()
    return when {
        m < 1 -> "<1 MIN"
        m < 60 -> "$m MIN"
        else -> {
            val h = m / 60
            val rem = m % 60
            if (rem == 0) "${h}H" else "${h}H ${rem}M"
        }
    }
}

private fun shareText(context: Context, text: String) {
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            },
            "SHARE",
        ),
    )
}

@Composable
private fun BoxScope.ReaderChrome(
    title: String,
    chapterLabel: String,
    percentage: Float,
    timeLeftLabel: String?,
    theme: ReadingTheme,
    fontScale: Float,
    onBack: () -> Unit,
    onCycleTheme: () -> Unit,
    onFontDown: () -> Unit,
    onFontUp: () -> Unit,
    onSeek: (Float) -> Unit,
    chapterMarks: List<Float>,
    bookmarkMarks: List<Float>,
    highlightMarks: List<Float>,
    onOpenToc: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    Row(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .background(CipherVoid.copy(alpha = 0.92f))
            .pointerInput(Unit) { detectTapGestures { } }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = CipherPhosphor,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = CipherPhosphor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        CipherCaption(chapterLabel, modifier = Modifier.padding(end = 8.dp))
    }

    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(CipherVoid.copy(alpha = 0.92f))
            .pointerInput(Unit) { detectTapGestures { } }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (timeLeftLabel != null) {
            CipherCaption(timeLeftLabel)
            Spacer(Modifier.height(10.dp))
        }
        ReaderScrubber(
            percentage = percentage,
            onSeek = onSeek,
            chapters = chapterMarks,
            bookmarks = bookmarkMarks,
            highlights = highlightMarks,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CipherButton(text = theme.name, onClick = onCycleTheme)
            Spacer(Modifier.weight(1f))
            CipherButton("A-", onClick = onFontDown, enabled = fontScale > 0.75f)
            CipherButton("A+", onClick = onFontUp, enabled = fontScale < 1.75f)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CipherButton("TOC", onClick = onOpenToc)
            CipherButton("MARKS", onClick = onOpenBookmarks)
            CipherButton("FIND", onClick = onOpenSearch)
        }
    }
}

/** A tapped footnote/internal-link target shown in a dismissible bottom panel,
 *  so the reader can peek a note without losing their page. */
@Composable
private fun BoxScope.FootnotePanel(text: String, onDismiss: () -> Unit) {
    CipherPanel(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp)
            // Swallow taps so tapping the note doesn't fall through to a page turn.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CipherCaption("NOTE", Modifier.weight(1f))
                CipherCaption(
                    "CLOSE",
                    modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = CipherPhosphor,
                modifier = Modifier
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

/** Interactive whole-book progress: tap or drag to seek. While dragging it
 *  previews the target percent; the seek commits on release. */
@Composable
private fun ReaderScrubber(
    percentage: Float,
    onSeek: (Float) -> Unit,
    chapters: List<Float>,
    bookmarks: List<Float>,
    highlights: List<Float>,
    modifier: Modifier = Modifier,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shown = dragFraction ?: percentage
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(24.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onSeek((offset.x / size.width).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            dragFraction?.let { onSeek(it) }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    ) { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            CipherProgressBar(shown)
            // Book Map: chapter boundaries as faint ticks, bookmarks (cyan) and
            // highlights (magenta) as marks, and a bright current-position line.
            Canvas(Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                chapters.forEach { f ->
                    val x = f.coerceIn(0f, 1f) * w
                    drawLine(CipherMuted.copy(alpha = 0.5f), Offset(x, h * 0.15f), Offset(x, h * 0.85f), 1.dp.toPx())
                }
                bookmarks.forEach { f ->
                    drawCircle(CipherCyan, 2.5.dp.toPx(), Offset(f.coerceIn(0f, 1f) * w, h * 0.25f))
                }
                highlights.forEach { f ->
                    drawCircle(CipherMagenta, 2.5.dp.toPx(), Offset(f.coerceIn(0f, 1f) * w, h * 0.75f))
                }
                val px = shown.coerceIn(0f, 1f) * w
                drawLine(CipherCyan, Offset(px, 0f), Offset(px, h), 2.dp.toPx())
            }
        }
        Spacer(Modifier.width(8.dp))
        CipherCaption("${(shown * 100).roundToInt()}%")
    }
}

/** Bottom-anchored in-book navigation: a Chapters/Bookmarks switcher over a
 *  scrim. Chrome aesthetic (never the reading surface). */
@Composable
private fun BoxScope.ReaderNavOverlay(
    tab: NavTab,
    toc: List<EpubTocEntry>,
    bookmarks: List<BookmarkEntity>,
    currentSpine: Int,
    onSwitchTab: (NavTab) -> Unit,
    onSelectChapter: (Int) -> Unit,
    onSelectBookmark: (Int, Int) -> Unit,
    onAddBookmark: () -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    highlights: List<HighlightEntity>,
    onSelectHighlight: (Int, Int) -> Unit,
    onDeleteHighlight: (Long) -> Unit,
    searchResults: List<ReaderViewModel.SearchHit>,
    searching: Boolean,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSelectSearchHit: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .matchParentSize()
            .background(CipherVoid.copy(alpha = 0.75f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .pointerInput(Unit) {
                // Consume drags so a swipe over the scrim can't reach the reader's
                // page-turn detector behind this modal (which would silently turn
                // the page and cancel the dismiss tap).
                detectHorizontalDragGestures { change, _ -> change.consume() }
            },
    )
    CipherPanel(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp)
            // Swallow taps so tapping the panel doesn't fall through to the scrim.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NavTabLabel("CHAPTERS", tab == NavTab.CHAPTERS) { onSwitchTab(NavTab.CHAPTERS) }
                    Spacer(Modifier.width(16.dp))
                    NavTabLabel("BOOKMARKS", tab == NavTab.BOOKMARKS) { onSwitchTab(NavTab.BOOKMARKS) }
                    Spacer(Modifier.width(16.dp))
                    NavTabLabel("HIGHLIGHTS", tab == NavTab.HIGHLIGHTS) { onSwitchTab(NavTab.HIGHLIGHTS) }
                    Spacer(Modifier.width(16.dp))
                    NavTabLabel("FIND", tab == NavTab.SEARCH) { onSwitchTab(NavTab.SEARCH) }
                }
                CipherCaption(
                    "CLOSE",
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            when (tab) {
                NavTab.CHAPTERS -> {
                    if (toc.isEmpty()) {
                        CipherCaption("NO TABLE OF CONTENTS", modifier = Modifier.padding(vertical = 12.dp))
                    } else {
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(toc) { entry ->
                                NavRow(onClick = { onSelectChapter(entry.spineIndex) }) {
                                    Text(
                                        text = entry.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (entry.spineIndex == currentSpine) CipherCyan else CipherPhosphor,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    CipherCaption("CH ${entry.spineIndex + 1}")
                                }
                            }
                        }
                    }
                }

                NavTab.BOOKMARKS -> {
                    CipherButton("+ BOOKMARK THIS PAGE", onClick = onAddBookmark)
                    Spacer(Modifier.height(8.dp))
                    if (bookmarks.isEmpty()) {
                        CipherCaption("NO BOOKMARKS YET", modifier = Modifier.padding(vertical = 12.dp))
                    } else {
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(bookmarks, key = { it.id }) { bm ->
                                NavRow(onClick = { onSelectBookmark(bm.spineIndex, bm.charOffset) }) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = bm.label.ifBlank { "CH ${bm.spineIndex + 1}" },
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = CipherPhosphor,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        CipherCaption("${(bm.percentage * 100).roundToInt()}%")
                                    }
                                    CipherCaption(
                                        "DELETE",
                                        color = CipherMagenta,
                                        modifier = Modifier
                                            .clickable { onDeleteBookmark(bm.id) }
                                            .padding(8.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                NavTab.HIGHLIGHTS -> {
                    if (highlights.isEmpty()) {
                        CipherCaption(
                            "NO HIGHLIGHTS YET — LONG-PRESS A WORD",
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    } else {
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(highlights, key = { it.id }) { hl ->
                                NavRow(onClick = { onSelectHighlight(hl.spineIndex, hl.startChar) }) {
                                    Text(
                                        text = hl.text,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = CipherPhosphor,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    CipherCaption(
                                        "DELETE",
                                        color = CipherMagenta,
                                        modifier = Modifier
                                            .clickable { onDeleteHighlight(hl.id) }
                                            .padding(8.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                NavTab.SEARCH -> {
                    var query by remember { mutableStateOf("") }
                    // Debounce: search 300ms after the last keystroke; a blank
                    // query clears results immediately.
                    LaunchedEffect(query) {
                        if (query.isBlank()) {
                            onClearSearch()
                        } else {
                            delay(300)
                            onSearch(query)
                        }
                    }
                    CipherTextField(value = query, onValueChange = { query = it }, label = "FIND IN BOOK")
                    Spacer(Modifier.height(8.dp))
                    when {
                        searching -> CipherCaption("SEARCHING…", modifier = Modifier.padding(vertical = 12.dp))
                        query.trim().length >= 2 && searchResults.isEmpty() ->
                            CipherCaption("NO MATCHES", modifier = Modifier.padding(vertical = 12.dp))
                        searchResults.isNotEmpty() -> {
                            CipherCaption("${searchResults.size} MATCH${if (searchResults.size == 1) "" else "ES"}")
                            Spacer(Modifier.height(4.dp))
                            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                                items(searchResults, key = { "${it.spineIndex}:${it.charOffset}" }) { hit ->
                                    NavRow(onClick = { onSelectSearchHit(hit.spineIndex, hit.charOffset) }) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                text = hit.snippet,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = CipherPhosphor,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            CipherCaption(hit.chapterLabel)
                                        }
                                    }
                                }
                            }
                        }
                        else -> CipherCaption(
                            "TYPE TO SEARCH THE WHOLE BOOK",
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavTabLabel(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (active) CipherCyan else CipherMuted,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    )
}

@Composable
private fun NavRow(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun BoxScope.SyncPromptPanel(
    prompt: PullResult.RemoteNewer,
    onJump: () -> Unit,
    onStay: () -> Unit,
) {
    CipherPanel(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(16.dp),
        onClick = {},
    ) {
        Column(Modifier.padding(16.dp)) {
            CipherCaption(
                text = "SYNC // ${prompt.fromDevice ?: "REMOTE"} @ ${(prompt.percentage * 100).roundToInt()}%",
                color = CipherPhosphor,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CipherButton("JUMP", onClick = onJump)
                CipherButton("STAY", onClick = onStay, accent = CipherMuted)
            }
        }
    }
}

private sealed interface ImageLoad {
    data object Loading : ImageLoad
    data class Done(val bitmap: ImageBitmap) : ImageLoad
    data object Failed : ImageLoad
}

/** Full-page in-flow image (cover pages, illustrations), decoded off the main
 *  thread with subsampling near the page size, fit-centered. */
@Composable
private fun ImagePage(vm: ReaderViewModel, path: String, ink: Color) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val targetW = constraints.maxWidth.coerceAtLeast(1)
        val targetH = constraints.maxHeight.coerceAtLeast(1)
        val load by produceState<ImageLoad>(ImageLoad.Loading, path) {
            value = withContext(Dispatchers.IO) {
                val bytes = vm.imageBytes(path) ?: return@withContext ImageLoad.Failed
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext ImageLoad.Failed
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= targetW &&
                        bounds.outHeight / (sample * 2) >= targetH
                    ) {
                        sample *= 2
                    }
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        ?.let { ImageLoad.Done(it.asImageBitmap()) }
                        ?: ImageLoad.Failed
                } catch (e: Exception) {
                    ImageLoad.Failed
                } catch (e: OutOfMemoryError) {
                    ImageLoad.Failed
                }
            }
        }
        when (val state = load) {
            ImageLoad.Loading -> Unit
            is ImageLoad.Done -> Image(
                bitmap = state.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            ImageLoad.Failed -> Text(
                text = "[ image unavailable ]",
                style = ReadingBodyStyle.copy(fontSize = 14.sp, color = ink.copy(alpha = 0.6f)),
            )
        }
    }
}

@Composable
private fun ReadingBlank(theme: ReadingTheme?) {
    val background = when (theme) {
        ReadingTheme.SEPIA -> ReadingSepiaBackground
        ReadingTheme.BLACK -> ReadingBlackBackground
        ReadingTheme.PAPER -> ReadingPaperBackground
        ReadingTheme.CONTRAST -> ReadingContrastBackground
        else -> ReadingNightBackground // NIGHT or not-yet-loaded
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(background),
    )
}

@Composable
private fun ReaderError(message: String, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CipherVoid)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CipherPanel {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CipherCaption("READ // ERROR", color = CipherMagenta)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = CipherPhosphor,
                )
                Spacer(Modifier.height(16.dp))
                CipherButton("BACK", onClick = onBack)
            }
        }
    }
}
