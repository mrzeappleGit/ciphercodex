package tech.mrzeapple.ciphercodex.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkMergeTest {

    private fun nb(guid: String, updatedAt: Long, deleted: Int = 0, title: String = "t") =
        InkNotebook(guid = guid, title = title, updatedAt = updatedAt, deleted = deleted)

    private fun pg(guid: String, notebookGuid: String = "n1", updatedAt: Long = 1, deleted: Int = 0) =
        InkPage(guid = guid, notebookGuid = notebookGuid, updatedAt = updatedAt, deleted = deleted)

    private fun st(guid: String, pageGuid: String, updatedAt: Long, deleted: Int = 0) =
        InkStroke(guid = guid, pageGuid = pageGuid, updatedAt = updatedAt, deleted = deleted)

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
    fun `live strokes group by page, tombstoned strokes drop`() {
        val by = InkMerge.liveStrokesByPage(listOf(
            InkSnapshot(strokes = listOf(st("s1", "p1", 10), st("s2", "p1", 11, deleted = 1), st("s3", "p2", 12))),
        ))
        assertEquals(listOf("s1"), by["p1"]!!.map { it.guid })
        assertEquals(listOf("s3"), by["p2"]!!.map { it.guid })
    }

    @Test
    fun `stroke LWW picks newest copy before grouping`() {
        val by = InkMerge.liveStrokesByPage(listOf(
            InkSnapshot(strokes = listOf(st("s1", "p1", 10))),
            InkSnapshot(strokes = listOf(st("s1", "p1", 20, deleted = 1))),
        ))
        assertTrue(by["p1"].isNullOrEmpty()) // newest copy is a tombstone
    }

    @Test
    fun `contentStamp changes with strokes and is 0 when empty`() {
        assertEquals(0L, InkMerge.contentStamp(emptyList()))
        val one = InkMerge.contentStamp(listOf(st("s1", "p1", 10)))
        val two = InkMerge.contentStamp(listOf(st("s1", "p1", 10), st("s2", "p1", 10)))
        assertTrue(one != 0L && one != two)
        assertEquals(10L * 31 + 1, one)
    }
}
