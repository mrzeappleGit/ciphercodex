package tech.mrzeapple.ciphercodex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import tech.mrzeapple.ciphercodex.ui.library.LibraryScreen
import tech.mrzeapple.ciphercodex.ui.nav.Routes
import tech.mrzeapple.ciphercodex.ui.reader.ReaderScreen
import tech.mrzeapple.ciphercodex.ui.settings.SettingsScreen
import tech.mrzeapple.ciphercodex.ui.stats.StatsScreen
import tech.mrzeapple.ciphercodex.ui.theme.CipherCodexTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app chrome is forced-dark regardless of system light/dark mode,
        // so system-bar icons must stay light; Sepia flips them in the reader.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            CipherCodexTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = Routes.LIBRARY) {
                    composable(Routes.LIBRARY) {
                        LibraryScreen(
                            onOpenBook = { bookId -> nav.navigate(Routes.reader(bookId)) },
                            onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                            onOpenStats = { nav.navigate(Routes.STATS) },
                        )
                    }
                    composable(
                        Routes.READER,
                        arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
                    ) { backStack ->
                        val bookId = backStack.arguments?.getLong("bookId") ?: return@composable
                        ReaderScreen(bookId = bookId, onBack = { nav.popBackStack() })
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(onBack = { nav.popBackStack() })
                    }
                    composable(Routes.STATS) {
                        StatsScreen(onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
