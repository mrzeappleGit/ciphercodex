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
}
