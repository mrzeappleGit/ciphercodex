package tech.mrzeapple.ciphercodex.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE deleted = 0 AND filePath != '' ORDER BY lastOpenedAt IS NULL, lastOpenedAt DESC, addedAt DESC")
    fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun bookById(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE digest = :digest")
    suspend fun bookByDigest(digest: String): BookEntity?

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("UPDATE books SET deleted = 1, updatedAt = :now WHERE id = :bookId")
    suspend fun softDeleteBook(bookId: Long, now: Long)

    @Query("SELECT * FROM progress WHERE bookId = :bookId AND deleted = 0")
    suspend fun progressFor(bookId: Long): ProgressEntity?

    @Query("SELECT * FROM progress WHERE deleted = 0")
    fun observeAllProgress(): Flow<List<ProgressEntity>>

    @Upsert
    suspend fun upsertProgress(progress: ProgressEntity)

    /** Stamps syncedAt only if the row is still the exact version that was pushed;
     *  a concurrent newer save (different updatedAt) stays dirty and is pushed later. */
    @Query("UPDATE progress SET syncedAt = :updatedAt WHERE bookId = :bookId AND updatedAt = :updatedAt")
    suspend fun markSynced(bookId: Long, updatedAt: Long)

    @Query("UPDATE progress SET deleted = 1, updatedAt = :now WHERE bookId = :bookId")
    suspend fun deleteProgressFor(bookId: Long, now: Long)

    @Query("SELECT * FROM progress WHERE (syncedAt IS NULL OR syncedAt < updatedAt) AND deleted = 0")
    suspend fun dirtyProgress(): List<ProgressEntity>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND deleted = 0 ORDER BY spineIndex, charOffset")
    fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Query("UPDATE bookmarks SET deleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun deleteBookmark(id: Long, now: Long)

    @Query("UPDATE bookmarks SET deleted = 1, updatedAt = :now WHERE bookId = :bookId")
    suspend fun deleteBookmarksFor(bookId: Long, now: Long)

    @Query("SELECT * FROM highlights WHERE bookId = :bookId AND deleted = 0 ORDER BY spineIndex, startChar")
    fun observeHighlights(bookId: Long): Flow<List<HighlightEntity>>

    /** All highlights across the library, newest first, joined with their book's
     *  title/author for the top-level KEPT screen. */
    @Query(
        "SELECT h.*, b.title AS bookTitle, b.author AS bookAuthor FROM highlights h " +
            "JOIN books b ON b.id = h.bookId WHERE h.deleted = 0 AND b.deleted = 0 ORDER BY h.createdAt DESC",
    )
    fun observeAllHighlights(): Flow<List<HighlightWithBook>>

    @Insert
    suspend fun insertHighlight(highlight: HighlightEntity): Long

    @Query("UPDATE highlights SET deleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun deleteHighlight(id: Long, now: Long)

    @Query("UPDATE highlights SET note = :note, colorId = :colorId, updatedAt = :now WHERE id = :id")
    suspend fun setHighlightAnnotation(id: Long, note: String?, colorId: Int, now: Long)

    @Query("UPDATE highlights SET deleted = 1, updatedAt = :now WHERE bookId = :bookId")
    suspend fun deleteHighlightsFor(bookId: Long, now: Long)

    @Query("SELECT * FROM collections WHERE deleted = 0 ORDER BY name COLLATE NOCASE")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Insert
    suspend fun insertCollection(collection: CollectionEntity): Long

    @Query("UPDATE collections SET deleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun deleteCollection(id: Long, now: Long)

    @Query("SELECT * FROM book_collections WHERE deleted = 0")
    fun observeBookCollections(): Flow<List<BookCollectionCrossRef>>

    @Upsert
    suspend fun addBookToCollection(ref: BookCollectionCrossRef)

    @Query("UPDATE book_collections SET deleted = 1, updatedAt = :now WHERE collectionId = :collectionId AND bookId = :bookId")
    suspend fun removeBookFromCollection(collectionId: Long, bookId: Long, now: Long)

    @Query("UPDATE book_collections SET deleted = 1, updatedAt = :now WHERE collectionId = :collectionId")
    suspend fun deleteCollectionMembers(collectionId: Long, now: Long)

    @Query("UPDATE book_collections SET deleted = 1, updatedAt = :now WHERE bookId = :bookId")
    suspend fun deleteBookCollectionsFor(bookId: Long, now: Long)
}
