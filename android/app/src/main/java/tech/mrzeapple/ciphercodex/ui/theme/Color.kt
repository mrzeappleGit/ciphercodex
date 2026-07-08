package tech.mrzeapple.ciphercodex.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

// Shared Cipher system palette. Same values as CipherWave's tokens, renamed
// from the cw- prefix since this palette isn't Wave-specific — it's the
// whole Cipher system's. Worth renaming in the CipherWave codebase too.
// These raw constants are the Dark palette's source values; chrome reads them
// through LocalCipherColors (below) so E-INK mode can swap them at runtime.
val CipherVoid = Color(0xFF0A0A0F)
val CipherStatic = Color(0xFF15171F)
val CipherCyan = Color(0xFF00E5FF)
val CipherMagenta = Color(0xFFFF2A93)
val CipherPhosphor = Color(0xFFE7F6FA)
val CipherMuted = Color(0xFF6C8994)

/** Runtime-swappable chrome palette. Chrome composables read these via
 *  [LocalCipherColors] instead of the raw constants, so E-INK mode can flip the
 *  whole app from neon-on-void to ink-on-paper for color e-ink (Boox Kaleido). */
@Immutable
data class CipherColors(
    val void: Color,      // app background
    val static: Color,    // panel / bar surface
    val cyan: Color,      // primary accent + active state
    val magenta: Color,   // secondary accent
    val phosphor: Color,  // primary text / ink
    val muted: Color,     // secondary text / inactive
    val gradient: Brush,  // signature border / rule / progress fill
)

val CipherDark = CipherColors(
    void = CipherVoid,
    static = CipherStatic,
    cyan = CipherCyan,
    magenta = CipherMagenta,
    phosphor = CipherPhosphor,
    muted = CipherMuted,
    gradient = Brush.linearGradient(listOf(CipherCyan, CipherMagenta)),
)

// E-INK: pure black on pure white. The neon accents and the cyan→magenta
// gradient render muddy under Kaleido's color-filter layer, so they collapse to
// solid black (active/accents) and one grey (inactive). Max legibility.
val CipherEink = CipherColors(
    void = Color(0xFFFFFFFF),
    static = Color(0xFFFFFFFF),
    cyan = Color(0xFF000000),
    magenta = Color(0xFF000000),
    phosphor = Color(0xFF000000),
    muted = Color(0xFF6B6B6B),
    gradient = SolidColor(Color(0xFF000000)),
)

val LocalCipherColors = staticCompositionLocalOf { CipherDark }

/** Translucent highlight tints, indexed by HighlightEntity.colorId — the reading
 *  theme shows through the alpha. Use [highlightTint]; `.copy(alpha = 1f)` gives
 *  the solid swatch color for pickers and accent bars. */
val HighlightPalette = listOf(
    Color(0x5500E5FF), // cyan (default)
    Color(0x55FF2A93), // magenta
    Color(0x55E0B062), // amber
    Color(0x5540E0A0), // mint
)

fun highlightTint(colorId: Int): Color = HighlightPalette.getOrElse(colorId) { HighlightPalette[0] }

// Reading surface — CipherCodex-specific, deliberately not neon.
// Night and Sepia: the two modes every e-reader already trained readers to expect.
val ReadingNightBackground = Color(0xFF121212)
val ReadingNightText = Color(0xFFE6E1D8)
val ReadingSepiaBackground = Color(0xFFF4ECD8)
val ReadingSepiaText = Color(0xFF2B2116)
// AMOLED true-black and a bright paper-white, the two most-requested extras.
val ReadingBlackBackground = Color(0xFF000000)
val ReadingBlackText = Color(0xFFCFC9BE)
val ReadingPaperBackground = Color(0xFFFAF9F5)
val ReadingPaperText = Color(0xFF1A1712)
// E-INK: true #000 on #FFF — the max contrast color e-ink (Kaleido) needs, since
// its color-filter layer mutes the softer Paper tones. Paired with a heavier body
// weight in the reader to counter the panel thinning out letter strokes.
val ReadingContrastBackground = Color(0xFFFFFFFF)
val ReadingContrastText = Color(0xFF000000)
