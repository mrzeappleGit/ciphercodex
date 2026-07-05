package tech.mrzeapple.ciphercodex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import tech.mrzeapple.ciphercodex.ui.components.CipherBottomNav
import tech.mrzeapple.ciphercodex.ui.components.CipherIconKept
import tech.mrzeapple.ciphercodex.ui.components.CipherIconLibrary
import tech.mrzeapple.ciphercodex.ui.components.CipherIconSettings
import tech.mrzeapple.ciphercodex.ui.components.CipherIconStats
import tech.mrzeapple.ciphercodex.ui.kept.KeptScreen
import tech.mrzeapple.ciphercodex.ui.library.LibraryScreen
import tech.mrzeapple.ciphercodex.ui.settings.SettingsScreen
import tech.mrzeapple.ciphercodex.ui.stats.StatsScreen

/** The tabbed home: Library / Kept / Stats / Settings switched by the bottom bar.
 *  Reader and OPDS remain full-screen destinations outside this scaffold. */
@Composable
fun MainScaffold(
    onOpenBookDetail: (Long) -> Unit,
    onOpenReader: (Long) -> Unit,
    onOpenOpds: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        CipherIconLibrary to "LIBRARY",
        CipherIconKept to "KEPT",
        CipherIconStats to "STATS",
        CipherIconSettings to "SET",
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> LibraryScreen(onOpenBook = onOpenBookDetail, onOpenOpds = onOpenOpds)
                1 -> KeptScreen(onOpenBook = onOpenReader)
                2 -> StatsScreen()
                else -> SettingsScreen()
            }
        }
        CipherBottomNav(tabs = tabs, selected = tab, onSelect = { tab = it })
    }
}
