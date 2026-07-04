package tech.mrzeapple.ciphercodex.ui.library

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.mrzeapple.ciphercodex.data.BookWithProgress
import tech.mrzeapple.ciphercodex.data.db.CollectionEntity
import tech.mrzeapple.ciphercodex.data.prefs.LibrarySort
import tech.mrzeapple.ciphercodex.ui.components.CipherButton
import tech.mrzeapple.ciphercodex.ui.components.CipherCaption
import tech.mrzeapple.ciphercodex.ui.components.CipherHeader
import tech.mrzeapple.ciphercodex.ui.components.CipherPanel
import tech.mrzeapple.ciphercodex.ui.components.CipherProgressBar
import tech.mrzeapple.ciphercodex.ui.components.CipherShape
import tech.mrzeapple.ciphercodex.ui.components.CipherShapeSmall
import tech.mrzeapple.ciphercodex.ui.components.CipherTextField
import tech.mrzeapple.ciphercodex.ui.theme.CipherCyan
import tech.mrzeapple.ciphercodex.ui.theme.CipherMagenta
import tech.mrzeapple.ciphercodex.ui.theme.CipherMuted
import tech.mrzeapple.ciphercodex.ui.theme.CipherStatic
import tech.mrzeapple.ciphercodex.ui.theme.CipherVoid

private val EPUB_MIME_TYPES = arrayOf("application/epub+zip", "application/octet-stream")

