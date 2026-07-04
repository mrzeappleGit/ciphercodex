package tech.mrzeapple.ciphercodex.ui.theme

import androidx.compose.ui.graphics.Color

// Shared Cipher system palette. Same values as CipherWave's tokens, renamed
// from the cw- prefix since this palette isn't Wave-specific — it's the
// whole Cipher system's. Worth renaming in the CipherWave codebase too.
val CipherVoid = Color(0xFF0A0A0F)
val CipherStatic = Color(0xFF15171F)
val CipherCyan = Color(0xFF00E5FF)
val CipherMagenta = Color(0xFFFF2A93)
val CipherPhosphor = Color(0xFFE7F6FA)
val CipherMuted = Color(0xFF6C8994)

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
