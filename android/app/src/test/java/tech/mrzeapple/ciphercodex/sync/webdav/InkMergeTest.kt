package tech.mrzeapple.ciphercodex.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mrzeapple.ciphercodex.data.db.StrokeEntity

class InkMergeTest {

    private fun nb(guid: String, updatedAt: Long, deleted: Int = 0, title: String = "t") =
        InkNotebook(guid = guid, title = title, updatedAt = updatedAt, deleted = deleted)

    private fun pg(guid: String, notebookGuid: String = "n1", updatedAt: Long = 1, deleted: Int = 0) =
        InkPage(guid = guid, notebookGuid = notebookGuid, updatedAt = updatedAt, deleted = deleted)

    private fun st(guid: String, pageGuid: String, updatedAt: Long, deleted: Int = 0) =
        InkStroke(guid = guid, pageGuid = pageGuid, updatedAt = updatedAt, deleted = deleted)

    private fun snap(vararg strokes: InkStroke) = InkSnapshot(deviceId = "d", strokes = strokes.toList())
    private fun stroke(guid: String, deleted: Int, at: Long) =
        InkStroke(guid = guid, pageGuid = "pg", deleted = deleted, updatedAt = at)

    @Test
    fun `notebooks merge LWW and order-independent`() {
        val a = InkSnapshot(deviceId = "A", notebooks = listOf(nb("n1", 10, title = "old")))
        val b = InkSnapshot(deviceId = "B", notebooks = listOf(nb("n1", 20, title = "new")))
        assertEquals("new", InkMerge.mergeNotebooks(listOf(a, b))["n1"]!!.title)
        assertEquals(InkMerge.mergeNotebooks(listOf(a, b)), InkMerge.mergeNotebooks(listOf(b, a)))
    }

    @Test
    fun `newer tombstone survives merge`() {
        val m = InkMerge.mergeNotebooks(listOf(
            InkSnapshot(notebooks = listOf(nb("n1", 30, deleted = 1))),
            InkSnapshot(notebooks = listOf(nb("n1", 10, deleted = 0))),
        ))
        assertEquals(1, m["n1"]!!.deleted)
    }

    @Test
    fun `mergeStrokes keeps every guid's winner, tombstones included`() {
        // Was "live strokes group by page, tombstoned strokes drop" — grouping and
        // dropping tombstones both retired: the caller now persists tombstones to
        // Room so they can out-vote a stale live copy arriving later.
        val m = InkMerge.mergeStrokes(listOf(
            InkSnapshot(strokes = listOf(st("s1", "p1", 10), st("s2", "p1", 11, deleted = 1), st("s3", "p2", 12))),
        ))
        assertEquals(0, m.getValue("s1").deleted)
        assertEquals(1, m.getValue("s2").deleted)
        assertEquals(0, m.getValue("s3").deleted)
    }

    @Test
    fun `mergeStrokes stroke LWW picks newest copy`() {
        // Was "stroke LWW picks newest copy before grouping" — no grouping left,
        // but the newest-copy-wins intent (a tombstone can beat an older live copy)
        // still holds.
        val m = InkMerge.mergeStrokes(listOf(
            InkSnapshot(strokes = listOf(st("s1", "p1", 10))),
            InkSnapshot(strokes = listOf(st("s1", "p1", 20, deleted = 1))),
        ))
        assertTrue(m.getValue("s1").deleted == 1)
    }

    @Test
    fun `mergeStrokes keeps tombstone winners`() {
        val live = snap(stroke("s1", 0, 100))
        val tomb = snap(stroke("s1", 1, 200))
        val m = InkMerge.mergeStrokes(listOf(live, tomb))
        assertEquals(1, m.getValue("s1").deleted)
        // tie -> tombstone wins (frozen rule)
        val m2 = InkMerge.mergeStrokes(listOf(snap(stroke("s2", 0, 300)), snap(stroke("s2", 1, 300))))
        assertEquals(1, m2.getValue("s2").deleted)
    }

    @Test
    fun `contentStamp changes with strokes and is 0 when empty`() {
        assertEquals(0L, InkMerge.contentStamp(emptyList<InkStroke>()))
        val one = InkMerge.contentStamp(listOf(st("s1", "p1", 10)))
        val two = InkMerge.contentStamp(listOf(st("s1", "p1", 10), st("s2", "p1", 10)))
        assertTrue(one != 0L && one != two)
        assertEquals(10L * 31 + 1, one)
    }

    @Test
    fun `entity contentStamp matches wire contentStamp for identical values`() {
        val wire = listOf(
            InkStroke(guid = "a", pageGuid = "pg", updatedAt = 500),
            InkStroke(guid = "b", pageGuid = "pg", updatedAt = 900),
        )
        val entities = listOf(
            StrokeEntity("a", "pg", 0, 9f, "", 0, 500, 0),
            StrokeEntity("b", "pg", 0, 9f, "", 0, 900, 0),
        )
        assertEquals(InkMerge.contentStamp(wire), InkMerge.contentStamp(entities))
        assertEquals(0L, InkMerge.contentStamp(emptyList<StrokeEntity>()))
    }
}
