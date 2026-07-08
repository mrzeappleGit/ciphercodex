package tech.mrzeapple.ciphercodex.ui.apps

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.mrzeapple.ciphercodex.ui.components.CipherCaption
import tech.mrzeapple.ciphercodex.ui.components.CipherHeader
import tech.mrzeapple.ciphercodex.ui.theme.LocalCipherColors

/** A launchable installed app: label + pre-rasterised icon + its launch intent. */
private data class AppEntry(
    val label: String,
    val icon: ImageBitmap,
    val launch: Intent,
)

/** App drawer for when CipherCodex is the device home screen. Lists every
 *  launchable app (except ourselves) as an icon grid; tap to open. */
@Composable
fun AppsScreen() {
    val context = LocalContext.current
    val ink = LocalCipherColors.current.phosphor
    // Query off the main thread — loadLabel/loadIcon touch each package's
    // resources and are slow across a full app list.
    val apps by produceState<List<AppEntry>?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { loadApps(context) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        CipherHeader(title = "APPS", modifier = Modifier.padding(16.dp))
        when (val list = apps) {
            null -> CipherCaption("LOADING...", modifier = Modifier.padding(horizontal = 16.dp))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(84.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(list, key = { it.launch.component?.packageName ?: it.label }) { app ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                runCatching { context.startActivity(app.launch) }
                            }
                            .padding(vertical = 4.dp),
                    ) {
                        Image(
                            bitmap = app.icon,
                            contentDescription = app.label,
                            modifier = Modifier.size(48.dp),
                        )
                        Text(
                            text = app.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = ink,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val self = context.packageName
    val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(query, 0)
        .asSequence()
        .map { it.activityInfo.packageName }
        .filter { it != self }
        .distinct()
        .mapNotNull { pkg ->
            val launch = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            val label = pm.getApplicationLabel(info).toString()
            val icon = pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap()
            AppEntry(label = label, icon = icon, launch = launch)
        }
        .sortedBy { it.label.lowercase() }
        .toList()
}
