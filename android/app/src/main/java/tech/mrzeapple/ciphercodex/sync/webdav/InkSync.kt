package tech.mrzeapple.ciphercodex.sync.webdav

import androidx.room.withTransaction
import tech.mrzeapple.ciphercodex.data.db.AppDatabase
import tech.mrzeapple.ciphercodex.data.db.NotebookEntity
import tech.mrzeapple.ciphercodex.data.db.NotebookPageEntity
import tech.mrzeapple.ciphercodex.data.db.PageTextEntity
import tech.mrzeapple.ciphercodex.data.db.StrokeEntity
import tech.mrzeapple.ciphercodex.sync.recognition.RecStroke
import tech.mrzeapple.ciphercodex.sync.recognition.RecognitionPass
import java.io.File

/** Applies the ink arrays of downloaded snapshots: LWW metadata and strokes merge
 *  into Room (the merged truth), tombstones hard-deleted (row + PNG), changed pages
 *  re-rendered to PNG and re-recognized from Room. Android now authors and emits
 *  ink of its own, so local strokes ride the wire alongside the merge. */
class InkSync(
    private val db: AppDatabase,
    private val notebooksDir: File,
    private val recognition: RecognitionPass? = null,
) {

    data class InkResult(val notebooks: Int, val pagesRendered: Int, val removed: Int,
                          val pagesRecognized: Int = 0)

    suspend fun apply(snapshotTexts: List<String>): InkResult {
        // A snapshot whose ink arrays fail to decode is skipped (its book arrays
        // were already handled by the main pass); the rest still merge.
        val snaps = snapshotTexts.mapNotNull { runCatching { InkSnapshotJson.decode(it) }.getOrNull() }
        val notebooks = InkMerge.mergeNotebooks(snaps)
        val pages = InkMerge.mergePages(snaps)
        val strokes = InkMerge.mergeStrokes(snaps)
        val dao = db.notesDao()

        val orphanFiles = mutableListOf<String>()
        var removed = 0

        // Metadata transaction: tombstone hard-deletes + LWW metadata upserts.
        db.withTransaction {
            for ((guid, n) in notebooks) {
                val local = dao.notebookByGuid(guid)
                if (n.deleted == 1) {
                    if (local != null) {
                        dao.allPages().filter { it.notebookGuid == guid }
                            .forEach {
                                if (it.imagePath.isNotEmpty()) orphanFiles.add(it.imagePath)
                                dao.deletePageText(it.guid)
                                dao.deleteStrokesOf(it.guid)
                            }
                        dao.deletePagesOf(guid)
                        dao.deleteNotebook(guid)
                        removed++
                    }
                } else if (local == null || SnapshotMerge.wins(n.updatedAt, 0, local.updatedAt, 0)) {
                    dao.upsertNotebook(NotebookEntity(id = local?.id ?: 0, guid = guid,
                        title = n.title, createdAt = n.createdAt, updatedAt = n.updatedAt))
                }
            }
            for ((guid, p) in pages) {
                val local = dao.pageByGuid(guid)
                if (p.deleted == 1) {
                    if (local != null) {
                        if (local.imagePath.isNotEmpty()) orphanFiles.add(local.imagePath)
                        dao.deletePage(guid)
                        dao.deletePageText(guid)
                        dao.deleteStrokesOf(guid)
                        removed++
                    }
                } else if (dao.notebookByGuid(p.notebookGuid) == null) {
                    // parent notebook tombstoned or unknown: skip, never orphan
                } else if (local == null) {
                    dao.upsertPage(NotebookPageEntity(guid = guid, notebookGuid = p.notebookGuid,
                        seq = p.seq, updatedAt = p.updatedAt, contentStamp = -1, imagePath = ""))
                } else if (SnapshotMerge.wins(p.updatedAt, 0, local.updatedAt, 0)) {
                    dao.upsertPage(local.copy(notebookGuid = p.notebookGuid, seq = p.seq,
                        updatedAt = p.updatedAt))
                }
            }
            for ((guid, s) in strokes) {
                val local = dao.strokeByGuid(guid)
                if (local == null) {
                    if (s.deleted == 1) continue // nothing local to tombstone; don't import a corpse
                    if (dao.pageByGuid(s.pageGuid) == null) continue // missing parent: converges next sync
                    dao.upsertStroke(StrokeEntity(guid = guid, pageGuid = s.pageGuid, tool = s.tool,
                        baseWidth = s.baseWidth, pointsB64 = s.pointsB64, createdAt = s.createdAt,
                        updatedAt = s.updatedAt, deleted = 0))
                } else if (SnapshotMerge.wins(s.updatedAt, s.deleted, local.updatedAt, local.deleted)) {
                    dao.upsertStroke(local.copy(pageGuid = s.pageGuid, pointsB64 = s.pointsB64,
                        baseWidth = s.baseWidth, updatedAt = s.updatedAt, deleted = s.deleted))
                }
            }
        }
        orphanFiles.forEach { File(it).delete() }

        // Render pass, outside the transaction: only pages whose content changed.
        notebooksDir.mkdirs()
        var rendered = 0
        var recognized = 0
        for (page in dao.allPages()) {
            val live = dao.liveStrokesForPage(page.guid)
            val stamp = InkMerge.contentStamp(live)
            // Independent of the render check below: text must catch up even when the PNG
            // was already current (or vice versa), so this never gates on the render continue.
            val rec = recognition
            if (rec != null) {
                val existing = dao.pageText(page.guid)
                if (existing?.sourceStamp != stamp) {
                    // Contained: an ML Kit throw on this page must not abort the render loop
                    // for the remaining pages. Failure leaves this page's row untouched.
                    runCatching {
                        val text = if (live.isEmpty()) "" else rec.textFor(live.map {
                            RecStroke(InkPoints.decode(it.pointsB64), it.createdAt)
                        })
                        // Always upsert, even when text is empty: a fresh updatedAt must win
                        // LWW against a stale live row on the rM2, and empty text has to
                        // propagate on the wire as text="" — a hard delete is wire-invisible
                        // and lets the stale remote row resurrect on the next sync.
                        dao.upsertPageText(PageTextEntity(page.guid, text, stamp,
                            System.currentTimeMillis()))
                        recognized++
                    }
                }
            }
            if (stamp == page.contentStamp && page.imagePath.isNotEmpty()) continue
            val dest = File(notebooksDir, "${page.guid}.png")
            if (InkRender.renderPage(live.map { it.pointsB64 to it.baseWidth }, dest)) {
                dao.setPageImage(page.guid, dest.absolutePath, stamp)
                rendered++
            }
        }
        return InkResult(notebooks.count { it.value.deleted == 0 }, rendered, removed, recognized)
    }
}
