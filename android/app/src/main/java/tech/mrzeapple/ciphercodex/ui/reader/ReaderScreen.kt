package tech.mrzeapple.ciphercodex.ui.reader

import android.app.Activity
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
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
import tech.mrzeapple.ciphercodex.data.prefs.ReadingTheme
import tech.mrzeapple.ciphercodex.data.prefs.Settings
import tech.mrzeapple.ciphercodex.sync.PullResult
import tech.mrzeapple.ciphercodex.ui.components.CipherButton
import tech.mrzeapple.ciphercodex.ui.components.CipherCaption
import tech.mrzeapple.ciphercodex.ui.components.CipherPanel
import tech.mrzeapple.ciphercodex.ui.components.CipherProgressBar
import tech.mrzeapple.ciphercodex.ui.theme.CipherMagenta
import tech.mrzeapple.ciphercodex.ui.theme.CipherMuted
import tech.mrzeapple.ciphercodex.ui.theme.CipherPhosphor
import tech.mrzeapple.ciphercodex.ui.theme.CipherVoid
import tech.mrzeapple.ciphercodex.ui.theme.ReadingBodyStyle
import tech.mrzeapple.ciphercodex.ui.theme.ReadingNightBackground
import tech.mrzeapple.ciphercodex.ui.theme.ReadingNightText
import tech.mrzeapple.ciphercodex.ui.theme.ReadingSepiaBackground
import tech.mrzeapple.ciphercodex.ui.theme.ReadingSepiaText
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
    val sysFontScale: Float,
    val sysDensity: Float,
    val chapter: PaginatedChapter,
)

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
    val night = settings.readingTheme == ReadingTheme.NIGHT
    val background = if (night) ReadingNightBackground else ReadingSepiaBackground
    val ink = if (night) ReadingNightText else ReadingSepiaText

    // Sepia's light page needs dark system-bar icons; everywhere else the app
    // is forced-dark with light icons (set in MainActivity), so restore that
    // default when leaving the reader.
    val view = LocalView.current
    DisposableEffect(night) {
        val window = (view.context as Activity).window
        val insets = WindowCompat.getInsetsController(window, view)
        insets.isAppearanceLightStatusBars = !night
        insets.isAppearanceLightNavigationBars = !night
        onDispose {
            insets.isAppearanceLightStatusBars = false
            insets.isAppearanceLightNavigationBars = false
        }
    }

    val position by vm.position.collectAsState()
    val percentage by vm.percentage.collectAsState()
    val syncPrompt by vm.syncPrompt.collectAsState()

    var chromeVisible by remember { mutableStateOf(false) }
    var turnDirection by remember { mutableIntStateOf(1) }
    var current by remember { mutableStateOf<PageSpec?>(null) }

    fun goNext() {
        val spec = current ?: return
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
        if (spec.pageIndex > 0) {
            turnDirection = -1
            vm.moveTo(spec.spineIndex, spec.paginated.pages[spec.pageIndex - 1].startChar)
        } else if (spec.spineIndex > 0) {
            // MAX_VALUE clamps to the previous chapter's last page once paginated.
            turnDirection = -1
            vm.moveTo(spec.spineIndex - 1, Int.MAX_VALUE)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val third = size.width / 3f
                    when {
                        offset.x < third -> goPrev()
                        offset.x > third * 2 -> goNext()
                        else -> chromeVisible = !chromeVisible
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
                .padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            val widthPx = constraints.maxWidth
            val heightPx = constraints.maxHeight
            val fontScale = settings.fontScale
            // The ViewModel's page cache outlives config changes; the system
            // font scale (and density) must invalidate it or cached page cuts
            // no longer match how the text renders.
            val density = LocalDensity.current
            val sysFontScale = density.fontScale
            val sysDensity = density.density
            val spineIndex = position.spineIndex
            val measurer = rememberTextMeasurer()
            val pageStyle = remember(ink, fontScale) {
                ReadingBodyStyle.copy(
                    color = ink,
                    fontSize = ReadingBodyStyle.fontSize * fontScale,
                    lineHeight = ReadingBodyStyle.lineHeight * fontScale,
                )
            }

            val result by produceState<PaginationResult?>(
                initialValue = null,
                spineIndex, widthPx, heightPx, fontScale, sysFontScale, sysDensity,
            ) {
                value = try {
                    withContext(Dispatchers.Default) {
                        PaginationResult(
                            widthPx = widthPx,
                            heightPx = heightPx,
                            fontScale = fontScale,
                            sysFontScale = sysFontScale,
                            sysDensity = sysDensity,
                            chapter = vm.paginated(
                                spineIndex, widthPx, heightPx, fontScale,
                                sysFontScale, sysDensity, measurer, pageStyle,
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
                        Box(Modifier.fillMaxSize()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(contentHeight)
                                    .align(Alignment.TopStart)
                                    .clipToBounds(),
                            ) {
                                // Same text, same style, same width as the measure
                                // pass; unbounded height so it reflows identically,
                                // shifted up to this page's first line.
                                Text(
                                    text = spec.paginated.text,
                                    style = spec.style,
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

        if (chromeVisible) {
            ReaderChrome(
                title = state.title,
                chapterLabel = "CH ${position.spineIndex + 1}/${state.spineCount}",
                percentage = percentage,
                night = night,
                fontScale = settings.fontScale,
                onBack = onLeave,
                onToggleTheme = {
                    vm.setTheme(if (night) ReadingTheme.SEPIA else ReadingTheme.NIGHT)
                },
                onFontDown = { vm.stepFontScale(-0.125f) },
                onFontUp = { vm.stepFontScale(0.125f) },
            )
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
private fun BoxScope.ReaderChrome(
    title: String,
    chapterLabel: String,
    percentage: Float,
    night: Boolean,
    fontScale: Float,
    onBack: () -> Unit,
    onToggleTheme: () -> Unit,
    onFontDown: () -> Unit,
    onFontUp: () -> Unit,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            CipherProgressBar(percentage, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            CipherCaption("${(percentage * 100).roundToInt()}%")
        }
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CipherButton(
                text = if (night) "SEPIA" else "NIGHT",
                onClick = onToggleTheme,
            )
            Spacer(Modifier.weight(1f))
            CipherButton("A-", onClick = onFontDown, enabled = fontScale > 0.75f)
            CipherButton("A+", onClick = onFontUp, enabled = fontScale < 1.75f)
        }
    }
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
    val background =
        if (theme == ReadingTheme.SEPIA) ReadingSepiaBackground else ReadingNightBackground
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
