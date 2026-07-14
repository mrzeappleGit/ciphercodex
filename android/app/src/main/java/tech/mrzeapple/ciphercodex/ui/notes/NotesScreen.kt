package tech.mrzeapple.ciphercodex.ui.notes

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.mrzeapple.ciphercodex.ui.components.CipherCaption
import tech.mrzeapple.ciphercodex.ui.components.CipherHeader
import tech.mrzeapple.ciphercodex.ui.components.CipherPanel
import tech.mrzeapple.ciphercodex.ui.components.CipherTextField
import tech.mrzeapple.ciphercodex.ui.theme.LocalCipherColors

/** Decode a PNG off the main thread, downsampled to roughly maxDim on the long side.
 *  Keyed on [stamp] too: sync re-renders a changed page to the SAME file path, so
 *  the path alone would never invalidate an on-screen bitmap. */
@Composable
private fun rememberPageBitmap(path: String, stamp: Long, maxDim: Int) = produceState<android.graphics.Bitmap?>(null, path, stamp) {
    value = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** NOTES: rendered reMarkable 2 notebooks synced in — a grid of covers that
 *  opens into a full-screen, pinch-zoomable page pager. */
@Composable
fun NotesScreen(vm: NotesViewModel = viewModel()) {
    val c = LocalCipherColors.current
    val scope = rememberCoroutineScope()
    val cards by vm.notebooks.collectAsState()
    val pageTexts by vm.pageTexts.collectAsState()
    val hasNotebooks by vm.hasNotebooks.collectAsState()
    val query by vm.query.collectAsState()
    var open by remember { mutableStateOf<NotebookCard?>(null) }
    var openInitialPage by remember { mutableStateOf(0) }
    var editingPageGuid by remember { mutableStateOf<String?>(null) }
    var showNewDialog by remember { mutableStateOf(false) }

    val editingGuid = editingPageGuid
    if (editingGuid != null) {
        // Always resolve from the live `cards` flow, never from `open` — `open` is a
        // point-in-time snapshot and goes stale the moment +PAGE adds a page to it
        // mid-session, which previously hid the next-page arrow after the count grew.
        val card = cards.firstOrNull { c2 -> c2.pages.any { it.guid == editingGuid } }
        if (card != null) {
            val idx = card.pages.indexOfFirst { it.guid == editingGuid }
            InkEditorScreen(
                pageGuid = editingGuid,
                title = card.notebook.title,
                onPrevPage = if (idx > 0) { { editingPageGuid = card.pages[idx - 1].guid } } else null,
                onNextPage = if (idx in 0 until card.pages.size - 1) {
                    { editingPageGuid = card.pages[idx + 1].guid }
                } else null,
                onAddPage = { scope.launch { editingPageGuid = vm.newPage(card.notebook.guid) } },
                onClose = {
                    open = card
                    openInitialPage = card.pages.indexOfFirst { it.guid == editingPageGuid }.coerceAtLeast(0)
                    editingPageGuid = null
                },
            )
        }
        // ponytail: card not found yet (brand-new notebook's Room Flow hasn't emitted):
        // render nothing for one frame rather than crash — `cards` is reactive and the
        // next emission (near-instant) resolves this branch.
        return
    }

    val current = open
    if (current != null) {
        PageViewer(
            card = cards.firstOrNull { it.notebook.guid == current.notebook.guid } ?: current,
            pageTexts = pageTexts,
            initialPage = openInitialPage,
            onEdit = { guid -> editingPageGuid = guid },
            onClose = { open = null },
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            CipherHeader(title = "NOTES")
            Spacer(Modifier.height(12.dp))

            if (!hasNotebooks) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CipherCaption("SYNC FROM YOUR REMARKABLE 2 — OR START WRITING")
                        Spacer(Modifier.height(12.dp))
                        CipherCaption(
                            text = "+ NEW",
                            color = c.cyan,
                            modifier = Modifier.clickable { showNewDialog = true },
                        )
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CipherTextField(
                        value = query,
                        onValueChange = { vm.query.value = it },
                        label = "SEARCH",
                        modifier = Modifier.weight(1f),
                    )
                    CipherCaption(
                        text = "+ NEW",
                        color = c.cyan,
                        modifier = Modifier
                            .clickable { showNewDialog = true }
                            .padding(start = 12.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (cards.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CipherCaption("NO MATCHES")
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        items(cards, key = { it.notebook.guid }) { card ->
                            NotebookCardView(card, onOpen = { open = card; openInitialPage = 0 })
                        }
                    }
                }
            }
        }

        if (showNewDialog) {
            var title by remember { mutableStateOf("") }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                CipherPanel(modifier = Modifier.fillMaxWidth(0.85f)) {
                    Column(Modifier.padding(16.dp)) {
                        CipherTextField(value = title, onValueChange = { title = it }, label = "TITLE")
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            CipherCaption(
                                text = "CANCEL",
                                modifier = Modifier.clickable { showNewDialog = false },
                            )
                            Spacer(Modifier.width(16.dp))
                            CipherCaption(
                                text = "CREATE",
                                color = c.cyan,
                                modifier = Modifier.clickable {
                                    scope.launch { editingPageGuid = vm.newNotebook(title) }
                                    showNewDialog = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotebookCardView(card: NotebookCard, onOpen: () -> Unit) {
    val c = LocalCipherColors.current
    CipherPanel(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1404f / 1872f)
                    .background(c.void),
            ) {
                val cover = card.coverPage
                if (cover != null) {
                    val bmp by rememberPageBitmap(cover.imagePath, cover.contentStamp, maxDim = 480)
                    bmp?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = card.notebook.title,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Column(Modifier.padding(8.dp)) {
                Text(
                    text = card.notebook.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                CipherCaption("${card.pages.size} PAGES", Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun PageViewer(
    card: NotebookCard,
    pageTexts: Map<String, String>,
    initialPage: Int,
    onEdit: (String) -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalCipherColors.current
    BackHandler(onBack = onClose)
    val pager = rememberPagerState(initialPage = initialPage.coerceIn(0, (card.pages.size - 1).coerceAtLeast(0))) { card.pages.size }
    var showText by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CipherCaption(
                text = "${card.notebook.title.uppercase()} · PAGE ${pager.currentPage + 1}/${card.pages.size}",
                color = c.cyan,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onClose),
            )
            CipherCaption(
                text = "TEXT",
                color = if (showText) c.cyan else c.muted,
                modifier = Modifier
                    .clickable { showText = !showText }
                    .padding(start = 12.dp),
            )
            CipherCaption(
                text = "EDIT",
                color = c.cyan,
                modifier = Modifier
                    .clickable { card.pages.getOrNull(pager.currentPage)?.let { onEdit(it.guid) } }
                    .padding(start = 12.dp),
            )
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { index ->
            val page = card.pages[index]
            var scale by remember { mutableStateOf(1f) }
            var offX by remember { mutableStateOf(0f) }
            var offY by remember { mutableStateOf(0f) }
            Box(
                Modifier
                    .fillMaxSize()
                    // Consume only pinches and zoomed pans; unzoomed single-finger
                    // swipes must pass through untouched so the pager can page.
                    .pointerInput(page.guid) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.size > 1 || scale > 1f) {
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    scale = (scale * zoom).coerceIn(1f, 6f)
                                    if (scale > 1f) {
                                        offX += pan.x; offY += pan.y
                                    } else {
                                        offX = 0f; offY = 0f
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (page.imagePath.isNotEmpty()) {
                    val bmp by rememberPageBitmap(page.imagePath, page.contentStamp, maxDim = 1872)
                    bmp?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offX,
                                translationY = offY,
                            ),
                        )
                    }
                }
            }
        }
        if (showText) {
            val guid = card.pages.getOrNull(pager.currentPage)?.guid
            CipherPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.4f)
                    .padding(16.dp),
            ) {
                Text(
                    text = pageTexts[guid]?.takeIf { it.isNotBlank() }
                        ?: "No text recognized yet — sync with recognition enabled.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
    }
}
