package tech.mrzeapple.ciphercodex.sync.recognition

import tech.mrzeapple.ciphercodex.sync.webdav.InkPoint

/** One rM2 stroke ready for recognition: decoded points + the stroke's epoch-ms creation time. */
data class RecStroke(val points: List<InkPoint>, val createdAt: Long)

/**
 * Groups strokes into text lines by y-interval overlap: a stroke joins a band when its
 * vertical extent overlaps the band's, expanded by a tolerance derived from the median
 * stroke height. Coordinates are page-normalized 0..1.
 * ponytail: greedy single-pass banding — side-by-side columns merge into one line; fine
 * for handwritten notes v1, revisit with x-gap splitting if column layouts matter.
 */
object InkLines {
    fun segment(strokes: List<RecStroke>): List<List<RecStroke>> {
        val inked = strokes.filter { it.points.isNotEmpty() }.sortedBy { it.createdAt }
        if (inked.isEmpty()) return emptyList()
        val heights = inked.map { s -> s.points.maxOf { it.y } - s.points.minOf { it.y } }.sorted()
        val tol = (heights[heights.size / 2] * 0.6f).coerceIn(0.006f, 0.04f)

        class Band(var top: Float, var bottom: Float, val members: MutableList<RecStroke>)
        val bands = mutableListOf<Band>()
        for (s in inked) {
            val top = s.points.minOf { it.y }
            val bottom = s.points.maxOf { it.y }
            val hit = bands.firstOrNull { it.top - tol <= bottom && top <= it.bottom + tol }
            if (hit != null) {
                hit.top = minOf(hit.top, top); hit.bottom = maxOf(hit.bottom, bottom)
                hit.members.add(s)
            } else bands.add(Band(top, bottom, mutableListOf(s)))
        }
        return bands.sortedBy { it.top }.map { it.members }
    }
}
