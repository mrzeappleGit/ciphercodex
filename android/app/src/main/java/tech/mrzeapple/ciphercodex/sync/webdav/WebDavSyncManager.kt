package tech.mrzeapple.ciphercodex.sync.webdav

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tech.mrzeapple.ciphercodex.data.LibraryRepository
import tech.mrzeapple.ciphercodex.data.db.AppDatabase
import tech.mrzeapple.ciphercodex.data.db.BookCollectionCrossRef
import tech.mrzeapple.ciphercodex.data.db.BookEntity
import tech.mrzeapple.ciphercodex.data.db.BookmarkEntity
import tech.mrzeapple.ciphercodex.data.db.CollectionEntity
import tech.mrzeapple.ciphercodex.data.db.HighlightEntity
import tech.mrzeapple.ciphercodex.data.db.PageTextEntity
import tech.mrzeapple.ciphercodex.data.db.ProgressEntity
import tech.mrzeapple.ciphercodex.data.db.ReadingSessionEntity
import tech.mrzeapple.ciphercodex.data.prefs.UserPrefs
import tech.mrzeapple.ciphercodex.sync.Guids
import tech.mrzeapple.ciphercodex.sync.recognition.HandwritingRecognizer
import tech.mrzeapple.ciphercodex.sync.recognition.RecognitionPass
import java.io.File

