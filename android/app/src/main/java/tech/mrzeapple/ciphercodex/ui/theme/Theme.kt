package tech.mrzeapple.ciphercodex.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CipherCodexColorScheme = darkColorScheme(
    background = CipherVoid,
    surface = CipherStatic,
    primary = CipherCyan,
    secondary = CipherMagenta,
    onBackground = CipherPhosphor,
    onSurface = CipherPhosphor,
    onPrimary = CipherVoid,
    onSecondary = CipherVoid,
)

// CipherCodex is dark-only, same rule as the rest of the Cipher system —
// isSystemInDarkTheme() is intentionally not wired up here.
// This wraps chrome screens only: library, search, settings. The reading
// surface uses ReadingNight/ReadingSepia from Color.kt directly, not this.
@Composable
fun CipherCodexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CipherCodexColorScheme,
        typography = CipherCodexTypography,
        content = content
    )
}
