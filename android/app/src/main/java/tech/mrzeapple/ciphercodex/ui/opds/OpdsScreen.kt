package tech.mrzeapple.ciphercodex.ui.opds

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import tech.mrzeapple.ciphercodex.opds.OpdsEntry
import tech.mrzeapple.ciphercodex.ui.components.CipherButton
import tech.mrzeapple.ciphercodex.ui.components.CipherCaption
import tech.mrzeapple.ciphercodex.ui.components.CipherHeader
import tech.mrzeapple.ciphercodex.ui.components.CipherPanel
import tech.mrzeapple.ciphercodex.ui.components.CipherTextField
import tech.mrzeapple.ciphercodex.ui.theme.LocalCipherColors

@Composable
fun OpdsScreen(onBack: () -> Unit) {
    val c = LocalCipherColors.current
    val vm: OpdsViewModel = viewModel()
    val state by vm.state.collectAsState()

    // Auto-load the catalog on first entry.
    LaunchedEffect(Unit) {
        if (state.feed == null && !state.loading) vm.open(state.url)
    }

    // Device Back walks up the catalog history before leaving BROWSE.
    BackHandler(enabled = state.canGoBack) { vm.back() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 8.dp, end = 16.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.cyan)
            }
            CipherHeader(title = "BROWSE", modifier = Modifier.weight(1f))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CipherTextField(value = state.url, onValueChange = vm::setUrl, label = "CATALOG URL")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CipherButton("GO", onClick = { vm.open(state.url) }, enabled = !state.loading)
                if (state.canGoBack) {
                    CipherButton("BACK", onClick = vm::back, accent = c.muted, enabled = !state.loading)
                }
            }
            state.downloadStatus?.let { CipherCaption(it, color = c.cyan) }
            when {
                state.loading -> CipherCaption("LOADING...")
                state.error != null -> CipherCaption(state.error!!.uppercase(), color = c.magenta)
            }
            state.feed?.let { feed ->
                CipherCaption(feed.title.uppercase())
                if (feed.entries.isEmpty()) {
                    CipherCaption("EMPTY CATALOG", modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(feed.entries) { entry ->
                            OpdsRow(
                                entry = entry,
                                onOpen = { entry.navHref?.let { vm.open(it) } },
                                onDownload = { vm.download(entry) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpdsRow(entry: OpdsEntry, onOpen: () -> Unit, onDownload: () -> Unit) {
    val c = LocalCipherColors.current
    val base = Modifier.fillMaxWidth()
    CipherPanel(modifier = if (entry.isNavigation) base.clickable(onClick = onOpen) else base) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                entry.author?.let { CipherCaption(it.uppercase()) }
            }
            when {
                entry.isBook -> CipherButton("GET", onClick = onDownload)
                entry.isNavigation -> CipherCaption("OPEN ›", color = c.cyan)
            }
        }
    }
}
