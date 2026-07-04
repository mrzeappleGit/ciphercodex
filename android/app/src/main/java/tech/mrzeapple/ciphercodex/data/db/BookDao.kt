package tech.mrzeapple.ciphercodex.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastOpenedAt IS NULL, lastOpenedAt DESC, addedAt DESC")
    fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun bookById(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE digest = :digest")
    suspend fun bookByDigest(digest: String): BookEntity?

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Delete
    suspend fun delete(book: BookEntity)

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun progressFor(bookId: Long): ProgressEntity?

    @Query("SELECT * FROM progress")
    fun observeAllProgress(): Flow<List<ProgressEntity>>

    @Upsert
    suspend fun upsertProgress(progress: ProgressEntity)

    /** Stamps syncedAt only if the row is still the exact version that was pushed;
     *  a concurrent newer save (different updatedAt) stays dirty and is pushed later. */
    @Query("UPDATE progress SET syncedAt = :updatedAt WHERE bookId = :bookId AND updatedAt = :updatedAt")
    suspend fun markSynced(bookId: Long, updatedAt: Long)

    @Query("DELETE FROM progress WHERE bookId = :bookId")
    suspend fun deleteProgressFor(bookId: Long)

    @Query("SELECT * FROM progress WHERE syncedAt IS NULL OR syncedAt < updatedAt")
    suspend fun dirtyProgress(): List<ProgressEntity>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY spineIndex, charOffset")
    fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteBookmarksFor(bookId: Long)

    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY spineIndex, startChar")
    fun observeHighlights(bookId: Long): Flow<List<HighlightEntity>>

    /** All highlights across the library, newest first, joined with their book's
     *  title/author for the top-level KEPT screen. */
    @Query(
        "SELECT h.*, b.title AS bookTitle, b.author AS bookAuthor FROM highlights h " +
            "JOIN books b ON b.id = h.bookId ORDER BY h.createdAt DESC",
    )
    fun observeAllHighlights(): Flow<List<HighlightWithBook>>

    @Insert
    suspend fun insertHighlight(highlight: HighlightEntity): Long

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteHighlight(id: Long)

    @Query("DELETE FROM highlights WHERE bookId = :bookId")
    suspend fun deleteHighlightsFor(bookId: Long)

    @Query("SELECT * FROM collections ORDER BY name COLLATE NOCASE")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Insert
    suspend fun insertCollection(collection: CollectionEntity): Long

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteCollection(id: Long)

    @Query("SELECT * FROM book_collections")
    fun observeBookCollections(): Flow<List<BookCollectionCrossRef>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBookToCollection(ref: BookCollectionCrossRef)

    @Query("DELETE FROM book_collections WHERE collectionId = :collectionId AND bookId = :bookId")
    suspend fun removeBookFromCollection(collectionId: Long, bookId: Long)

    @Query("DELETE FROM book_collections WHERE collectionId = :collectionId")
    suspend fun deleteCollectionMembers(collectionId: Long)

    @Query("DELETE FROM book_collections WHERE bookId = :bookId")
    suspend fun deleteBookCollectionsFor(bookId: Long)
}
