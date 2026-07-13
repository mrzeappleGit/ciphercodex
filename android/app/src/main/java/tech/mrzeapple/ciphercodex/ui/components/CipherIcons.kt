package tech.mrzeapple.ciphercodex.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/* Feather-style line icons drawn straight from the design comp's SVG paths, so
 * the app stays free of the material-icons-extended dependency. Icon(tint = …)
 * recolors the strokes, so the placeholder stroke color below is irrelevant. */

private fun strokeIcon(vararg pathData: String): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply {
            pathData.forEach { d ->
                addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
        .build()

/** Open-book outline — the LIBRARY tab. */
val CipherIconLibrary: ImageVector = strokeIcon(
    "M4 19.5A2.5 2.5 0 0 1 6.5 17H20",
    "M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z",
)

/** Pencil/edit — the KEPT (highlights) tab; magenta marks what you kept. */
val CipherIconKept: ImageVector = strokeIcon(
    "M20 12v7a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7",
    "M18 2l4 4-9 9-4 1 1-4z",
)

/** Bar chart — the STATS tab. */
val CipherIconStats: ImageVector = strokeIcon("M18 20V10", "M12 20V4", "M6 20v-6")

/** Four-square grid — the library grid-view toggle. */
val CipherIconGrid: ImageVector = strokeIcon("M3 3h7v7H3z", "M14 3h7v7h-7z", "M3 14h7v7H3z", "M14 14h7v7h-7z")

/** Lines with leading ticks — the library list-view toggle. */
val CipherIconList: ImageVector =
    strokeIcon("M8 6h13", "M8 12h13", "M8 18h13", "M3 6h0.01", "M3 12h0.01", "M3 18h0.01")

/** 3x3 dots — the APPS launcher tab (distinct from the 2x2 grid toggle). */
val CipherIconApps: ImageVector = strokeIcon(
    "M4 4h3v3H4z", "M10.5 4h3v3h-3z", "M17 4h3v3h-3z",
    "M4 10.5h3v3H4z", "M10.5 10.5h3v3h-3z", "M17 10.5h3v3h-3z",
    "M4 17h3v3H4z", "M10.5 17h3v3h-3z", "M17 17h3v3h-3z",
)

/** Document with text lines — the NOTES (rM2 notebooks) tab. */
val CipherIconNotes: ImageVector = strokeIcon(
    "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z",
    "M14 2v6h6",
    "M16 13H8",
    "M16 17H8",
)

/** Gear — the SET(tings) tab. */
val CipherIconSettings: ImageVector = strokeIcon(
    "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
    "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 " +
        "1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 " +
        "2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 " +
        "1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 " +
        "1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 " +
        "2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z",
)
