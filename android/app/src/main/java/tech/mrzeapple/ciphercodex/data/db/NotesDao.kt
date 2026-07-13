package tech.mrzeapple.ciphercodex.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Notebook metadata access. UI observes; the sync ink pass writes. */
@Dao
interface NotesDao {
    @Query("SELECT * FROM notebooks ORDER BY updatedAt DESC")
    fun observeNotebooks(): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebook_pages ORDER BY seq")
    fun observeAllPages(): Flow<List<NotebookPageEntity>>

    @Query("SELECT * FROM notebooks") suspend fun allNotebooks(): List<NotebookEntity>
    @Query("SELECT * FROM notebook_pages") suspend fun allPages(): List<NotebookPageEntity>
    @Query("SELECT * FROM notebooks WHERE guid = :guid") suspend fun notebookByGuid(guid: String): NotebookEntity?
    @Query("SELECT * FROM notebook_pages WHERE guid = :guid") suspend fun pageByGuid(guid: String): NotebookPageEntity?

    @Upsert suspend fun upsertNotebook(n: NotebookEntity)
    @Upsert suspend fun upsertPage(p: NotebookPageEntity)

    @Query("UPDATE notebook_pages SET imagePath = :imagePath, contentStamp = :contentStamp WHERE guid = :guid")
    suspend fun setPageImage(guid: String, imagePath: String, contentStamp: Long)

    @Query("DELETE FROM notebooks WHERE guid = :guid") suspend fun deleteNotebook(guid: String)
    @Query("DELETE FROM notebook_pages WHERE guid = :guid") suspend fun deletePage(guid: String)
    @Query("DELETE FROM notebook_pages WHERE notebookGuid = :notebookGuid")
    suspend fun deletePagesOf(notebookGuid: String)

    @Query("SELECT * FROM page_texts WHERE pageGuid = :guid")
    suspend fun pageText(guid: String): PageTextEntity?

    @Query("SELECT * FROM page_texts")
    suspend fun allPageTexts(): List<PageTextEntity>

    @Query("SELECT * FROM page_texts")
    fun observeAllPageTexts(): Flow<List<PageTextEntity>>

    @Upsert
    suspend fun upsertPageText(t: PageTextEntity)

    @Query("DELETE FROM page_texts WHERE pageGuid = :pageGuid")
    suspend fun deletePageText(pageGuid: String)
}
