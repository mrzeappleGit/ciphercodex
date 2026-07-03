package tech.mrzeapple.ciphercodex.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "books",
    indices = [Index(value = ["digest"], unique = true)],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String?,
    /** Absolute path of the imported copy under filesDir/books. */
    val filePath: String,
    /** kosync partial-MD5 document digest (binary matching method). */
    val digest: String,
    /** Absolute path of the cached cover image, if the EPUB had one. */
    val coverPath: String?,
    val sizeBytes: Long,
    val addedAt: Long,
    val lastOpenedAt: Long?,
)

/** One contiguous stretch of active reading of one book. Sessions shorter
 *  than 15s with no page turns are discarded, not stored. */
@Entity(tableName = "reading_sessions")
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val pagesTurned: Int,
    val startPercentage: Float,
    val endPercentage: Float,
)

@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val bookId: Long,
    val spineIndex: Int,
    val charOffset: Int,
    /** Whole-book progress, 0f..1f. */
    val percentage: Float,
    /** Local wall-clock millis of the last position change. */
    val updatedAt: Long,
    /** updatedAt value that was last pushed successfully; null or < updatedAt means dirty. */
    val syncedAt: Long?,
)
