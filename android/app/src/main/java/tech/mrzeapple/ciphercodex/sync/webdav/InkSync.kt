package tech.mrzeapple.ciphercodex.sync.webdav

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.room.withTransaction
import tech.mrzeapple.ciphercodex.data.db.AppDatabase
import tech.mrzeapple.ciphercodex.data.db.NotebookEntity
import tech.mrzeapple.ciphercodex.data.db.NotebookPageEntity
import java.io.File

/** Applies the ink arrays of downloaded snapshots: LWW metadata into Room,
 *  tombstones hard-deleted (row + PNG), changed pages re-rendered to PNG.
 *  Android never emits ink, so no tombstones are kept locally. */
class InkSync(private val db: AppDatabase, private val notebooksDir: File) {

    data class InkResult(val notebooks: Int, val pagesRendered: Int, val removed: Int)

    companion object {
        const val PAGE_W = 1404
        const val PAGE_H = 1872
    }

    suspend fun apply(snapshotTexts: List<String>): InkResult {
        // A snapshot whose ink arrays fail to decode is skipped (its book arrays
        // were already handled by the main pass); the rest still merge.
        val snaps = snapshotTexts.mapNotNull { runCatching { InkSnapshotJson.decode(it) }.getOrNull() }
        val notebooks = InkMerge.mergeNotebooks(snaps)
        val pages = InkMerge.mergePages(snaps)
        val strokesByPage = InkMerge.liveStrokesByPage(snaps)
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
                            .forEach { if (it.imagePath.isNotEmpty()) orphanFiles.add(it.imagePath) }
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
        }
        orphanFiles.forEach { File(it).delete() }

        // Render pass, outside the transaction: only pages whose content changed.
        notebooksDir.mkdirs()
        var rendered = 0
        for (page in dao.allPages()) {
            // A page absent from this pass's merged wire `pages` (missing snapshot, or its
            // ink arrays failed to decode) must not be treated as stroke-less and blanked —
            // only a page present with zero live strokes legitimately renders blank.
            if (!pages.containsKey(page.guid)) continue
            val strokes = strokesByPage[page.guid].orEmpty()
            val stamp = InkMerge.contentStamp(strokes)
            if (stamp == page.contentStamp && page.imagePath.isNotEmpty()) continue
            val dest = File(notebooksDir, "${page.guid}.png")
            if (renderPage(strokes, dest)) {
                dao.setPageImage(page.guid, dest.absolutePath, stamp)
                rendered++
            }
        }
        return InkResult(notebooks.count { it.value.deleted == 0 }, rendered, removed)
    }

    /** White 1404x1872 page, black pressure-width ink. Atomic: temp then rename.
     *  Returns whether the final file actually landed at [dest]; callers must not record a
     *  rendered stamp when this is false, or a failed rename leaves a page permanently
     *  blank (stamp matches forever, no image on disk). */
    private fun renderPage(strokes: List<InkStroke>, dest: File): Boolean {
        val bmp = Bitmap.createBitmap(PAGE_W, PAGE_H, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                strokeCap = Paint.Cap.ROUND
                style = Paint.Style.STROKE
            }
            for (stroke in strokes) {
                val points = InkPoints.decode(stroke.pointsB64)
                for (seg in InkGeometry.strokeSegments(points, stroke.baseWidth)) {
                    paint.strokeWidth = seg.width
                    canvas.drawLine(seg.x0 * PAGE_W, seg.y0 * PAGE_H,
                        seg.x1 * PAGE_W, seg.y1 * PAGE_H, paint)
                }
            }
            val tmp = File(dest.parentFile, "${dest.name}.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (tmp.renameTo(dest)) return true
            dest.delete()
            if (tmp.renameTo(dest)) return true
            tmp.delete()
            return false
        } finally {
            bmp.recycle()
        }
    }
}
