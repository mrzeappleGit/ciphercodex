package tech.mrzeapple.ciphercodex.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import tech.mrzeapple.ciphercodex.CipherCodexApp
import tech.mrzeapple.ciphercodex.data.db.BookEntity
import tech.mrzeapple.ciphercodex.data.db.ProgressEntity
import tech.mrzeapple.ciphercodex.data.db.ReadingSessionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

private const val FINISHED_THRESHOLD = 0.98f
private const val STREAK_MIN_MS = 60_000L
private const val HEATMAP_WEEKS = 15

data class DayStat(val date: LocalDate, val millis: Long)

data class BookStat(
    val bookId: Long,
    val title: String,
    val millis: Long,
    val percentage: Float?,
)

data class StatsUiState(
    val hasData: Boolean = false,
    val todayMillis: Long = 0,
    val weekMillis: Long = 0,
    val allTimeMillis: Long = 0,
    val streakDays: Int = 0,
    val pagesPerHour: Int? = null,
    val booksFinished: Int = 0,
    val estLeftMillis: Long? = null,
    val estLeftTitle: String? = null,
    /** Oldest..today, exactly 14 entries. */
    val last14: List<DayStat> = emptyList(),
    /** Monday-aligned continuous range covering ~15 weeks up to today. */
    val heatmap: List<DayStat> = emptyList(),
    val perBook: List<BookStat> = emptyList(),
)

class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CipherCodexApp
    private val statsDao = app.database.statsDao()
    private val bookDao = app.database.bookDao()

    val state: StateFlow<StatsUiState> = combine(
        statsDao.observeAllSessions(),
        bookDao.observeBooks(),
        bookDao.observeAllProgress(),
    ) { sessions, books, progress ->
        derive(sessions, books, progress)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    private fun derive(
        sessions: List<ReadingSessionEntity>,
        books: List<BookEntity>,
        progress: List<ProgressEntity>,
    ): StatsUiState {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        fun dayOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

        // A session is bucketed on the day it ended; sessions crossing midnight
        // are short enough (10-minute idle guard) for that to be honest.
        val byDay = HashMap<LocalDate, Long>()
        var allTime = 0L
        for (s in sessions) {
            val dur = max(0L, s.endedAt - s.startedAt)
            allTime += dur
            val d = dayOf(s.endedAt)
            byDay[d] = (byDay[d] ?: 0L) + dur
        }

        val weekStart = today.minusDays(6)
        val weekMillis = (0..6L).sumOf { byDay[weekStart.plusDays(it)] ?: 0L }

        var streak = 0
        var cursor = if ((byDay[today] ?: 0L) >= STREAK_MIN_MS) today else today.minusDays(1)
        while ((byDay[cursor] ?: 0L) >= STREAK_MIN_MS) {
            streak++
            cursor = cursor.minusDays(1)
        }

        val weekAgoMs = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        var recentPages = 0
        var recentMs = 0L
        for (s in sessions) {
            if (s.endedAt >= weekAgoMs) {
                recentPages += s.pagesTurned
                recentMs += max(0L, s.endedAt - s.startedAt)
            }
        }
        val pagesPerHour =
            if (recentMs >= 5 * 60_000L && recentPages > 0)
                (recentPages * 3600_000.0 / recentMs).toInt()
            else null

        val progressByBook = progress.associateBy { it.bookId }
        val booksFinished = books.count {
            (progressByBook[it.id]?.percentage ?: 0f) >= FINISHED_THRESHOLD
        }

        // Estimated time left in the most recently opened unfinished book.
        var estLeftMillis: Long? = null
        var estLeftTitle: String? = null
        val current = books
            .filter { it.lastOpenedAt != null }
            .sortedByDescending { it.lastOpenedAt }
            .firstOrNull { (progressByBook[it.id]?.percentage ?: 0f) < FINISHED_THRESHOLD }
        if (current != null) {
            val own = sessions.filter { it.bookId == current.id }
            if (own.size >= 2) {
                var pctGained = 0.0
                var msSpent = 0L
                for (s in own) {
                    val gain = (s.endPercentage - s.startPercentage).toDouble()
                    if (gain > 0) {
                        pctGained += gain
                        msSpent += max(0L, s.endedAt - s.startedAt)
                    }
                }
                val rate = if (msSpent > 0) pctGained / msSpent else 0.0
                val remaining = 1.0 - (progressByBook[current.id]?.percentage ?: 0f)
                if (rate > 0 && remaining > 0) {
                    estLeftMillis = (remaining / rate).toLong()
                    estLeftTitle = current.title
                }
            }
        }

        val last14 = (13 downTo 0L).map { back ->
            val d = today.minusDays(back)
            DayStat(d, byDay[d] ?: 0L)
        }

        val heatStart = today
            .minusWeeks((HEATMAP_WEEKS - 1).toLong())
            .with(java.time.DayOfWeek.MONDAY)
        val heatmap = generateSequence(heatStart) { it.plusDays(1) }
            .takeWhile { !it.isAfter(today) }
            .map { DayStat(it, byDay[it] ?: 0L) }
            .toList()

        val titles = books.associate { it.id to it.title }
        val perBook = sessions
            .groupBy { it.bookId }
            .mapNotNull { (bookId, list) ->
                val title = titles[bookId] ?: return@mapNotNull null
                BookStat(
                    bookId = bookId,
                    title = title,
                    millis = list.sumOf { max(0L, it.endedAt - it.startedAt) },
                    percentage = progressByBook[bookId]?.percentage,
                )
            }
            .sortedByDescending { it.millis }

        return StatsUiState(
            hasData = sessions.isNotEmpty(),
            todayMillis = byDay[today] ?: 0L,
            weekMillis = weekMillis,
            allTimeMillis = allTime,
            streakDays = streak,
            pagesPerHour = pagesPerHour,
            booksFinished = booksFinished,
            estLeftMillis = estLeftMillis,
            estLeftTitle = estLeftTitle,
            last14 = last14,
            heatmap = heatmap,
            perBook = perBook,
        )
    }
}
