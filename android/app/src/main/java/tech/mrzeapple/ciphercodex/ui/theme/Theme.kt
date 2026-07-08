package tech.mrzeapple.ciphercodex.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val CipherDarkColorScheme = darkColorScheme(
    background = CipherVoid,
    surface = CipherStatic,
    primary = CipherCyan,
    secondary = CipherMagenta,
    onBackground = CipherPhosphor,
    onSurface = CipherPhosphor,
    onPrimary = CipherVoid,
    onSecondary = CipherVoid,
)

// E-INK chrome: ink-on-paper so Material-driven widgets (text fields, etc.)
// match the CipherEink palette. Mirrors CipherEink in Color.kt.
private val CipherEinkColorScheme = lightColorScheme(
    background = CipherEink.void,
    surface = CipherEink.static,
    primary = CipherEink.cyan,
    secondary = CipherEink.magenta,
    onBackground = CipherEink.phosphor,
    onSurface = CipherEink.phosphor,
    onPrimary = CipherEink.void,
    onSecondary = CipherEink.void,
)

// CipherCodex ignores system light/dark — the only switch is the user's E-INK
// mode, for reading on color e-ink. The reading surface uses ReadingNight/etc.
// from Color.kt directly, not this.
@Composable
fun CipherCodexTheme(eink: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCipherColors provides if (eink) CipherEink else CipherDark) {
        MaterialTheme(
            colorScheme = if (eink) CipherEinkColorScheme else CipherDarkColorScheme,
            typography = CipherCodexTypography,
            content = content,
        )
    }
}
