package tech.mrzeapple.ciphercodex.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotMergeTest {

    private fun book(digest: String, updatedAt: Long, deleted: Int = 0, title: String = "t") =
        SnapBook(digest = digest, guid = "g-$digest", title = title, updatedAt = updatedAt, deleted = deleted)

    private fun hl(guid: String, updatedAt: Long, note: String? = null, deleted: Int = 0) =
        SnapHighlight(guid = guid, bookDigest = "d1", text = "x", note = note,
            updatedAt = updatedAt, deleted = deleted)

    @Test
    fun `convergence - merge is order-independent and unions entities`() {
        val a = Snapshot(deviceId = "A", books = listOf(book("d1", 10)),
            highlights = listOf(hl("h1", 11)))
        val b = Snapshot(deviceId = "B", books = listOf(book("d2", 20)),
            bookmarks = listOf(SnapBookmark(guid = "m1", bookDigest = "d2", updatedAt = 21)))
        val ab = SnapshotMerge.merge(listOf(a, b))
        val ba = SnapshotMerge.merge(listOf(b, a))
        assertEquals(ab, ba)
        assertEquals(setOf("d1", "d2"), ab.books.keys)
        assertEquals(setOf("h1"), ab.highlights.keys)
        assertEquals(setOf("m1"), ab.bookmarks.keys)
    }

    @Test
    fun `lww - newer updatedAt wins per entity`() {
        val older = hl("h1", 10, note = "old")
        val newer = hl("h1", 20, note = "new")
        val m = SnapshotMerge.merge(listOf(
            Snapshot(deviceId = "A", highlights = listOf(older)),
            Snapshot(deviceId = "B", highlights = listOf(newer)),
        ))
        assertEquals("new", m.highlights["h1"]!!.note)
    }

    @Test
    fun `books merge by digest not guid - no duplicates`() {
        // A and B imported the same file independently: same digest, different guids.
        val m = SnapshotMerge.merge(listOf(
            Snapshot(deviceId = "A", books = listOf(book("d1", 10).copy(guid = "guidA"))),
            Snapshot(deviceId = "B", books = listOf(book("d1", 20).copy(guid = "guidB"))),
        ))
        assertEquals(1, m.books.size)
        assertEquals("guidB", m.books["d1"]!!.guid) // newer record's row won
    }

    @Test
    fun `tombstone beats older edit and wins updatedAt ties`() {
        val edit = book("d1", 10, deleted = 0, title = "edited")
        val del = book("d1", 20, deleted = 1)
        assertEquals(1, SnapshotMerge.merge(listOf(
            Snapshot(deviceId = "A", books = listOf(edit)),
            Snapshot(deviceId = "B", books = listOf(del)),
        )).books["d1"]!!.deleted)
        // tie: deleted wins
        val tieEdit = book("d1", 20, deleted = 0)
        assertEquals(1, SnapshotMerge.merge(listOf(
            Snapshot(deviceId = "A", books = listOf(tieEdit)),
            Snapshot(deviceId = "B", books = listOf(del)),
        )).books["d1"]!!.deleted)
    }

    @Test
    fun `no resurrection - old live copy does not revive newer tombstone`() {
        val m = SnapshotMerge.merge(listOf(
            Snapshot(deviceId = "A", books = listOf(book("d1", 30, deleted = 1))),
            Snapshot(deviceId = "B", books = listOf(book("d1", 10, deleted = 0))),
        ))
        assertEquals(1, m.books["d1"]!!.deleted)
    }

    @Test
    fun `bookCollections keyed by collectionGuid plus bookDigest`() {
        val m = SnapshotMerge.merge(listOf(
            Snapshot(deviceId = "A", bookCollections = listOf(
                SnapBookCollection("c1", "d1", updatedAt = 1),
                SnapBookCollection("c1", "d2", updatedAt = 1))),
            Snapshot(deviceId = "B", bookCollections = listOf(
                SnapBookCollection("c1", "d1", deleted = 1, updatedAt = 5))),
        ))
        assertEquals(2, m.bookCollections.size)
        assertEquals(1, m.bookCollections[Pair("c1", "d1")]!!.deleted)
        assertEquals(0, m.bookCollections[Pair("c1", "d2")]!!.deleted)
    }

    @Test
    fun `wins - lww against local rows`() {
        assertTrue(SnapshotMerge.wins(20, 0, 10, 0))   // newer remote
        assertFalse(SnapshotMerge.wins(10, 0, 20, 0))  // never lower a newer local
        assertTrue(SnapshotMerge.wins(10, 1, 10, 0))   // tie: tombstone wins
        assertFalse(SnapshotMerge.wins(10, 0, 10, 1))  // tie: live does not beat tombstone
        assertFalse(SnapshotMerge.wins(10, 0, 10, 0))  // tie, both live: keep local
    }
}
