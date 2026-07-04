package tech.mrzeapple.ciphercodex.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import tech.mrzeapple.ciphercodex.ui.components.CipherCaption
import tech.mrzeapple.ciphercodex.ui.components.CipherGradient
import tech.mrzeapple.ciphercodex.ui.components.CipherHeader
import tech.mrzeapple.ciphercodex.ui.components.CipherPanel
import tech.mrzeapple.ciphercodex.ui.components.CipherProgressBar
import tech.mrzeapple.ciphercodex.ui.theme.CipherCyan
import tech.mrzeapple.ciphercodex.ui.theme.CipherMuted
import tech.mrzeapple.ciphercodex.ui.theme.CipherStatic
import kotlin.math.roundToInt

@Composable
fun StatsScreen(onBack: (() -> Unit)? = null) {
    val vm: StatsViewModel = viewModel()
    val state by vm.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (onBack != null) 4.dp else 16.dp, top = 8.dp, end = 16.dp),
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = CipherCyan,
                    )
                }
            }
            CipherHeader(title = "STATS", modifier = Modifier.weight(1f))
        }

        if (!state.hasData) {
            StatsEmpty()
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("TODAY", formatShortDuration(state.todayMillis), Modifier.weight(1f))
                StatTile("STREAK", "${state.streakDays}D", Modifier.weight(1f))
                StatTile("PAGES/HR", state.pagesPerHour?.toString() ?: "—", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("ALL TIME", formatShortDuration(state.allTimeMillis), Modifier.weight(1f))
                StatTile("FINISHED", state.booksFinished.toString(), Modifier.weight(1f))
                StatTile(
                    "EST. LEFT",
                    state.estLeftMillis?.let { formatShortDuration(it) } ?: "—",
                    Modifier.weight(1f),
                )
            }
            if (state.estLeftTitle != null) {
                CipherCaption("EST. LEFT IN: ${state.estLeftTitle!!.uppercase()}")
            }

            // Progress ring (display only); the goal itself is set in Settings >
            // Reading, so it stays reachable even before any reading session.
            if (state.dailyGoalMinutes > 0) {
                val todayMin = (state.todayMillis / 60_000L).toInt()
                CipherPanel(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GoalRing(todayMin, state.dailyGoalMinutes)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            CipherCaption("DAILY GOAL")
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "$todayMin / ${state.dailyGoalMinutes} MIN TODAY",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (todayMin >= state.dailyGoalMinutes) {
                                Spacer(Modifier.height(4.dp))
                                CipherCaption("GOAL MET", color = CipherCyan)
                            }
                        }
                    }
                }
            }

            CipherPanel(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    CipherCaption("LAST 14 DAYS")
                    Spacer(Modifier.height(8.dp))
                    DailyBars(state.last14)
                }
            }

            CipherPanel(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    CipherCaption("ACTIVITY // ${state.weekMillis.let { formatShortDuration(it) }} THIS WEEK")
                    Spacer(Modifier.height(8.dp))
                    StreakHeatmap(state.heatmap)
                }
            }

            CipherPanel(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CipherCaption("BY BOOK")
                    state.perBook.forEach { book ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = book.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = formatShortDuration(book.millis),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CipherMuted,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            CipherProgressBar(book.percentage ?: 0f)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Circular today-vs-goal ring: static track under a cyan→magenta progress arc. */
@Composable
private fun GoalRing(todayMinutes: Int, goalMinutes: Int, modifier: Modifier = Modifier) {
    val progress = if (goalMinutes > 0) (todayMinutes.toFloat() / goalMinutes).coerceIn(0f, 1f) else 0f
    Box(modifier = modifier.size(88.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 8.dp.toPx()
            val d = size.minDimension - stroke
            val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
            val arc = Size(d, d)
            drawArc(CipherStatic, -90f, 360f, false, tl, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(CipherGradient, -90f, 360f * progress, false, tl, arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(
            text = "${(progress * 100).roundToInt()}%",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    CipherPanel(modifier = modifier) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            CipherCaption(label)
        }
    }
}

@Composable
private fun StatsEmpty() {
    val barColors = listOf(
        Color(0xFF00E5FF), Color(0xFF40B6E4), Color(0xFF8088C9),
        Color(0xFFBF59AE), Color(0xFFFF2A93),
    )
    val widths = listOf(56.dp, 40.dp, 52.dp, 28.dp, 44.dp)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            barColors.forEachIndexed { i, color ->
                Box(
                    Modifier
                        .width(widths[i])
                        .height(6.dp)
                        .background(color),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "NO READING DATA YET",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        CipherCaption("STATS BEGIN WITH YOUR FIRST SESSION")
    }
}
