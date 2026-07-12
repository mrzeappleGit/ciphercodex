package tech.mrzeapple.ciphercodex.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** Sync-only access: reads INCLUDE tombstones (snapshots carry them). */
@Dao
interface SyncDao {
    @Query("SELECT * FROM books") suspend fun allBooks(): List<BookEntity>
    @Query("SELECT * FROM progress") suspend fun allProgress(): List<ProgressEntity>
    @Query("SELECT * FROM bookmarks") suspend fun allBookmarks(): List<BookmarkEntity>
    @Query("SELECT * FROM highlights") suspend fun allHighlights(): List<HighlightEntity>
    @Query("SELECT * FROM collections") suspend fun allCollections(): List<CollectionEntity>
    @Query("SELECT * FROM book_collections") suspend fun allBookCollections(): List<BookCollectionCrossRef>
    @Query("SELECT * FROM reading_sessions") suspend fun allSessions(): List<ReadingSessionEntity>

    @Query("SELECT * FROM bookmarks WHERE guid = :guid") suspend fun bookmarkByGuid(guid: String): BookmarkEntity?
    @Query("SELECT * FROM highlights WHERE guid = :guid") suspend fun highlightByGuid(guid: String): HighlightEntity?
    @Query("SELECT * FROM collections WHERE guid = :guid") suspend fun collectionByGuid(guid: String): CollectionEntity?
    @Query("SELECT * FROM reading_sessions WHERE guid = :guid") suspend fun sessionByGuid(guid: String): ReadingSessionEntity?
    @Query("SELECT * FROM book_collections WHERE collectionId = :collectionId AND bookId = :bookId")
    suspend fun bookCollection(collectionId: Long, bookId: Long): BookCollectionCrossRef?

    @Upsert suspend fun upsertBook(b: BookEntity)
    @Upsert suspend fun upsertProgress(p: ProgressEntity)
    @Upsert suspend fun upsertBookmark(b: BookmarkEntity)
    @Upsert suspend fun upsertHighlight(h: HighlightEntity)
    @Upsert suspend fun upsertCollection(c: CollectionEntity)
    @Upsert suspend fun upsertBookCollection(r: BookCollectionCrossRef)
    @Upsert suspend fun upsertSession(s: ReadingSessionEntity)

    @Query("UPDATE books SET filePath = :filePath, coverPath = :coverPath, sizeBytes = :sizeBytes WHERE digest = :digest")
    suspend fun attachBookFile(digest: String, filePath: String, coverPath: String?, sizeBytes: Long)
}