class WebDavSyncManager(
    private val prefs: UserPrefs,
    private val db: AppDatabase,
    private val repository: LibraryRepository,
    private val cacheDir: File,
    private val notebooksDir: File,
) {
    data class WebDavSummary(val booksUp: Int = 0, val booksDown: Int = 0, val entities: Int = 0,
                             val tombstones: Int = 0, val pagesRecognized: Int = 0,
                             val error: String? = null)

    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    suspend fun testConnection(url: String, user: String, pass: String): Result<Unit> =
        withContext(Dispatchers.IO) { WebDavClient(url, user, pass).test() }

    suspend fun syncIfDue(minIntervalMs: Long): WebDavSummary? {
        val s = prefs.current()
        if (s.webdavUrl.isEmpty()) return null
        if (System.currentTimeMillis() - s.webdavLastSyncAt < minIntervalMs) return null
        return syncNow()
    }

    suspend fun syncNow(): WebDavSummary = mutex.withLock {
        withContext(Dispatchers.IO) {
            _running.value = true
            try { doSync() } catch (e: Exception) {
                WebDavSummary(error = e.message ?: e.javaClass.simpleName)
            } finally { _running.value = false }
        }
    }

    private suspend fun doSync(): WebDavSummary {
        val settings = prefs.current()
        if (settings.webdavUrl.isEmpty()) return WebDavSummary(error = "not configured")
        val deviceId = prefs.deviceId()
        val dav = WebDavClient(settings.webdavUrl, settings.webdavUser, settings.webdavPass)
        val sync = db.syncDao()

        dav.mkcol("books/"); dav.mkcol("state/")

        // 1. Upload book files the endpoint lacks (live epubs with a local file).
        val remoteBooks = dav.list("books/").toSet()
        var booksUp = 0
        for (b in sync.allBooks()) {
            if (b.deleted || b.filePath.isEmpty()) continue
            val f = File(b.filePath)
            if (!f.exists() || "${b.digest}.epub" in remoteBooks) continue
            dav.putFile("books/${b.digest}.epub", f, "application/epub+zip")
            booksUp++
        }

        // 2. Pull every device snapshot (unparsable one aborts: better no sync than a partial merge).
        val stateTexts = dav.list("state/")
            .filter { it.endsWith(".json") }
            .map { dav.get("state/$it").decodeToString() }
        val snapshots = stateTexts.map { SnapshotJson.decode(it) }

        // 3. Merge + apply.
        val merged = SnapshotMerge.merge(snapshots)
        val result = applyMerged(merged)

        // 4. Fetch missing book files, attach.
        var booksDown = 0
        for (digest in result.needFiles) {
            if ("$digest.epub" !in remoteBooks) continue // not uploaded anywhere yet
            val tmp = File(cacheDir, "dav-$digest.epub")
            try {
                dav.getToFile("books/$digest.epub", tmp)
                if (repository.attachBookFile(digest, tmp)) booksDown++
            } finally { tmp.delete() }
        }

        // 5. Push our snapshot (full state incl. tombstones), atomically.
        val out = SnapshotJson.encode(exportSnapshot(deviceId))
        dav.put("state/$deviceId.json.tmp", out.encodeToByteArray())
        dav.move("state/$deviceId.json.tmp", "state/$deviceId.json")

        // 6. Ink pass (rM2 notebooks -> local viewer). Contained: never fails book sync.
        var pagesRecognized = 0
        var hw: HandwritingRecognizer? = null
        val inkError = try {
            val recognition = if (settings.handwritingRecognition) {
                try {
                    hw = HandwritingRecognizer()
                    if (hw!!.modelDownloaded()) RecognitionPass(hw!!::recognizeLine) else null
                } catch (t: Throwable) { null }  // recognition must never break sync
            } else null
            pagesRecognized = InkSync(db, notebooksDir, recognition).apply(stateTexts).pagesRecognized
            null
        } catch (e: Exception) {
            "notes: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            hw?.close()
        }

        prefs.setWebdavLastSyncAt(System.currentTimeMillis())
        return WebDavSummary(booksUp, booksDown, result.entities, result.tombstones,
            pagesRecognized = pagesRecognized, error = inkError)
    }

    private data class ApplyResult(val needFiles: List<String>, val entities: Int, val tombstones: Int)

    private suspend fun applyMerged(m: SnapshotMerge.Merged): ApplyResult {
        val sync = db.syncDao()
        var entities = 0; var tombstones = 0
        val needFiles = mutableListOf<String>()
        db.withTransaction {
            // --- books (by digest; epub only; filePath/coverPath stay local) ---
            val localBooks = sync.allBooks().associateBy { it.digest }.toMutableMap()
            for ((digest, r) in m.books) {
                if (r.format != 1) continue
                val local = localBooks[digest]
                if (local == null) {
                    if (r.deleted == 1) continue // nothing local to tombstone; don't import a corpse
                    val row = BookEntity(title = r.title, author = r.author, filePath = "",
                        digest = digest, coverPath = null, sizeBytes = 0,
                        addedAt = r.addedAt,
                        // rM firmware sends 0 as its never-opened sentinel; keep it out of the DB.
                        lastOpenedAt = r.lastOpenedAt?.takeIf { it != 0L },
                        guid = r.guid.ifEmpty { Guids.new() }, updatedAt = r.updatedAt, deleted = false)
                    sync.upsertBook(row)
                    localBooks[digest] = sync.allBooks().first { it.digest == digest }
                    needFiles.add(digest); entities++
                } else if (SnapshotMerge.wins(r.updatedAt, r.deleted, local.updatedAt, if (local.deleted) 1 else 0)) {
                    val nowDeleted = r.deleted == 1
                    sync.upsertBook(local.copy(title = r.title, author = r.author,
                        addedAt = r.addedAt,
                        // rM firmware sends 0 as its never-opened sentinel; keep it out of the DB.
                        lastOpenedAt = r.lastOpenedAt?.takeIf { it != 0L },
                        updatedAt = r.updatedAt, deleted = nowDeleted,
                        filePath = if (nowDeleted) "" else local.filePath,
                        coverPath = if (nowDeleted) null else local.coverPath))
                    if (nowDeleted && !local.deleted) {
                        if (local.filePath.isNotEmpty()) File(local.filePath).delete()
                        local.coverPath?.let { File(it).delete() }
                        tombstones++
                    } else entities++
                    // Live row still missing its file: re-queue the download; without this a
                    // remote reader bumping updatedAt each round starves this device forever.
                    if (!nowDeleted && local.filePath.isEmpty()) needFiles.add(digest)
                    localBooks[digest] = sync.allBooks().first { it.digest == digest }
                } else if (!local.deleted && local.filePath.isEmpty()) {
                    needFiles.add(digest) // row known, file still missing (earlier failed download)
                }
            }
            val bookIdByDigest = localBooks.mapValues { it.value.id }

            // --- progress (by book digest; load once — avoids an allProgress() scan per row) ---
            val progressByBookId = sync.allProgress().associateBy { it.bookId }
            for ((digest, r) in m.progress) {
                val bookId = bookIdByDigest[digest] ?: continue // missing parent: skip
                val local = progressByBookId[bookId]
                if (local == null || SnapshotMerge.wins(r.updatedAt, r.deleted, local.updatedAt, if (local.deleted) 1 else 0)) {
                    sync.upsertProgress(ProgressEntity(bookId = bookId, spineIndex = r.spineIndex,
                        charOffset = r.charOffset, percentage = r.percentage,
                        updatedAt = r.updatedAt, syncedAt = local?.syncedAt,
                        deleted = r.deleted == 1))
                    if (r.deleted == 1) tombstones++ else entities++
                }
            }

            // --- bookmarks / highlights / sessions (by guid, parent by digest) ---
            for ((guid, r) in m.bookmarks) {
                val bookId = bookIdByDigest[r.bookDigest] ?: continue
                val local = sync.bookmarkByGuid(guid)
                if (local == null || SnapshotMerge.wins(r.updatedAt, r.deleted, local.updatedAt, if (local.deleted) 1 else 0)) {
                    sync.upsertBookmark(BookmarkEntity(id = local?.id ?: 0, bookId = bookId,
                        spineIndex = r.spineIndex, charOffset = r.charOffset, percentage = r.percentage,
                        label = r.label, createdAt = r.createdAt,
                        guid = guid, updatedAt = r.updatedAt, deleted = r.deleted == 1))
                    if (r.deleted == 1) tombstones++ else entities++
                }
            }
            for ((guid, r) in m.highlights) {
                val bookId = bookIdByDigest[r.bookDigest] ?: continue
                val local = sync.highlightByGuid(guid)
                if (local == null || SnapshotMerge.wins(r.updatedAt, r.deleted, local.updatedAt, if (local.deleted) 1 else 0)) {
                    sync.upsertHighlight(HighlightEntity(id = local?.id ?: 0, bookId = bookId,
                        spineIndex = r.spineIndex, startChar = r.startChar, endChar = r.endChar,
                        text = r.text, createdAt = r.createdAt, note = r.note, colorId = r.colorId,
                        guid = guid, updatedAt = r.updatedAt, deleted = r.deleted == 1))
                    if (r.deleted == 1) tombstones++ else entities++
                }
            }
            for ((guid, r) in m.sessions) {
                val bookId = bookIdByDigest[r.bookDigest] ?: continue
                val local = sync.sessionByGuid(guid)
                if (local == null || SnapshotMerge.wins(r.updatedAt, r.deleted, local.updatedAt, if (local.deleted) 1 else 0)) {
                    sync.upsertSession(ReadingSessionEntity(id = local?.id ?: 0, bookId = bookId,
                        startedAt = r.startedAt, endedAt = r.endedAt, pagesTurned = r.pagesTurned,
                        startPercentage = r.startPercentage, endPercentage = r.endPercentage,
                        guid = guid, updatedAt = r.updatedAt, deleted = r.deleted == 1))
                    if (r.deleted == 1) tombstones++ else entities++
                }
            }

            // --- collections then memberships ---
            val collectionIdByGuid = HashMap<String, Long>()
            for ((guid, r) in m.collections) {
                val local = sync.collectionByGuid(guid)
                if (local == null || SnapshotMerge.wins(r.updatedAt, r.deleted, local.updatedAt, if (local.deleted) 1 else 0)) {
                    sync.upsertCollection(CollectionEntity(id = local?.id ?: 0, name = r.name,
                        createdAt = r.createdAt, guid = guid, updatedAt = r.updatedAt, deleted = r.deleted == 1))
                    if (r.deleted == 1) tombstones++ else entities++
                }
                (sync.collectionByGuid(guid))?.let { collectionIdByGuid[guid] = it.id }
            }
            for ((key, r) in m.bookCollections) {
                val collectionId = collectionIdByGuid[key.first]
                    ?: sync.collectionByGuid(key.first)?.id ?: continue
                val bookId = bookIdByDigest[key.second] ?: continue
                val local = sync.bookCollection(collectionId, bookId)
                if (local == null || SnapshotMerge.wins(r.updatedAt, r.deleted, local.updatedAt, if (local.deleted) 1 else 0)) {
                    sync.upsertBookCollection(BookCollectionCrossRef(collectionId = collectionId,
                        bookId = bookId, updatedAt = r.updatedAt, deleted = r.deleted == 1))
                    if (r.deleted == 1) tombstones++ else entities++
                }
            }

            // --- page texts (recognized handwriting; parent by pageGuid) ---
            val notesDao = db.notesDao()
            for (r in m.pageTexts) {
                val local = notesDao.pageText(r.pageGuid)
                if (r.deleted == 1) {
                    // Gated like every sibling: a stale remote tombstone must not destroy a
                    // fresher local recognition written after the last export.
                    if (local != null && SnapshotMerge.wins(r.updatedAt, 1, local.updatedAt, 0)) {
                        notesDao.deletePageText(r.pageGuid)
                        tombstones++
                    }
                    continue
                }
                if (notesDao.pageByGuid(r.pageGuid) == null) continue // missing parent: skip, converges next sync
                if (SnapshotMerge.wins(r.updatedAt, r.deleted, local?.updatedAt ?: -1, 0)) {
                    notesDao.upsertPageText(PageTextEntity(pageGuid = r.pageGuid, text = r.text,
                        sourceStamp = r.sourceStamp, updatedAt = r.updatedAt))
                    entities++
                }
            }
        }
        return ApplyResult(needFiles.distinct(), entities, tombstones)
    }

    private suspend fun exportSnapshot(deviceId: String): Snapshot {
        val sync = db.syncDao()
        val books = sync.allBooks()
        val digestById = books.associate { it.id to it.digest }
        fun d(deleted: Boolean) = if (deleted) 1 else 0
        return Snapshot(
            deviceId = deviceId,
            generatedAt = System.currentTimeMillis(),
            books = books.map { SnapBook(digest = it.digest, guid = it.guid, title = it.title,
                author = it.author, format = 1, addedAt = it.addedAt,
                lastOpenedAt = it.lastOpenedAt, deleted = d(it.deleted), updatedAt = it.updatedAt) },
            progress = sync.allProgress().mapNotNull { p ->
                val digest = digestById[p.bookId] ?: return@mapNotNull null
                SnapProgress(bookDigest = digest, spineIndex = p.spineIndex, charOffset = p.charOffset,
                    percentage = p.percentage, deleted = d(p.deleted), updatedAt = p.updatedAt) },
            bookmarks = sync.allBookmarks().mapNotNull { b ->
                val digest = digestById[b.bookId] ?: return@mapNotNull null
                SnapBookmark(guid = b.guid, bookDigest = digest, spineIndex = b.spineIndex,
                    charOffset = b.charOffset, percentage = b.percentage, label = b.label,
                    createdAt = b.createdAt, deleted = d(b.deleted), updatedAt = b.updatedAt) },
            highlights = sync.allHighlights().mapNotNull { h ->
                val digest = digestById[h.bookId] ?: return@mapNotNull null
                SnapHighlight(guid = h.guid, bookDigest = digest, spineIndex = h.spineIndex,
                    startChar = h.startChar, endChar = h.endChar, text = h.text, note = h.note,
                    colorId = h.colorId, createdAt = h.createdAt, deleted = d(h.deleted), updatedAt = h.updatedAt) },
            collections = sync.allCollections().map { c ->
                SnapCollection(guid = c.guid, name = c.name, createdAt = c.createdAt,
                    deleted = d(c.deleted), updatedAt = c.updatedAt) },
            bookCollections = run {
                val guidByCollectionId = sync.allCollections().associate { it.id to it.guid }
                sync.allBookCollections().mapNotNull { r ->
                    val cg = guidByCollectionId[r.collectionId] ?: return@mapNotNull null
                    val bd = digestById[r.bookId] ?: return@mapNotNull null
                    SnapBookCollection(collectionGuid = cg, bookDigest = bd,
                        deleted = d(r.deleted), updatedAt = r.updatedAt) } },
            sessions = sync.allSessions().mapNotNull { s ->
                val digest = digestById[s.bookId] ?: return@mapNotNull null
                SnapSession(guid = s.guid, bookDigest = digest, startedAt = s.startedAt,
                    endedAt = s.endedAt, pagesTurned = s.pagesTurned,
                    startPercentage = s.startPercentage, endPercentage = s.endPercentage,
                    deleted = d(s.deleted), updatedAt = s.updatedAt) },
            pageTexts = db.notesDao().allPageTexts().map {
                SnapPageText(it.pageGuid, it.text, it.sourceStamp, 0, it.updatedAt) },
        )
    }
}
