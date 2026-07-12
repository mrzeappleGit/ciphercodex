package tech.mrzeapple.ciphercodex.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotJsonTest {

    /** Shaped like a real rM2 snapshot: includes notebooks/pages/strokes arrays
     *  (points_b64 blobs) that Android must skip without error. */
    private val rm2Snapshot = """
    {"deviceId":"aabb01","generatedAt":1752300000000,
     "books":[{"digest":"d1","guid":"g-b1","title":"Dune","author":"Herbert","format":1,
               "addedAt":1,"lastOpenedAt":2,"deleted":0,"updatedAt":10}],
     "progress":[{"bookDigest":"d1","spineIndex":3,"charOffset":120,"percentage":0.25,"deleted":0,"updatedAt":11}],
     "bookmarks":[{"guid":"g-m1","bookDigest":"d1","spineIndex":1,"charOffset":5,"percentage":0.1,
                   "label":"start","createdAt":3,"deleted":0,"updatedAt":12}],
     "highlights":[{"guid":"g-h1","bookDigest":"d1","spineIndex":1,"startChar":5,"endChar":9,
                    "text":"fear","note":null,"colorId":0,"createdAt":4,"deleted":0,"updatedAt":13}],
     "collections":[{"guid":"g-c1","name":"SF","createdAt":5,"deleted":0,"updatedAt":14}],
     "bookCollections":[{"collectionGuid":"g-c1","bookDigest":"d1","deleted":0,"updatedAt":15}],
     "notebooks":[{"guid":"g-n1","title":"ink","createdAt":6,"deleted":0,"updatedAt":16}],
     "pages":[{"guid":"g-p1","notebookGuid":"g-n1","seq":0,"deleted":0,"updatedAt":17}],
     "strokes":[{"guid":"g-s1","pageGuid":"g-p1","tool":0,"baseWidth":2.0,"points_b64":"AAAA",
                 "createdAt":7,"deleted":0,"updatedAt":18}],
     "sessions":[{"guid":"g-r1","bookDigest":"d1","startedAt":8,"endedAt":9,"pagesTurned":4,
                  "startPercentage":0.0,"endPercentage":0.1,"deleted":0,"updatedAt":19}]}
    """.trimIndent()

    @Test
    fun `parses rm2 snapshot, skipping ink entities`() {
        val s = SnapshotJson.decode(rm2Snapshot)
        assertEquals("aabb01", s.deviceId)
        assertEquals(1, s.books.size)
        assertEquals("d1", s.books[0].digest)
        assertEquals(1, s.books[0].format)
        assertEquals(0.25f, s.progress[0].percentage, 1e-6f)
        assertEquals("g-m1", s.bookmarks[0].guid)
        assertEquals("fear", s.highlights[0].text)
        assertEquals("g-c1", s.bookCollections[0].collectionGuid)
        assertEquals(4, s.sessions[0].pagesTurned)
    }

    @Test
    fun `round-trips and never emits ink keys`() {
        val s = SnapshotJson.decode(rm2Snapshot)
        val out = SnapshotJson.encode(s.copy(deviceId = "android1"))
        val back = SnapshotJson.decode(out)
        assertEquals(s.books, back.books)
        assertEquals(s.progress, back.progress)
        assertEquals(s.highlights, back.highlights)
        assertTrue("must not emit ink arrays", !out.contains("strokes") && !out.contains("notebooks"))
    }

    @Test
    fun `missing arrays parse as empty`() {
        val s = SnapshotJson.decode("""{"deviceId":"x","generatedAt":0}""")
        assertTrue(s.books.isEmpty() && s.progress.isEmpty())
    }
}
