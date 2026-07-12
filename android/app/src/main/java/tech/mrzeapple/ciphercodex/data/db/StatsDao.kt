package tech.mrzeapple.ciphercodex.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StatsDao {
    @Insert
    suspend fun insertSession(session: ReadingSessionEntity): Long

    @Query("SELECT * FROM reading_sessions WHERE endedAt >= :since AND deleted = 0 ORDER BY startedAt")
    fun observeSessionsSince(since: Long): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions WHERE deleted = 0 ORDER BY startedAt")
    fun observeAllSessions(): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions WHERE bookId = :bookId AND deleted = 0 ORDER BY startedAt")
    suspend fun sessionsFor(bookId: Long): List<ReadingSessionEntity>

    @Query("UPDATE reading_sessions SET deleted = 1, updatedAt = :now WHERE bookId = :bookId")
    suspend fun deleteSessionsFor(bookId: Long, now: Long)

    /** Total pages turned across all sessions that actually turned a page —
     *  the numerator of the personal reading-speed estimate. */
    @Query("SELECT COALESCE(SUM(pagesTurned), 0) FROM reading_sessions WHERE pagesTurned > 0 AND deleted = 0")
    suspend fun totalPagesTurned(): Int

    /** Total reading milliseconds over the same sessions (the denominator). */
    @Query("SELECT COALESCE(SUM(endedAt - startedAt), 0) FROM reading_sessions WHERE pagesTurned > 0 AND deleted = 0")
    suspend fun totalReadingMs(): Long
}
