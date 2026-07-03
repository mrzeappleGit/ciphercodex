package tech.mrzeapple.ciphercodex.data.stats

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.mrzeapple.ciphercodex.data.db.ReadingSessionEntity
import tech.mrzeapple.ciphercodex.data.db.StatsDao

private const val MIN_SESSION_MS = 15_000L
private const val IDLE_LIMIT_MS = 10 * 60_000L
private const val IDLE_TAIL_MS = 60_000L

/** Records one reading session at a time for one book. Restartable: after
 *  [flush] a new [onSessionStart] begins a fresh session. Thread-safe. */
class SessionRecorder(private val statsDao: StatsDao, private val bookId: Long) {

    private val lock = Any()
    private var active = false
    private var firstShow = true
    private var startedAt = 0L
    private var lastActivityAt = 0L
    private var pagesTurned = 0
    private var startPercentage = 0f
    private var lastPercentage = 0f

    fun onSessionStart(percentage: Float) = synchronized(lock) {
        if (active) return@synchronized
        active = true
        firstShow = true
        startedAt = System.currentTimeMillis()
        lastActivityAt = startedAt
        pagesTurned = 0
        startPercentage = percentage
        lastPercentage = percentage
    }

    fun onPageShown(percentage: Float) = synchronized(lock) {
        if (!active) return@synchronized
        lastPercentage = percentage
        // The first page shown after a start is the restored position, not a turn.
        if (firstShow) {
            firstShow = false
            return@synchronized
        }
        pagesTurned++
        lastActivityAt = System.currentTimeMillis()
    }

    /** Finalizes and persists the current session (if it meets the minimums);
     *  idempotent — a second flush without a new start is a no-op. */
    suspend fun flush(endPercentage: Float? = null) {
        val session: ReadingSessionEntity? = synchronized(lock) {
            if (!active) return
            active = false
            val now = System.currentTimeMillis()
            // Screen-on idling is not reading: trim ends far past the last turn.
            val end = (if (now - lastActivityAt > IDLE_LIMIT_MS) lastActivityAt + IDLE_TAIL_MS else now)
                .coerceAtLeast(startedAt)
            if (end - startedAt < MIN_SESSION_MS && pagesTurned == 0) {
                null
            } else {
                ReadingSessionEntity(
                    bookId = bookId,
                    startedAt = startedAt,
                    endedAt = end,
                    pagesTurned = pagesTurned,
                    startPercentage = startPercentage,
                    endPercentage = endPercentage ?: lastPercentage,
                )
            }
        }
        if (session != null) {
            withContext(Dispatchers.IO) { statsDao.insertSession(session) }
        }
    }
}
