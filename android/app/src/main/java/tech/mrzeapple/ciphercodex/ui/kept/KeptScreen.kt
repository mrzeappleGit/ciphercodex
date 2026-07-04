package tech.mrzeapple.ciphercodex.ui.kept

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import tech.mrzeapple.ciphercodex.data.db.HighlightWithBook
import tech.mrzeapple.ciphercodex.ui.components.CipherCaption
import tech.mrzeapple.ciphercodex.ui.components.CipherHeader
import tech.mrzeapple.ciphercodex.ui.components.CipherPanel
import tech.mrzeapple.ciphercodex.ui.theme.CipherMagenta
import tech.mrzeapple.ciphercodex.ui.theme.CipherMuted

/** KEPT: everything you highlighted, across every book. Magenta marks what you kept. */
@Composable
fun KeptScreen(
    onOpenBook: (Long) -> Unit,
    modifier: Modifier = Modifier,
    vm: KeptViewModel = viewModel(),
) {
    val highlights by vm.highlights.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        CipherHeader(title = "KEPT")
        Spacer(Modifier.height(12.dp))

        if (highlights.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CipherCaption("NOTHING KEPT YET — SELECT TEXT WHILE READING")
            }
        } else {
            LazyColumn(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(highlights, key = { it.highlight.id }) { hw ->
                    KeptCard(hw, onOpen = { onOpenBook(hw.highlight.bookId) }, onDelete = { vm.delete(hw.highlight.id) })
                }
            }
        }
    }
}

@Composable
private fun KeptCard(hw: HighlightWithBook, onOpen: () -> Unit, onDelete: () -> Unit) {
    CipherPanel(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // Magenta spine — the "kept" accent.
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(CipherMagenta),
            )
            Column(Modifier.padding(14.dp)) {
                Text(
                    text = "“${hw.highlight.text}”",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val meta = hw.bookTitle.uppercase() + (hw.bookAuthor?.let { " · $it" } ?: "")
                    CipherCaption(meta, Modifier.weight(1f), color = CipherMuted)
                    Text(
                        text = "DELETE",
                        style = MaterialTheme.typography.labelSmall,
                        color = CipherMagenta,
                        modifier = Modifier
                            .clickable(onClick = onDelete)
                            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                    )
                }
            }
        }
    }
}
