package tech.mrzeapple.ciphercodex.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.mrzeapple.ciphercodex.ui.components.CipherCaption
import tech.mrzeapple.ciphercodex.ui.theme.CipherCyan
import tech.mrzeapple.ciphercodex.ui.theme.CipherMuted
import tech.mrzeapple.ciphercodex.ui.theme.CipherStatic
import tech.mrzeapple.ciphercodex.ui.theme.ShareTechMono
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/* Chart rules (design-reviewed, do not drift): charts are cyan-only — magenta
 * never appears here; all text wears ink colors, never the series color;
 * single series means no legend; tap is the hover layer. */

/** Validated monotonic-lightness sequential ramp for the heatmap. */
private val HeatRamp = listOf(
    Color(0xFF0F5D6E),
    Color(0xFF0C8298),
    Color(0xFF06B3CB),
    Color(0xFF00E5FF),
)

internal fun formatShortDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 -> "${h}H ${m.toString().padStart(2, '0')}M"
        else -> "${m}M"
    }
}

/** 14-day reading-time bars. Single series: no legend; selective direct labels
 *  (peak day and today only); tap a bar for its exact value. */
@Composable
fun DailyBars(days: List<DayStat>, modifier: Modifier = Modifier) {
    if (days.isEmpty()) return
    var selected by remember(days) { mutableStateOf(-1) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = TextStyle(
        fontFamily = ShareTechMono,
        fontSize = 9.sp,
        color = CipherMuted,
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .pointerInput(days) {
                    detectTapGestures { offset ->
                        val slot = size.width.toFloat() / days.size
                        val idx = (offset.x / slot).toInt().coerceIn(0, days.lastIndex)
                        selected = if (selected == idx) -1 else idx
                    }
                },
        ) {
            val gap = with(density) { 2.dp.toPx() }
            val corner = with(density) { 4.dp.toPx() }
            val labelBand = with(density) { 14.dp.toPx() }
            val axisBand = with(density) { 12.dp.toPx() }
            val chartTop = labelBand
            val baseline = size.height - axisBand
            val chartHeight = baseline - chartTop
            val slot = size.width / days.size
            val barWidth = max(1f, slot - gap)
            val maxMs = max(1L, days.maxOf { it.millis })
            val peakIndex = days.indexOfFirst { it.millis == days.maxOf { d -> d.millis } }

            // Recessive baseline + one max gridline.
            drawLine(
                color = CipherMuted.copy(alpha = 0.25f),
                start = Offset(0f, baseline),
                end = Offset(size.width, baseline),
                strokeWidth = 1f,
            )
            drawLine(
                color = CipherMuted.copy(alpha = 0.25f),
                start = Offset(0f, chartTop),
                end = Offset(size.width, chartTop),
                strokeWidth = 1f,
            )

            days.forEachIndexed { i, day ->
                val x = i * slot + gap / 2
                val hRaw = (day.millis.toFloat() / maxMs) * chartHeight
                val h = if (day.millis > 0) max(hRaw, with(density) { 2.dp.toPx() }) else 0f
                if (h > 0f) {
                    val rect = Rect(x, baseline - h, x + barWidth, baseline)
                    val path = Path().apply {
                        addRoundRect(
                            RoundRect(
                                rect,
                                topLeft = CornerRadius(corner),
                                topRight = CornerRadius(corner),
                                bottomLeft = CornerRadius.Zero,
                                bottomRight = CornerRadius.Zero,
                            )
                        )
                    }
                    val dim = selected != -1 && selected != i
                    drawPath(path, color = if (dim) CipherCyan.copy(alpha = 0.45f) else CipherCyan)
                }
                // Selective direct labels: peak and today only.
                val isToday = i == days.lastIndex
                if (day.millis > 0 && (i == peakIndex || isToday)) {
                    val text = formatShortDuration(day.millis)
                    val layout = measurer.measure(text, labelStyle)
                    val lx = (x + barWidth / 2 - layout.size.width / 2)
                        .coerceIn(0f, size.width - layout.size.width)
                    drawText(layout, topLeft = Offset(lx, max(0f, baseline - h - layout.size.height - 2)))
                }
                // Day-of-week initial beneath every bar.
                val initial = day.date.dayOfWeek
                    .getDisplayName(JavaTextStyle.NARROW, Locale.getDefault())
                val axisLayout = measurer.measure(initial, labelStyle.copy(fontSize = 8.sp))
                drawText(
                    axisLayout,
                    topLeft = Offset(x + barWidth / 2 - axisLayout.size.width / 2, baseline + 2),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        val sel = days.getOrNull(selected)
        CipherCaption(
            text = if (sel != null) {
                val dow = sel.date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())
                "${dow.uppercase()} ${sel.date} — ${formatShortDuration(sel.millis)}"
            } else {
                "TAP A BAR FOR DETAIL"
            },
        )
    }
}

/** GitHub-style activity heatmap, Monday rows, ~15 week columns. Empty days sit
 *  at surface level; quartiles climb the sequential ramp. Tap a cell for the
 *  exact value (relief for the deliberately quiet low end). */
@Composable
fun StreakHeatmap(days: List<DayStat>, modifier: Modifier = Modifier) {
    if (days.isEmpty()) return
    var selected by remember(days) { mutableStateOf(-1) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val monthStyle = TextStyle(fontFamily = ShareTechMono, fontSize = 8.sp, color = CipherMuted)
    val weeks = (days.size + 6) / 7

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .pointerInput(days) {
                    detectTapGestures { offset ->
                        val gapPx = with(density) { 2.dp.toPx() }
                        val bandPx = with(density) { 12.dp.toPx() }
                        val cell = min(
                            (size.width - (weeks - 1) * gapPx) / weeks,
                            (size.height - bandPx - 6 * gapPx) / 7f,
                        )
                        val col = (offset.x / (cell + gapPx)).toInt()
                        val row = ((offset.y - bandPx) / (cell + gapPx)).toInt()
                        val idx = col * 7 + row
                        selected =
                            if (row in 0..6 && idx in days.indices && idx != selected) idx else -1
                    }
                },
        ) {
            val gap = with(density) { 2.dp.toPx() }
            val monthBand = with(density) { 12.dp.toPx() }
            val corner = with(density) { 2.dp.toPx() }
            val cell = min(
                (size.width - (weeks - 1) * gap) / weeks,
                (size.height - monthBand - 6 * gap) / 7f,
            )
            val maxMs = max(1L, days.maxOf { it.millis })

            var lastMonth = -1
            days.forEachIndexed { i, day ->
                val col = i / 7
                val row = i % 7
                val x = col * (cell + gap)
                val y = monthBand + row * (cell + gap)
                val color = if (day.millis <= 0) {
                    CipherStatic
                } else {
                    val q = ((day.millis * 4) / maxMs).toInt().coerceIn(0, 3)
                    HeatRamp[q]
                }
                val ringed = i == selected
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, y),
                    size = androidx.compose.ui.geometry.Size(cell, cell),
                    cornerRadius = CornerRadius(corner),
                )
                if (ringed) {
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(x, y),
                        size = androidx.compose.ui.geometry.Size(cell, cell),
                        cornerRadius = CornerRadius(corner),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
                    )
                }
                if (row == 0 && day.date.monthValue != lastMonth) {
                    lastMonth = day.date.monthValue
                    val label = day.date.month
                        .getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())
                        .uppercase()
                        .take(3)
                    drawText(measurer.measure(label, monthStyle), topLeft = Offset(x, 0f))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        val sel = days.getOrNull(selected)
        CipherCaption(
            text = if (sel != null) {
                "${sel.date} — ${formatShortDuration(sel.millis)}"
            } else {
                "TAP A DAY FOR DETAIL"
            },
        )
    }
}
