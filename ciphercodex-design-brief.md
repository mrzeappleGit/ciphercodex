# CipherCodex — design brief (Android / Compose)

Same Cipher system as CipherWave — shared palette, same restraint rules — extended for a reading app, with one real addition: a dedicated reading surface that deliberately does not use the neon chrome.

## Assumption
Built for Jetpack Compose + Material 3, since that's the modern default for a new Android app. If this is actually a Views/XML project, say so and these become `colors.xml` / `themes.xml` instead — same values, different format.

## Files in this handoff
- `Color.kt`, `Type.kt`, `Theme.kt` — drop into `ui/theme/` in a Compose project. These are the exact three files the Android Studio Compose template generates, so they overwrite cleanly.
- `ciphercodex-icon.svg` — the app mark. Same angular frame as CipherWave's icon, different glyph inside: horizontal bars instead of vertical, reading as lines of text rather than a waveform. This is a source mark, not a launcher-ready asset — see below.
- Package assumed as `tech.mrzeapple.ciphercodex` — change the `package` line at the top of each `.kt` file if that's wrong.

## The one real decision: chrome vs. reading surface
Everything that isn't the actual book text — library grid, currently-reading shelf, settings, search — is full Cipher brand: void background, cyan/magenta accents, Orbitron wordmark, Share Tech Mono UI text.

The page you actually read a novel on is different on purpose:
- **No neon.** A glowing cyan-on-black background is fine for a five-second glance at a card; it's fatiguing across a real reading session.
- **No Share Tech Mono.** Monospace is built for short, scannable UI strings — every character the same width fights the eye across a full paragraph. Book text needs a real reading face.
- Two reading themes instead: **Night** (`#121212` background, warm off-white text) and **Sepia** (`#F4ECD8` background, warm dark text) — the two modes every serious e-reader already trained readers to expect. Both are in `Color.kt`, both use `Literata` (Google's own reading-optimized serif) instead of the brand fonts.

Treat the reading surface as its own small theme the brand wraps around, not a screen that inherits `CipherCodexTheme` directly.

## Fonts
Orbitron and Share Tech Mono need to be added as font resources — Android doesn't do CSS `@import`. Download the `.ttf` files, drop them in `res/font/` using lowercase-with-underscores names (`orbitron_black.ttf`, `share_tech_mono_regular.ttf`, `literata_regular.ttf`), and the `Font(R.font.…)` references in `Type.kt` resolve automatically. I can go fetch the actual font files next if that's useful.

## Icon → launcher icon
Android adaptive icons need a foreground layer and a background layer as separate assets, plus legacy density PNGs for older devices — the SVG alone won't drop into `res/mipmap/`. I can generate the adaptive icon XML and density set from this SVG on request; didn't do it by default since it's a bunch of extra files for a mark you might still want to art-direct further first.

## Everything else
Same rules as the CipherWave brief: six-color palette, 4px spacing grid, one signature motion per app (CipherWave's is the eq pulse; CipherCodex's should probably be the page-turn, designed once and reused everywhere), and respecting reduced-motion — Android's system animator duration scale setting is the equivalent of `prefers-reduced-motion` here.
