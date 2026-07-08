package tech.mrzeapple.ciphercodex.ui.kept

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import tech.mrzeapple.ciphercodex.data.db.HighlightWithBook
import tech.mrzeapple.ciphercodex.ui.components.CipherButton
import tech.mrzeapple.ciphercodex.ui.components.CipherCaption
import tech.mrzeapple.ciphercodex.ui.components.CipherHeader
import tech.mrzeapple.ciphercodex.ui.components.CipherPanel
import tech.mrzeapple.ciphercodex.ui.components.CipherShapeSmall
import tech.mrzeapple.ciphercodex.ui.components.CipherTextField
import tech.mrzeapple.ciphercodex.ui.theme.HighlightPalette
import tech.mrzeapple.ciphercodex.ui.theme.LocalCipherColors
import tech.mrzeapple.ciphercodex.ui.theme.highlightTint

/** KEPT: everything you highlighted, across every book — the annotation hub.
 *  Tap a card to edit its note/color; the accent bar shows the highlight color. */
@Composable
fun KeptScreen(
    onOpenBook: (Long) -> Unit,
    modifier: Modifier = Modifier,
    vm: KeptViewModel = viewModel(),
) {
    val c = LocalCipherColors.current
    val highlights by vm.highlights.collectAsState()
    var editing by remember { mutableStateOf<HighlightWithBook?>(null) }
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { CipherHeader(title = "KEPT") }
            if (highlights.isNotEmpty()) {
                CipherCaption(
                    "EXPORT",
                    color = c.cyan,
                    modifier = Modifier
                        .clickable { shareHighlightsMarkdown(context, highlights) }
                        .padding(8.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        if (highlights.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CipherCaption("NOTHING KEPT YET — SELECT TEXT WHILE READING")
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(highlights, key = { it.highlight.id }) { hw ->
                    KeptCard(
                        hw,
                        onOpen = { onOpenBook(hw.highlight.bookId) },
                        onEdit = { editing = hw },
                        onDelete = { vm.delete(hw.highlight.id) },
                    )
                }
            }
        }
    }

    editing?.let { hw ->
        EditHighlightDialog(
            hw = hw,
            onSave = { note, colorId ->
                vm.updateAnnotation(hw.highlight.id, note, colorId)
                editing = null
            },
            onOpen = { onOpenBook(hw.highlight.bookId); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun KeptCard(hw: HighlightWithBook, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val c = LocalCipherColors.current
    CipherPanel(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // Accent bar in the highlight's own color.
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(highlightTint(hw.highlight.colorId).copy(alpha = 1f)),
            )
            Column(Modifier.padding(14.dp)) {
                Text(
                    text = "“${hw.highlight.text}”",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                hw.highlight.note?.takeIf { it.isNotBlank() }?.let { note ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = c.phosphor,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val meta = hw.bookTitle.uppercase() + (hw.bookAuthor?.let { " · $it" } ?: "")
                    CipherCaption(meta, Modifier.weight(1f), color = c.muted)
                    Text(
                        text = "OPEN",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.cyan,
                        modifier = Modifier.clickable(onClick = onOpen).padding(8.dp),
                    )
                    Text(
                        text = "DELETE",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.magenta,
                        modifier = Modifier.clickable(onClick = onDelete).padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EditHighlightDialog(
    hw: HighlightWithBook,
    onSave: (String?, Int) -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalCipherColors.current
    var note by remember { mutableStateOf(hw.highlight.note.orEmpty()) }
    var colorId by remember { mutableStateOf(hw.highlight.colorId) }
    Dialog(onDismissRequest = onDismiss) {
        CipherPanel(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = "“${hw.highlight.text}”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.phosphor,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(16.dp))
                CipherCaption("COLOR")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HighlightPalette.forEachIndexed { id, tint ->
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CipherShapeSmall)
                                .background(tint.copy(alpha = 1f))
                                .border(
                                    2.dp,
                                    if (id == colorId) c.cyan else Color.Transparent,
                                    CipherShapeSmall,
                                )
                                .clickable { colorId = id },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                CipherTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = "NOTE",
                    singleLine = false,
                )
                Spacer(Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CipherButton("OPEN", onClick = onOpen)
                    Spacer(Modifier.weight(1f))
                    CipherButton("CANCEL", onClick = onDismiss)
                    CipherButton("SAVE", onClick = { onSave(note.trim().ifBlank { null }, colorId) })
                }
            }
        }
    }
}

/** Formats every kept highlight as Markdown grouped by book and fires the share
 *  sheet — the payoff for keeping quotes: get them out. */
private fun shareHighlightsMarkdown(context: Context, highlights: List<HighlightWithBook>) {
    if (highlights.isEmpty()) return
    val sb = StringBuilder()
    highlights.groupBy { it.bookTitle to it.bookAuthor }.forEach { (book, items) ->
        val (title, author) = book
        sb.append("# ").append(title)
        if (!author.isNullOrBlank()) sb.append(" — ").append(author)
        sb.append("\n\n")
        items.forEach { hw ->
            sb.append("> ").append(hw.highlight.text.replace('\n', ' ')).append('\n')
            hw.highlight.note?.takeIf { it.isNotBlank() }?.let { sb.append('\n').append(it.replace('\n', ' ')).append('\n') }
            sb.append('\n')
        }
    }
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, sb.toString())
            },
            "EXPORT HIGHLIGHTS",
        ),
    )
}
