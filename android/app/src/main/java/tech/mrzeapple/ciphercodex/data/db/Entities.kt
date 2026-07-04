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

/** A user-saved position within a book. Mirrors the firmware's BookmarkEntry;
 *  kept local (kosync carries progress only) until an annotation transport exists. */
@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["bookId"])],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val spineIndex: Int,
    val charOffset: Int,
    /** Whole-book percentage at capture, for display and ordering fallback. */
    val percentage: Float,
    /** Short snippet of the bookmarked page; may be blank. */
    val label: String,
    val createdAt: Long,
)

/** A highlighted range within a book, char offsets into the chapter's built
 *  text (stable across typography). Local-only until an annotation transport exists. */
@Entity(
    tableName = "highlights",
    indices = [Index(value = ["bookId", "spineIndex"])],
)
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val spineIndex: Int,
    val startChar: Int,
    val endChar: Int,
    val text: String,
    val createdAt: Long,
)

/** A user-created shelf. Books join via [BookCollectionCrossRef] (many-to-many). */
@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "book_collections",
    primaryKeys = ["collectionId", "bookId"],
    indices = [Index(value = ["bookId"])],
)
data class BookCollectionCrossRef(
    val collectionId: Long,
    val bookId: Long,
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
