package tech.mrzeapple.ciphercodex.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.mrzeapple.ciphercodex.ui.components.CipherShapeSmall
import tech.mrzeapple.ciphercodex.ui.theme.CipherCyan
import tech.mrzeapple.ciphercodex.ui.theme.CipherMuted
import tech.mrzeapple.ciphercodex.ui.theme.CipherVoid
import tech.mrzeapple.ciphercodex.ui.theme.Orbitron
import kotlin.math.roundToInt

/** A book at/above this whole-book fraction reads as finished (mirrors the VM). */
internal const val COVER_FINISHED = 0.98f

private data class CoverTint(val top: Color, val bottom: Color, val border: Color, val line: Color, val ink: Color)

/** Deterministic per-title tint from the design comp's four cover treatments
 *  (cyan / magenta / brass / neutral), so a book's generated cover is stable. */
private fun coverTint(title: String): CoverTint = when (Math.floorMod(title.hashCode(), 4)) {
    0 -> CoverTint(Color(0xFF0C2A34), Color(0xFF06121A), CipherCyan.copy(alpha = 0.28f), CipherCyan.copy(alpha = 0.06f), Color(0xFFD7F6FF))
    1 -> CoverTint(Color(0xFF2A0C22), Color(0xFF150614), Color(0xFFFF2A93).copy(alpha = 0.30f), Color(0xFFFF2A93).copy(alpha = 0.06f), Color(0xFFFFD7EE))
    2 -> CoverTint(Color(0xFF22160A), Color(0xFF120C06), Color(0xFFE0B062).copy(alpha = 0.28f), Color(0xFFE0B062).copy(alpha = 0.06f), Color(0xFFF0DCB4))
    else -> CoverTint(Color(0xFF10202A), Color(0xFF080F16), Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.035f), Color(0xFFCFE0EC))
}

/** Generated cover for a book with no embedded art: tinted gradient, diagonal
 *  scanlines, and the title set in Orbitron — the design comp's cover motif. */
@Composable
internal fun GeneratedCover(title: String, modifier: Modifier = Modifier, showTitle: Boolean = true) {
    val t = remember(title) { coverTint(title) }
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(t.top, t.bottom)))
            .drawBehind {
                val step = 13.dp.toPx()
                val w = 1.dp.toPx()
                var x = 0f
                while (x < size.width + size.height) {
                    drawLine(t.line, Offset(x, size.height), Offset(x - size.height, 0f), w)
                    x += step
                }
            }
            .border(1.dp, t.border),
    ) {
        if (showTitle) {
            Text(
                text = title.uppercase(),
                style = TextStyle(fontFamily = Orbitron, fontSize = 13.sp, lineHeight = 15.sp, letterSpacing = 0.3.sp),
                color = t.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
            )
        }
    }
}

/** Reading-status overlay for a cover corner: a progress ring, a finished
 *  check, or a queued tag, matching the design comp. */
@Composable
internal fun CoverProgressRing(percentage: Float, modifier: Modifier = Modifier) {
    Box(modifier.size(38.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(CipherVoid)
            val stroke = 3.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(Color.White.copy(alpha = 0.12f), -90f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            drawArc(CipherCyan, -90f, 360f * percentage.coerceIn(0f, 1f), false, topLeft, arcSize, style = Stroke(stroke))
        }
        Text(
            text = "${(percentage * 100).roundToInt()}",
            style = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 10.sp),
            color = CipherCyan,
        )
    }
}

@Composable
internal fun CoverFinishedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(CipherCyan),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Check, contentDescription = "Finished", tint = CipherVoid, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun CoverQueuedTag(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(CipherShapeSmall)
            .border(1.dp, CipherMuted.copy(alpha = 0.6f), CipherShapeSmall)
            .background(CipherVoid.copy(alpha = 0.6f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "QUEUED",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = CipherMuted,
        )
    }
}
