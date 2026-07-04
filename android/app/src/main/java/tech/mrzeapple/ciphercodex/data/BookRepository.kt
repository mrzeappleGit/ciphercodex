package tech.mrzeapple.ciphercodex.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import tech.mrzeapple.ciphercodex.data.db.BookDao
import tech.mrzeapple.ciphercodex.data.db.BookEntity
import tech.mrzeapple.ciphercodex.data.db.ProgressEntity
import tech.mrzeapple.ciphercodex.data.db.StatsDao
import tech.mrzeapple.ciphercodex.epub.Epub
import tech.mrzeapple.ciphercodex.epub.EpubParseException
import tech.mrzeapple.ciphercodex.sync.Digests
import java.io.File

class BookRepository(
    private val context: Context,
    private val dao: BookDao,
    private val statsDao: StatsDao,
) : LibraryRepository {

    private val importMutex = Mutex()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private fun booksDir(): File = File(context.filesDir, "books").apply { mkdirs() }

    private fun coversDir(): File = File(context.filesDir, "covers").apply { mkdirs() }

    override fun observeLibrary(): Flow<List<BookWithProgress>> =
        combine(dao.observeBooks(), dao.observeAllProgress()) { books, progress ->
            val byBookId = progress.associateBy { it.bookId }
            books.map { book -> BookWithProgress(book, byBookId[book.id]?.percentage) }
        }

    override suspend fun importEpub(uri: Uri): ImportResult =
        // Serialize imports so sweepStaleImportTemps never races a live sibling
        // temp — both the library batch and the share/open-with intent path
        // import through here, potentially concurrently.
        importMutex.withLock { doImportEpub(uri) }

    override suspend fun importEpubFromUrl(url: String): ImportResult = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "opds-${System.nanoTime()}.download")
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ImportResult.Failed("Download failed: HTTP ${response.code}")
                }
                val stream = response.body?.byteStream()
                    ?: return@withContext ImportResult.Failed("Empty download")
                temp.outputStream().use { stream.copyTo(it) }
            }
            importEpub(Uri.fromFile(temp))
        } catch (e: Exception) {
            ImportResult.Failed("Download failed: ${e.readable()}")
        } finally {
            temp.delete()
        }
    }

    private suspend fun doImportEpub(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        sweepStaleImportTemps()
        val temp = File(booksDir(), "import-${System.nanoTime()}.tmp")
        try {
            val opened = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                    true
                } ?: false
            } catch (e: Exception) {
                return@withContext fail(temp, "Could not read the selected file: ${e.readable()}")
            }
            if (!opened) return@withContext fail(temp, "Could not open the selected file.")

            val digest = Digests.partialMd5(temp)
            dao.bookByDigest(digest)?.let { existing ->
                temp.delete()
                return@withContext ImportResult.Duplicate(existing.id)
            }

            val fallbackTitle = displayName(uri)
            val title: String
            val author: String?
            val coverBytes: ByteArray?
            try {
                Epub.open(temp).use { doc ->
                    title = doc.metadata.title?.ifBlank { null } ?: fallbackTitle
                    author = doc.metadata.author
                    coverBytes = doc.coverImageBytes()
                }
            } catch (e: EpubParseException) {
                return@withContext fail(temp, "Not a readable EPUB: ${e.readable()}")
            }

            val coverPath = coverBytes?.let { bytes ->
                runCatching {
                    File(coversDir(), "$digest.img").apply { writeBytes(bytes) }.absolutePath
                }.getOrNull()
            }

            val dest = File(booksDir(), "$digest.epub")
            moveFile(temp, dest)

            val id = dao.insert(
                BookEntity(
                    title = title,
                    author = author,
                    filePath = dest.absolutePath,
                    digest = digest,
                    coverPath = coverPath,
                    sizeBytes = dest.length(),
                    addedAt = System.currentTimeMillis(),
                    lastOpenedAt = null,
                )
            )
            ImportResult.Imported(id)
        } catch (e: Exception) {
            fail(temp, "Import failed: ${e.readable()}")
        }
    }

    override suspend fun deleteBook(bookId: Long) {
        withContext(Dispatchers.IO) {
            val book = dao.bookById(bookId) ?: return@withContext
            dao.deleteProgressFor(bookId)
            dao.deleteBookmarksFor(bookId)
            dao.deleteHighlightsFor(bookId)
            dao.deleteBookCollectionsFor(bookId)
            statsDao.deleteSessionsFor(bookId)
            dao.delete(book)
            File(book.filePath).delete()
            book.coverPath?.let { File(it).delete() }
        }
    }

    override suspend fun bookById(bookId: Long): BookEntity? =
        withContext(Dispatchers.IO) { dao.bookById(bookId) }

    override suspend fun markOpened(bookId: Long) {
        withContext(Dispatchers.IO) {
            val book = dao.bookById(bookId) ?: return@withContext
            dao.update(book.copy(lastOpenedAt = System.currentTimeMillis()))
        }
    }

    override suspend fun saveProgress(bookId: Long, spineIndex: Int, charOffset: Int, percentage: Float) {
        withContext(Dispatchers.IO) {
            // Preserve syncedAt so an unsynced row stays dirty and a synced row
            // becomes dirty again (syncedAt < the new updatedAt).
            val existing = dao.progressFor(bookId)
            dao.upsertProgress(
                ProgressEntity(
                    bookId = bookId,
                    spineIndex = spineIndex,
                    charOffset = charOffset,
                    percentage = percentage,
                    updatedAt = System.currentTimeMillis(),
                    syncedAt = existing?.syncedAt,
                )
            )
        }
    }

    /** Deletes temps orphaned by a process kill mid-import. Safe to run at
     *  import start because importMutex serializes all imports, so no live
     *  sibling temp can exist. */
    private fun sweepStaleImportTemps() {
        booksDir().listFiles()?.forEach { f ->
            if (f.name.startsWith("import-") && f.name.endsWith(".tmp")) f.delete()
        }
    }

    private fun fail(temp: File, message: String): ImportResult {
        temp.delete()
        return ImportResult.Failed(message)
    }

    private fun displayName(uri: Uri): String {
        val raw = runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull()
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: return "Untitled"
        val trimmed = if (raw.endsWith(".epub", ignoreCase = true)) raw.dropLast(5) else raw
        return trimmed.ifBlank { "Untitled" }
    }

    private fun moveFile(from: File, to: File) {
        if (to.exists()) to.delete()
        if (from.renameTo(to)) return
        from.copyTo(to, overwrite = true)
        from.delete()
    }

    private fun Exception.readable(): String = message ?: javaClass.simpleName
}
