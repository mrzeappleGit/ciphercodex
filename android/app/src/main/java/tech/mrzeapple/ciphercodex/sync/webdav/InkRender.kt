package tech.mrzeapple.ciphercodex.sync.webdav

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File

/** Shared page-rasterization path: both the sync pipeline (downloaded ink) and the
 *  local editor (Task 4+) render strokes through here, so on-device and synced pages
 *  never drift in how ink is drawn. */
object InkRender {
    const val PAGE_W = 1404
    const val PAGE_H = 1872

    /** White 1404x1872 page, black pressure-width ink. Atomic: temp then rename.
     *  Returns whether the final file actually landed at [dest]; callers must not record a
     *  rendered stamp when this is false, or a failed rename leaves a page permanently
     *  blank (stamp matches forever, no image on disk). */
    fun renderPage(strokes: List<Pair<String, Float>>, dest: File): Boolean {
        val bmp = Bitmap.createBitmap(PAGE_W, PAGE_H, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                strokeCap = Paint.Cap.ROUND
                style = Paint.Style.STROKE
            }
            for (pair in strokes) {
                val (pointsB64, baseWidth) = pair
                val points = InkPoints.decode(pointsB64)
                for (seg in InkGeometry.strokeSegments(points, baseWidth)) {
                    paint.strokeWidth = seg.width
                    canvas.drawLine(seg.x0 * PAGE_W, seg.y0 * PAGE_H,
                        seg.x1 * PAGE_W, seg.y1 * PAGE_H, paint)
                }
            }
            // Unique per invocation: InkAuthor.renderPageNow (appScope, editor close) and
            // InkSync.apply's render pass (sync mutex) can both stamp-gate on the same stale
            // contentStamp and race to render the same page. A shared tmp name let one writer
            // truncate the other's in-progress file, and the surviving rename would still
            // record the stamp — sticking a corrupt PNG until the strokes next change.
            val tmp = File(dest.parentFile, "${dest.name}.${System.nanoTime()}.tmp")
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