@Composable
fun LibraryScreen(
    onOpenBook: (Long) -> Unit,
    onOpenOpds: () -> Unit,
) {
    val vm: LibraryViewModel = viewModel()
    val books by vm.books.collectAsState()
    val importState by vm.importState.collectAsState()
    val query by vm.query.collectAsState()
    val filter by vm.filter.collectAsState()
    val sort by vm.sort.collectAsState()
    val libraryEmpty by vm.isLibraryEmpty.collectAsState()
    val collections by vm.collections.collectAsState()
    val bookCollections by vm.bookCollections.collectAsState()
    val selectedCollection by vm.selectedCollection.collectAsState()
    val filterCounts by vm.filterCounts.collectAsState()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) vm.importEpubs(uris) }
    val launchImport = { importLauncher.launch(EPUB_MIME_TYPES) }
    val importing = importState is ImportUiState.Working

    var bookMenu by remember { mutableStateOf<BookWithProgress?>(null) }
    var shelfToDelete by remember { mutableStateOf<CollectionEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        CipherHeader(
            title = "CIPHERCODEX",
            trailing = {
                Text(
                    text = "BROWSE",
                    style = MaterialTheme.typography.labelSmall,
                    color = CipherMuted,
                    modifier = Modifier
                        .clickable(onClick = onOpenOpds)
                        .padding(8.dp),
                )
            },
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ImportStatus(importState, Modifier.weight(1f))
            if (!libraryEmpty) {
                CipherButton("IMPORT", onClick = launchImport, enabled = !importing)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (libraryEmpty) {
            EmptyLibrary(
                onImport = launchImport,
                importing = importing,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        } else {
            CipherTextField(
                value = query,
                onValueChange = vm::setQuery,
                label = "SEARCH",
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LibraryChip(
                    text = "SORT ▸ ${sort.name}",
                    active = sort != LibrarySort.RECENT,
                    onClick = { vm.setSort(nextSort(sort)) },
                )
                LibraryFilter.entries.forEach { f ->
                    val count = filterCounts[f] ?: 0
                    LibraryChip(text = "${f.name} · $count", active = filter == f, onClick = { vm.setFilter(f) })
                }
            }
            if (collections.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LibraryChip("ALL SHELVES", selectedCollection == null) { vm.setCollectionFilter(null) }
                    collections.forEach { c ->
                        LibraryChip(
                            text = c.name.uppercase(),
                            active = selectedCollection == c.id,
                            onLongClick = { shelfToDelete = c },
                        ) { vm.setCollectionFilter(c.id) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (books.isEmpty()) {
                // Only a genuine search/filter shows "NO MATCHES"; with no query
                // and no filter an empty list is just the first-frame race
                // between the books and isLibraryEmpty flows.
                val searching = query.isNotBlank() ||
                    filter != LibraryFilter.ALL ||
                    selectedCollection != null
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (searching) CipherCaption("NO MATCHES")
                }
            } else {
                // The Continue Reading hero only makes sense in the default
                // browsing view; searching/filtering/re-sorting hides it.
                val defaultView = query.isBlank() &&
                    filter == LibraryFilter.ALL &&
                    sort == LibrarySort.RECENT &&
                    selectedCollection == null
                val lastRead =
                    if (defaultView) books.first().takeIf { it.book.lastOpenedAt != null } else null
                val gridBooks = if (lastRead != null) books.drop(1) else books
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    if (lastRead != null) {
                        item(key = lastRead.book.id, span = { GridItemSpan(maxLineSpan) }) {
                            ContinueReadingHero(
                                entry = lastRead,
                                onOpen = { onOpenBook(lastRead.book.id) },
                                onLongPress = { bookMenu = lastRead },
                            )
                        }
                    }
                    items(gridBooks, key = { it.book.id }) { entry ->
                        BookCard(
                            entry = entry,
                            onOpen = { onOpenBook(entry.book.id) },
                            onLongPress = { bookMenu = entry },
                        )
                    }
                }
            }
        }
    }

    bookMenu?.let { target ->
        val memberOf = bookCollections
            .filter { it.bookId == target.book.id }
            .mapTo(HashSet()) { it.collectionId }
        BookActionsDialog(
            target = target,
            collections = collections,
            memberOf = memberOf,
            onToggleShelf = { collectionId, inShelf ->
                vm.setBookInCollection(target.book.id, collectionId, inShelf)
            },
            onCreateShelf = { name -> vm.createCollection(name, addBookId = target.book.id) },
            onDelete = {
                vm.delete(target.book.id)
                bookMenu = null
            },
            onDismiss = { bookMenu = null },
        )
    }

    shelfToDelete?.let { shelf ->
        AlertDialog(
            onDismissRequest = { shelfToDelete = null },
            containerColor = CipherStatic,
            shape = CipherShape,
            title = {
                Text(
                    text = "DELETE SHELF \"${shelf.name.uppercase()}\"?",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = { CipherCaption("THE BOOKS STAY IN YOUR LIBRARY") },
            confirmButton = {
                CipherButton(
                    text = "DELETE",
                    accent = CipherMagenta,
                    onClick = {
                        vm.deleteCollection(shelf.id)
                        shelfToDelete = null
                    },
                )
            },
            dismissButton = {
                CipherButton("KEEP", onClick = { shelfToDelete = null })
            },
        )
    }
}

@Composable
private fun BookActionsDialog(
    target: BookWithProgress,
    collections: List<CollectionEntity>,
    memberOf: Set<Long>,
    onToggleShelf: (Long, Boolean) -> Unit,
    onCreateShelf: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var newShelf by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CipherStatic,
        shape = CipherShape,
        title = {
            Text(
                text = target.book.title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column {
                CipherCaption("SHELVES")
                Spacer(Modifier.height(8.dp))
                if (collections.isEmpty()) {
                    CipherCaption("NO SHELVES YET")
                } else {
                    collections.forEach { c ->
                        val inShelf = c.id in memberOf
                        LibraryChip(c.name.uppercase(), active = inShelf) {
                            onToggleShelf(c.id, !inShelf)
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CipherTextField(
                        value = newShelf,
                        onValueChange = { newShelf = it },
                        label = "NEW SHELF",
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    CipherButton("ADD", onClick = {
                        onCreateShelf(newShelf)
                        newShelf = ""
                    })
                }
            }
        },
        confirmButton = {
            CipherButton("DELETE", accent = CipherMagenta, onClick = onDelete)
        },
        dismissButton = {
            CipherButton("CLOSE", onClick = onDismiss)
        },
    )
}

@Composable
private fun ImportStatus(state: ImportUiState, modifier: Modifier = Modifier) {
    when (state) {
        ImportUiState.Idle -> Spacer(modifier)
        is ImportUiState.Working -> CipherCaption(
            if (state.total == 1) "IMPORTING..." else "IMPORTING ${state.current}/${state.total}...",
            modifier,
        )
        is ImportUiState.Done -> CipherCaption(state.message.uppercase(), modifier, color = CipherCyan)
        is ImportUiState.Error -> CipherCaption(state.message.uppercase(), modifier, color = CipherMagenta)
    }
}

/** Compact toggle chip for the sort cycle and reading-state filters.
 *  [onLongClick] is used by shelf chips to offer deletion. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryChip(
    text: String,
    active: Boolean,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val color = if (active) CipherCyan else CipherMuted
    Box(
        modifier = Modifier
            .clip(CipherShapeSmall)
            .border(1.dp, color, CipherShapeSmall)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

private fun nextSort(current: LibrarySort): LibrarySort {
    val values = LibrarySort.entries
    return values[(current.ordinal + 1) % values.size]
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueReadingHero(
    entry: BookWithProgress,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    CipherPanel {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        ) {
            Box(Modifier.width(110.dp)) {
                BookCover(coverPath = entry.book.coverPath, title = entry.book.title, percentage = entry.percentage)
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(12.dp),
            ) {
                CipherCaption("CONTINUE READING", color = CipherCyan)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = entry.book.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                CipherCaption((entry.book.author ?: "UNKNOWN AUTHOR").uppercase())
                Spacer(Modifier.height(8.dp))
                CipherProgressBar(entry.percentage ?: 0f)
                entry.percentage?.let { pct ->
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        CipherCaption("${(pct * 100).roundToInt()}%")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(entry: BookWithProgress, onOpen: () -> Unit, onLongPress: () -> Unit) {
    CipherPanel {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        ) {
            BookCover(coverPath = entry.book.coverPath, title = entry.book.title, percentage = entry.percentage)
            Column(Modifier.padding(8.dp)) {
                Text(
                    text = entry.book.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                CipherCaption((entry.book.author ?: "UNKNOWN AUTHOR").uppercase())
                Spacer(Modifier.height(8.dp))
                CipherProgressBar(entry.percentage ?: 0f)
                entry.percentage?.let { pct ->
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        CipherCaption("${(pct * 100).roundToInt()}%")
                    }
                }
            }
        }
    }
}

@Composable
private fun BookCover(coverPath: String?, title: String, percentage: Float?) {
    // Decode off the main thread; the generated cover shows while null.
    val cover by produceState<ImageBitmap?>(initialValue = null, coverPath) {
        value = coverPath?.let { path ->
            withContext(Dispatchers.IO) { decodeCoverSampled(path) }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .background(CipherVoid),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = cover
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            GeneratedCover(title = title)
        }
        // Reading-status corner: queued tag, finished check, or progress ring.
        when {
            percentage == null -> CoverQueuedTag(Modifier.align(Alignment.TopStart).padding(8.dp))
            percentage >= COVER_FINISHED -> CoverFinishedBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
            else -> CoverProgressRing(percentage, Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
    }
}

private const val COVER_TARGET_WIDTH_PX = 256

/** Decodes [path] subsampled to roughly [COVER_TARGET_WIDTH_PX] wide (~2x the
 *  120dp grid cell), so a full-resolution stored cover never inflates a
 *  multi-megabyte bitmap per cell. */
private fun decodeCoverSampled(path: String): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= COVER_TARGET_WIDTH_PX) sampleSize *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeFile(path, options)?.asImageBitmap()
}

/** Five horizontal bars stepping cyan -> magenta: the app-icon glyph, reused
 *  as the no-cover placeholder and the empty-state motif. */
@Composable
private fun BarsMotif(barWidth: Dp, barHeight: Dp, gap: Dp, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(gap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        repeat(5) { i ->
            Box(
                Modifier
                    .width(barWidth)
                    .height(barHeight)
                    .background(lerp(CipherCyan, CipherMagenta, i / 4f)),
            )
        }
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit, importing: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BarsMotif(barWidth = 96.dp, barHeight = 6.dp, gap = 10.dp)
        Spacer(Modifier.height(24.dp))
        Text(
            text = "LIBRARY EMPTY",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        CipherCaption("IMPORT AN EPUB TO BEGIN")
        Spacer(Modifier.height(24.dp))
        CipherButton("IMPORT", onClick = onImport, enabled = !importing)
    }
}
