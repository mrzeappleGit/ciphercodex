package tech.mrzeapple.ciphercodex

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import tech.mrzeapple.ciphercodex.data.ImportResult
import tech.mrzeapple.ciphercodex.ui.MainScaffold
import tech.mrzeapple.ciphercodex.ui.nav.Routes
import tech.mrzeapple.ciphercodex.ui.opds.OpdsScreen
import tech.mrzeapple.ciphercodex.ui.reader.ReaderScreen
import tech.mrzeapple.ciphercodex.ui.theme.CipherCodexTheme

class MainActivity : ComponentActivity() {

    // A book delivered via VIEW/SEND intent, imported and pending open; consumed
    // once by the nav effect (set back to null) so config changes don't reopen it.
    private val pendingOpen = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app chrome is forced-dark regardless of system light/dark mode,
        // so system-bar icons must stay light; Sepia flips them in the reader.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // Only on a fresh launch: a recreation (config change, process-death
        // restore) retains the original VIEW/SEND intent, and reprocessing it
        // would re-import and force-reopen the book.
        if (savedInstanceState == null) handleIncomingIntent(intent)
        setContent {
            CipherCodexTheme {
                val nav = rememberNavController()
                val pending by pendingOpen.collectAsState()
                LaunchedEffect(pending) {
                    val bookId = pending ?: return@LaunchedEffect
                    pendingOpen.value = null
                    nav.navigate(Routes.reader(bookId)) { launchSingleTop = true }
                }
                NavHost(navController = nav, startDestination = Routes.MAIN) {
                    composable(Routes.MAIN) {
                        MainScaffold(
                            onOpenBook = { bookId -> nav.navigate(Routes.reader(bookId)) },
                            onOpenOpds = { nav.navigate(Routes.OPDS) },
                        )
                    }
                    composable(Routes.OPDS) {
                        OpdsScreen(onBack = { nav.popBackStack() })
                    }
                    composable(
                        Routes.READER,
                        arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
                    ) { backStack ->
                        val bookId = backStack.arguments?.getLong("bookId") ?: return@composable
                        ReaderScreen(bookId = bookId, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /** Imports any EPUB(s) delivered by an "open with"/share intent and opens
     *  the first that lands in the library. The intent's transient read grant
     *  is valid here because importEpub copies the bytes immediately. */
    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return
        val uris = epubUris(intent)
        if (uris.isEmpty()) return
        // Consume the action so a redelivered/retained copy of this intent is
        // not imported again.
        intent.action = null
        val repository = (application as CipherCodexApp).repository
        lifecycleScope.launch {
            var openId: Long? = null
            for (uri in uris) {
                when (val result = repository.importEpub(uri)) {
                    is ImportResult.Imported -> if (openId == null) openId = result.bookId
                    is ImportResult.Duplicate -> if (openId == null) openId = result.bookId
                    is ImportResult.Failed -> Unit
                }
            }
            openId?.let { pendingOpen.value = it }
        }
    }

    private fun epubUris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        return when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE ->
                intent.parcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            else -> emptyList()
        }
    }
}

private inline fun <reified T : Parcelable> Intent.parcelableExtra(key: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }

private inline fun <reified T : Parcelable> Intent.parcelableArrayListExtra(key: String): ArrayList<T>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayListExtra(key)
    }
