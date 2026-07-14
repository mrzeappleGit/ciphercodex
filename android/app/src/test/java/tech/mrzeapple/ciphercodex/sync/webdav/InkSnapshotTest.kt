package tech.mrzeapple.ciphercodex.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkSnapshotTest {
    @Test
    fun `encodeMerged carries book arrays and ink arrays in one object`() {
        val base = Snapshot(deviceId = "dev-1", generatedAt = 5L,
            pageTexts = listOf(SnapPageText("pg", "hello", 1, 0, 9L)))
        val ink = InkSnapshot(deviceId = "dev-1",
            notebooks = listOf(InkNotebook("nb", "N", 1, 0, 2)),
            pages = listOf(InkPage("pg", "nb", 0, 0, 2)),
            strokes = listOf(InkStroke(guid = "s", pageGuid = "pg", tool = 0,
                baseWidth = 9f, pointsB64 = "AAAA", createdAt = 1, deleted = 0, updatedAt = 2)))
        val text = InkSnapshotJson.encodeMerged(base, ink)
        assertTrue(text.contains("\"points_b64\"")) // frozen wire key, underscore
        val books = SnapshotJson.decode(text)       // old-peer view still parses
        assertEquals("hello", books.pageTexts.single().text)
        val inkBack = InkSnapshotJson.decode(text)
        assertEquals("s", inkBack.strokes.single().guid)
        assertEquals("nb", inkBack.notebooks.single().guid)
    }
}
