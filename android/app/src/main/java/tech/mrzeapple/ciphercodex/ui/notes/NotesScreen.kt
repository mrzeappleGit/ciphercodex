package tech.mrzeapple.ciphercodex.ui.notes

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.mrzeapple.ciphercodex.ui.components.CipherCaption
import tech.mrzeapple.ciphercodex.ui.components.CipherHeader
import tech.mrzeapple.ciphercodex.ui.components.CipherPanel
import tech.mrzeapple.ciphercodex.ui.theme.LocalCipherColors

/** Decode a PNG off the main thread, downsampled to roughly maxDim on the long side. */
@Composable
private fun rememberPageBitmap(path: String, maxDim: Int) = produceState<android.graphics.Bitmap?>(null, path) {
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
    val cards by vm.notebooks.collectAsState()
    var open by remember { mutableStateOf<NotebookCard?>(null) }

    val current = open
    if (current != null) {
        PageViewer(
            card = cards.firstOrNull { it.notebook.guid == current.notebook.guid } ?: current,
            onClose = { open = null },
        )
        return
    }

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

        if (cards.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CipherCaption("NOTES SYNC FROM YOUR REMARKABLE 2")
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
                    NotebookCardView(card, onOpen = { open = card })
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
                val cover = card.coverPath
                if (cover != null) {
                    val bmp by rememberPageBitmap(cover, maxDim = 480)
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
private fun PageViewer(card: NotebookCard, onClose: () -> Unit) {
    val c = LocalCipherColors.current
    BackHandler(onBack = onClose)
    val pager = rememberPagerState { card.pages.size }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        CipherCaption(
            text = "${card.notebook.title.uppercase()} · PAGE ${pager.currentPage + 1}/${card.pages.size}",
            color = c.cyan,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClose)
                .padding(16.dp),
        )
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { index ->
            val page = card.pages[index]
            var scale by remember { mutableStateOf(1f) }
            var offX by remember { mutableStateOf(0f) }
            var offY by remember { mutableStateOf(0f) }
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(page.guid) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offX += pan.x; offY += pan.y
                            if (scale == 1f) { offX = 0f; offY = 0f }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (page.imagePath.isNotEmpty()) {
                    val bmp by rememberPageBitmap(page.imagePath, maxDim = 1872)
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
    }
}
