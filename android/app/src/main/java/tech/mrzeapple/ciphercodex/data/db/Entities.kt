package tech.mrzeapple.ciphercodex.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import tech.mrzeapple.ciphercodex.sync.Guids

@Entity(
    tableName = "books",
    indices = [Index(value = ["digest"], unique = true), Index(value = ["guid"], unique = true)],
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
    @ColumnInfo(defaultValue = "''") val guid: String = Guids.new(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

/** One contiguous stretch of active reading of one book. Sessions shorter
 *  than 15s with no page turns are discarded, not stored. */
@Entity(tableName = "reading_sessions", indices = [Index(value = ["guid"], unique = true)])
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val pagesTurned: Int,
    val startPercentage: Float,
    val endPercentage: Float,
    @ColumnInfo(defaultValue = "''") val guid: String = Guids.new(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

/** A user-saved position within a book. Mirrors the firmware's BookmarkEntry;
 *  kept local (kosync carries progress only) until an annotation transport exists. */
@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["bookId"]), Index(value = ["guid"], unique = true)],
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
    @ColumnInfo(defaultValue = "''") val guid: String = Guids.new(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

/** A highlighted range within a book, char offsets into the chapter's built
 *  text (stable across typography). Local-only until an annotation transport exists. */
@Entity(
    tableName = "highlights",
    indices = [Index(value = ["bookId", "spineIndex"]), Index(value = ["guid"], unique = true)],
)
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val spineIndex: Int,
    val startChar: Int,
    val endChar: Int,
    val text: String,
    val createdAt: Long,
    /** Optional personal note attached to the highlight. */
    val note: String? = null,
    /** Index into the highlight-tint palette (0 = default cyan). */
    @ColumnInfo(defaultValue = "0") val colorId: Int = 0,
    @ColumnInfo(defaultValue = "''") val guid: String = Guids.new(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

/** A highlight joined with its book's title/author, for the KEPT screen. */
data class HighlightWithBook(
    @Embedded val highlight: HighlightEntity,
    val bookTitle: String,
    val bookAuthor: String?,
)

/** A user-created shelf. Books join via [BookCollectionCrossRef] (many-to-many). */
@Entity(tableName = "collections", indices = [Index(value = ["guid"], unique = true)])
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "''") val guid: String = Guids.new(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

@Entity(
    tableName = "book_collections",
    primaryKeys = ["collectionId", "bookId"],
    indices = [Index(value = ["bookId"])],
)
data class BookCollectionCrossRef(
    val collectionId: Long,
    val bookId: Long,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
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
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

/** rM2 notebook metadata mirrored from sync snapshots. Read-only on Android;
 *  ink tombstones hard-delete rows, so no deleted column. */
@Entity(tableName = "notebooks", indices = [Index(value = ["guid"], unique = true)])
data class NotebookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val guid: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "notebook_pages",
    indices = [Index(value = ["guid"], unique = true), Index("notebookGuid")],
)
data class NotebookPageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val guid: String,
    val notebookGuid: String,
    val seq: Int,
    val updatedAt: Long,
    /** max(stroke.updatedAt)*31 + liveStrokeCount at last render; -1 = never rendered. */
    val contentStamp: Long,
    val imagePath: String,
)

/** Recognized handwriting per rM2 page. Derived from strokes at sync time (sourceStamp =
 *  the contentStamp it was computed from); hard-deleted with its page like all ink data. */
@Entity(tableName = "page_texts")
data class PageTextEntity(
    @PrimaryKey val pageGuid: String,
    val text: String,
    val sourceStamp: Long,
    val updatedAt: Long,
)

/** One ink stroke, the merged local truth (mirrors the wire rows and the rM2's own
 *  strokes table). Tombstones are KEPT (deleted = 1) so the eraser travels on the
 *  wire and out-votes stale live copies — unlike notebooks/pages, which hard-delete. */
@Entity(tableName = "strokes", indices = [Index("pageGuid")])
data class StrokeEntity(
    @PrimaryKey val guid: String,
    val pageGuid: String,
    val tool: Int,
    val baseWidth: Float,
    val pointsB64: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Int,
)
