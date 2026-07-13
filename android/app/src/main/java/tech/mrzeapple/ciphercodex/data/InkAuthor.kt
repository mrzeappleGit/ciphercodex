package tech.mrzeapple.ciphercodex.data

import java.io.File
import tech.mrzeapple.ciphercodex.data.db.AppDatabase
import tech.mrzeapple.ciphercodex.data.db.NotebookEntity
import tech.mrzeapple.ciphercodex.data.db.NotebookPageEntity
import tech.mrzeapple.ciphercodex.data.db.StrokeEntity
import tech.mrzeapple.ciphercodex.sync.Guids
import tech.mrzeapple.ciphercodex.sync.webdav.InkPoint
import tech.mrzeapple.ciphercodex.sync.webdav.InkPoints
import tech.mrzeapple.ciphercodex.sync.webdav.InkRender

/** Local ink authoring: notebooks, pages, and strokes born on this device.
 *  Everything lands in Room with fresh guids/updatedAt and rides the next export. */
class InkAuthor(private val db: AppDatabase, private val notebooksDir: File) {
    private val dao get() = db.notesDao()

    suspend fun createNotebook(title: String): String {
        val now = System.currentTimeMillis()
        val guid = Guids.new()
        dao.upsertNotebook(NotebookEntity(guid = guid, title = title, createdAt = now, updatedAt = now))
        return guid
    }

    suspend fun createPage(notebookGuid: String): String {
        val now = System.currentTimeMillis()
        val guid = Guids.new()
        val seq = (dao.allPages().filter { it.notebookGuid == notebookGuid }
            .maxOfOrNull { it.seq } ?: -1) + 1
        dao.upsertPage(NotebookPageEntity(guid = guid, notebookGuid = notebookGuid,
            seq = seq, updatedAt = now, contentStamp = -1, imagePath = ""))
        return guid
    }

    /** Degenerate strokes (no points) are dropped, never stored. */
    suspend fun commitStroke(pageGuid: String, points: List<InkPoint>): StrokeEntity? {
        if (points.isEmpty()) return null
        val now = System.currentTimeMillis()
        val s = StrokeEntity(guid = Guids.new(), pageGuid = pageGuid, tool = 0,
            baseWidth = 9f, pointsB64 = InkPoints.encode(points),
            createdAt = now, updatedAt = now, deleted = 0)
        dao.upsertStroke(s)
        return s
    }

    suspend fun eraseStroke(guid: String): StrokeEntity? {
        val s = dao.strokeByGuid(guid) ?: return null
        if (s.deleted == 1) return null
        dao.upsertStroke(s.copy(deleted = 1, updatedAt = System.currentTimeMillis()))
        return s
    }

    /** Undo of an erase (or redo of an add): fresh updatedAt so it wins LWW. */
    suspend fun restoreStroke(s: StrokeEntity) {
        dao.upsertStroke(s.copy(deleted = 0, updatedAt = System.currentTimeMillis()))
    }

    /** Re-render this page's PNG + stamp from current Room strokes, so the viewer and
     *  thumbnails are correct immediately (and offline), not only after the next sync. */
    suspend fun renderPageNow(pageGuid: String) {
        val page = dao.pageByGuid(pageGuid) ?: return
        val live = dao.liveStrokesForPage(pageGuid)
        // Task 4 replaces with InkMerge.contentStamp(live) once that overload exists.
        val stamp = if (live.isEmpty()) 0L else live.maxOf { it.updatedAt } * 31 + live.size
        if (stamp == page.contentStamp && page.imagePath.isNotEmpty()) return
        notebooksDir.mkdirs()
        val dest = File(notebooksDir, "$pageGuid.png")
        if (InkRender.renderPage(live.map { it.pointsB64 to it.baseWidth }, dest)) {
            dao.setPageImage(pageGuid, dest.absolutePath, stamp)
        }
    }
}
