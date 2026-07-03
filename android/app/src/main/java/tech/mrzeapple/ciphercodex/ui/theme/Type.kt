package tech.mrzeapple.ciphercodex.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import tech.mrzeapple.ciphercodex.R

// Chrome fonts. Add the .ttf files under res/font/ first — see the brief for sourcing.
val Orbitron = FontFamily(Font(R.font.orbitron_black, FontWeight.Black))
val ShareTechMono = FontFamily(Font(R.font.share_tech_mono_regular, FontWeight.Normal))

val CipherCodexTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Orbitron,
        fontWeight = FontWeight.Black,
        fontSize = 30.sp,
        letterSpacing = 1.sp
    ),
    titleMedium = TextStyle(
        fontFamily = ShareTechMono,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        letterSpacing = 0.5.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = ShareTechMono,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    labelSmall = TextStyle(
        fontFamily = ShareTechMono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 1.sp
    ),
)

// Reading surface only. Never referenced by CipherCodexTheme in Theme.kt —
// wire this up separately wherever the actual page content renders.
// Literata is Google's own reading-optimized serif; swap for anything with
// real hinting at body sizes. Don't reuse Share Tech Mono here — monospace
// breaks reading rhythm across a full paragraph.
// Bold/italic faces included so <b>/<i>/headings inside book text render with
// real glyphs instead of synthetic slanting.
val ReadingFont = FontFamily(
    Font(R.font.literata_regular, FontWeight.Normal),
    Font(R.font.literata_bold, FontWeight.Bold),
    Font(R.font.literata_italic, FontWeight.Normal, FontStyle.Italic),
)

val ReadingBodyStyle = TextStyle(
    fontFamily = ReadingFont,
    fontWeight = FontWeight.Normal,
    fontSize = 17.sp,
    lineHeight = 27.sp
)
