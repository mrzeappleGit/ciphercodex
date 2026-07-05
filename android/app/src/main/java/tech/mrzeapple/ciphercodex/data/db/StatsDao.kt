package tech.mrzeapple.ciphercodex.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StatsDao {
    @Insert
    suspend fun insertSession(session: ReadingSessionEntity): Long

    @Query("SELECT * FROM reading_sessions WHERE endedAt >= :since ORDER BY startedAt")
    fun observeSessionsSince(since: Long): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions ORDER BY startedAt")
    fun observeAllSessions(): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions WHERE bookId = :bookId ORDER BY startedAt")
    suspend fun sessionsFor(bookId: Long): List<ReadingSessionEntity>

    @Query("DELETE FROM reading_sessions WHERE bookId = :bookId")
    suspend fun deleteSessionsFor(bookId: Long)

    /** Total pages turned across all sessions that actually turned a page —
     *  the numerator of the personal reading-speed estimate. */
    @Query("SELECT COALESCE(SUM(pagesTurned), 0) FROM reading_sessions WHERE pagesTurned > 0")
    suspend fun totalPagesTurned(): Int

    /** Total reading milliseconds over the same sessions (the denominator). */
    @Query("SELECT COALESCE(SUM(endedAt - startedAt), 0) FROM reading_sessions WHERE pagesTurned > 0")
    suspend fun totalReadingMs(): Long
}
