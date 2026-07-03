package tech.mrzeapple.ciphercodex.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
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
}
