package tech.mrzeapple.ciphercodex.ui.detail

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.mrzeapple.ciphercodex.ui.components.CipherButton
import tech.mrzeapple.ciphercodex.ui.components.CipherCaption
import tech.mrzeapple.ciphercodex.ui.components.CipherPanel
import tech.mrzeapple.ciphercodex.ui.components.CipherProgressBar
import tech.mrzeapple.ciphercodex.ui.components.CipherShapeSmall
import tech.mrzeapple.ciphercodex.ui.components.CipherTextField
import tech.mrzeapple.ciphercodex.ui.library.COVER_FINISHED
import tech.mrzeapple.ciphercodex.ui.library.CoverFinishedBadge
import tech.mrzeapple.ciphercodex.ui.library.CoverProgressRing
import tech.mrzeapple.ciphercodex.ui.library.GeneratedCover
import tech.mrzeapple.ciphercodex.ui.theme.LocalCipherColors
import kotlin.math.roundToInt

/** The per-book hub reached by tapping a library book: cover hero, progress,
 *  resume, description, annotation counts, shelf membership, and delete. */
@Composable
fun BookDetailScreen(
    bookId: Long,
    onBack: () -> Unit,
    onResume: () -> Unit,
    onDeleted: () -> Unit,
) {
    val c = LocalCipherColors.current
    val vm: BookDetailViewModel = viewModel(key = "detail-$bookId") {
        val application = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
        BookDetailViewModel(application, bookId)
    }
    val book by vm.book.collectAsState()
    val percentage by vm.percentage.collectAsState()
    val description by vm.description.collectAsState()
    val bookmarkCount by vm.bookmarkCount.collectAsState()
    val highlightCount by vm.highlightCount.collectAsState()
    val collections by vm.collections.collectAsState()
    val memberOf by vm.memberOf.collectAsState()

    var newShelf by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.phosphor)
            }
            CipherCaption("DETAILS")
        }

        val b = book
        if (b == null) {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CipherCaption("LOADING…")
            }
            return@Column
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            DetailCover(
                coverPath = b.coverPath,
                title = b.title,
                percentage = percentage,
                modifier = Modifier.width(132.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = b.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                CipherCaption((b.author ?: "UNKNOWN AUTHOR").uppercase())
                Spacer(Modifier.height(16.dp))
                val pct = percentage
                CipherCaption(
                    when {
                        pct == null -> "NOT STARTED"
                        pct >= COVER_FINISHED -> "✓ FINISHED"
                        else -> "READING · ${(pct * 100).roundToInt()}%"
                    },
                    color = if (pct != null) c.cyan else c.muted,
                )
                if (pct != null && pct < COVER_FINISHED) {
                    Spacer(Modifier.height(8.dp))
                    CipherProgressBar(pct)
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        CipherButton(
            text = if (percentage == null) "START READING" else "RESUME",
            onClick = onResume,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        val desc = description
        if (!desc.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            Box(Modifier.padding(horizontal = 16.dp)) {
                CipherPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        CipherCaption("ABOUT")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.phosphor,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        CipherCaption(
            "$bookmarkCount BOOKMARK${plural(bookmarkCount)} · $highlightCount HIGHLIGHT${plural(highlightCount)}",
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(16.dp))
        CipherCaption("SHELVES", modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        if (collections.isEmpty()) {
            CipherCaption(
                "NO SHELVES YET",
                color = c.muted,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                collections.forEach { shelf ->
                    val active = shelf.id in memberOf
                    ShelfChip(shelf.name, active) { vm.setInShelf(shelf.id, !active) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                CipherTextField(value = newShelf, onValueChange = { newShelf = it }, label = "NEW SHELF")
            }
            Spacer(Modifier.width(8.dp))
            CipherButton(
                text = "ADD",
                onClick = {
                    vm.createShelf(newShelf)
                    newShelf = ""
                },
                enabled = newShelf.isNotBlank(),
            )
        }

        Spacer(Modifier.height(24.dp))
        CipherButton(
            text = "DELETE BOOK",
            onClick = { confirmDelete = true },
            accent = c.magenta,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(24.dp))
    }

    if (confirmDelete) {
        Dialog(onDismissRequest = { confirmDelete = false }) {
            CipherPanel {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        text = "DELETE THIS BOOK?",
                        style = MaterialTheme.typography.titleMedium,
                        color = c.phosphor,
                    )
                    Spacer(Modifier.height(8.dp))
                    CipherCaption("REMOVES IT FROM YOUR LIBRARY. PROGRESS, BOOKMARKS AND HIGHLIGHTS ARE LOST.")
                    Spacer(Modifier.height(20.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            CipherButton(text = "CANCEL", onClick = { confirmDelete = false }, modifier = Modifier.fillMaxWidth())
                        }
                        Box(Modifier.weight(1f)) {
                            CipherButton(
                                text = "DELETE",
                                onClick = {
                                    confirmDelete = false
                                    vm.deleteBook(onDeleted)
                                },
                                accent = c.magenta,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun plural(n: Int) = if (n == 1) "" else "S"

@Composable
private fun ShelfChip(name: String, active: Boolean, onClick: () -> Unit) {
    val cipher = LocalCipherColors.current
    val c = if (active) cipher.cyan else cipher.muted
    Box(
        Modifier
            .clip(CipherShapeSmall)
            .border(1.dp, c, CipherShapeSmall)
            .background(if (active) cipher.cyan.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(name.uppercase(), style = MaterialTheme.typography.labelSmall, color = c)
    }
}

@Composable
private fun DetailCover(coverPath: String?, title: String, percentage: Float?, modifier: Modifier = Modifier) {
    val c = LocalCipherColors.current
    val cover by produceState<ImageBitmap?>(initialValue = null, coverPath) {
        value = coverPath?.let { path ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    var sample = 1
                    while (bounds.outWidth > 0 && bounds.outWidth / (sample * 2) >= 320) sample *= 2
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .clip(CipherShapeSmall)
            .background(c.void),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = cover
        if (bmp != null) {
            Image(bmp, contentDescription = title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            GeneratedCover(title = title, modifier = Modifier.fillMaxSize())
        }
        when {
            percentage == null -> Unit
            percentage >= COVER_FINISHED -> CoverFinishedBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            else -> CoverProgressRing(percentage, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
    }
}
