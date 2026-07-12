# WebDAV Sync — Android App Implementation Plan (slices A1–A3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Android app syncs books, progress, bookmarks, highlights, collections, and reading sessions with the other CipherCodex devices through the self-hosted WebDAV endpoint, implementing the frozen rM2 Phase 3 contract.

**Architecture:** Room v6→v7 adds guid/updatedAt/deleted (soft-delete tombstones) to synced tables. A pure-Kotlin LWW merge core (JVM unit-tested) merges per-device snapshot JSONs pulled from `state/`; a sync manager applies the merged state to Room in dependency order, unions book files by digest under `books/`, and pushes this device's full snapshot. OkHttp provides the WebDAV verbs.

**Tech Stack:** Kotlin, Room, OkHttp (custom methods), kotlinx-serialization-json (already a dependency), DataStore, WorkManager, JUnit4 JVM unit tests.

## Global Constraints

- Wire contract is FROZEN: `remarkable2-os/docs/phase3b-contracts.md`. Field names exactly as written there (camelCase; `deleted` is int 0/1; strokes key is `points_b64`). Never emit or persist `notebooks`/`pages`/`strokes`.
- Books merge by `digest` (kosync partialMD5), NOT guid. `filePath`/`coverPath`/`sizeBytes`/`syncedAt` are device-local, never synced.
- PDF books are skipped entirely on Android (no row, no download). Android emits `format: "epub"` for all its books.
- LWW: remote record applies only if `updatedAt` greater than local (or local absent); `deleted=1` wins ties. Never lower a newer local value. Missing-parent records are skipped, never inserted as orphans.
- Room migration SQL must EXACTLY match Room's generated schema (v4→v5 lesson: mismatch = runtime crash). Migration-added columns need matching `@ColumnInfo(defaultValue = ...)`.
- Package `tech.mrzeapple.ciphercodex`; manual DI via `CipherCodexApp`; all repo work on `Dispatchers.IO`.
- Endpoint for live tests: `https://kosync.cph.gg/ccx/` (dufs, Basic auth, user `ccx`) — use a scratch base path, never the real `books/`/`state/`.
- Creds stored plaintext in DataStore like kosync creds; never log the password.

---

### Task 1: Room v7 — guid/updatedAt/deleted columns + migration

**Files:**
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/data/db/Entities.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/data/db/AppDatabase.kt`
- Create: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/Guids.kt`

**Interfaces:**
- Produces: every synced entity gains `guid: String`, `updatedAt: Long`, `deleted: Boolean` (progress: `deleted` only; book_collections: `updatedAt`+`deleted` only — its contract identity is (collectionGuid, bookDigest), no guid). `Guids.new(): String` = UUIDv4 hex, no dashes.

- [ ] **Step 1: Create Guids helper**

```kotlin
package tech.mrzeapple.ciphercodex.sync

import java.util.UUID

object Guids {
    /** Cross-device row identity: UUIDv4 hex, dashes stripped (contract format). */
    fun new(): String = UUID.randomUUID().toString().replace("-", "")
}
```

- [ ] **Step 2: Add sync columns to entities**

In `Entities.kt`, extend each entity. New columns get Kotlin defaults so every existing construction site keeps compiling, and `@ColumnInfo(defaultValue=...)` matching the migration SQL exactly.

`BookEntity` — add to indices `Index(value = ["guid"], unique = true)` and to the class:
```kotlin
    @ColumnInfo(defaultValue = "''") val guid: String = Guids.new(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
```

`ReadingSessionEntity`, `BookmarkEntity`, `HighlightEntity`, `CollectionEntity` — same three lines each (same defaults). Add `indices = [Index(value = ["guid"], unique = true)]` to `CollectionEntity` and `ReadingSessionEntity` (`@Entity(tableName = "collections", indices = [...])`); for `BookmarkEntity` and `HighlightEntity` APPEND the guid index to their existing `indices` list.

`BookCollectionCrossRef` — add only:
```kotlin
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
```

`ProgressEntity` — add only:
```kotlin
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
```

Import `tech.mrzeapple.ciphercodex.sync.Guids` and `androidx.room.Index` as needed.

- [ ] **Step 3: Bump version and write MIGRATION_6_7**

In `AppDatabase.kt`: `version = 7`, add `MIGRATION_6_7` to companion and to `.addMigrations(...)`:

```kotlin
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val nowMs = System.currentTimeMillis()
        // guid-carrying tables: (table, updatedAt backfill expression)
        listOf(
            "books" to "COALESCE(lastOpenedAt, addedAt)",
            "bookmarks" to "createdAt",
            "highlights" to "createdAt",
            "collections" to "createdAt",
            "reading_sessions" to "endedAt",
        ).forEach { (t, backfill) ->
            db.execSQL("ALTER TABLE `$t` ADD COLUMN `guid` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `$t` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `$t` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE `$t` SET guid = lower(hex(randomblob(16))), updatedAt = $backfill")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_${t}_guid` ON `$t` (`guid`)")
        }
        db.execSQL("ALTER TABLE `book_collections` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `book_collections` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `book_collections` SET updatedAt = $nowMs")
        db.execSQL("ALTER TABLE `progress` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
    }
}
```

- [ ] **Step 4: Build + existing tests**

Run (from `android/`): `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (grep output for it — piping to tail masks the exit code). Migration correctness is device-verified in Task 8 via the release-upgrade recipe.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/tech/mrzeapple/ciphercodex/data/db/ android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/Guids.kt
git commit -m "feat(sync): Room v7 — guid/updatedAt/deleted on synced tables"
```

---

### Task 2: Soft deletes + tombstone-filtered reads

**Files:**
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/data/db/BookDao.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/data/db/StatsDao.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/data/BookRepository.kt`
- Modify: callers of changed DAO methods (LibraryViewModel/ReaderViewModel/KeptViewModel — compile errors will point at them; most signatures don't change)

**Interfaces:**
- Consumes: Task 1 columns.
- Produces: all live reads exclude tombstones; every delete is `UPDATE ... SET deleted=1, updatedAt=:now`; re-importing a tombstoned digest resurrects the row. `BookDao.softDeleteBook(bookId, now)` etc. as below.

- [ ] **Step 1: Filter live reads in BookDao**

Add `WHERE deleted = 0` (or `AND deleted = 0`) to: `observeBooks` (also add `AND filePath != ''` — a merged book row awaiting its file download must not appear openable), `progressFor`, `observeAllProgress`, `dirtyProgress`, `observeBookmarks`, `observeHighlights`, `observeCollections`, `observeBookCollections`, and both sides of the `observeAllHighlights` JOIN (`WHERE h.deleted = 0 AND b.deleted = 0`). E.g.:

```kotlin
@Query("SELECT * FROM books WHERE deleted = 0 AND filePath != '' ORDER BY lastOpenedAt IS NULL, lastOpenedAt DESC, addedAt DESC")
fun observeBooks(): Flow<List<BookEntity>>
```

`bookById`/`bookByDigest` stay unfiltered (import + sync need tombstones).

- [ ] **Step 2: Replace hard deletes with soft deletes in BookDao**

Replace the bodies (keep names so callers mostly compile; each gains a `now: Long` parameter):

```kotlin
@Query("UPDATE books SET deleted = 1, updatedAt = :now WHERE id = :bookId")
suspend fun softDeleteBook(bookId: Long, now: Long)

@Query("UPDATE progress SET deleted = 1, updatedAt = :now WHERE bookId = :bookId")
suspend fun deleteProgressFor(bookId: Long, now: Long)

@Query("UPDATE bookmarks SET deleted = 1, updatedAt = :now WHERE id = :id")
suspend fun deleteBookmark(id: Long, now: Long)

@Query("UPDATE bookmarks SET deleted = 1, updatedAt = :now WHERE bookId = :bookId")
suspend fun deleteBookmarksFor(bookId: Long, now: Long)

@Query("UPDATE highlights SET deleted = 1, updatedAt = :now WHERE id = :id")
suspend fun deleteHighlight(id: Long, now: Long)

@Query("UPDATE highlights SET deleted = 1, updatedAt = :now WHERE bookId = :bookId")
suspend fun deleteHighlightsFor(bookId: Long, now: Long)

@Query("UPDATE collections SET deleted = 1, updatedAt = :now WHERE id = :id")
suspend fun deleteCollection(id: Long, now: Long)

@Query("UPDATE book_collections SET deleted = 1, updatedAt = :now WHERE collectionId = :collectionId AND bookId = :bookId")
suspend fun removeBookFromCollection(collectionId: Long, bookId: Long, now: Long)

@Query("UPDATE book_collections SET deleted = 1, updatedAt = :now WHERE collectionId = :collectionId")
suspend fun deleteCollectionMembers(collectionId: Long, now: Long)

@Query("UPDATE book_collections SET deleted = 1, updatedAt = :now WHERE bookId = :bookId")
suspend fun deleteBookCollectionsFor(bookId: Long, now: Long)
```

Delete the `@Delete suspend fun delete(book: BookEntity)` method. Shelf re-add must clear a tombstone — replace `addBookToCollection`'s `@Insert(onConflict = IGNORE)` with:

```kotlin
@Upsert
suspend fun addBookToCollection(ref: BookCollectionCrossRef)
```
(callers construct `BookCollectionCrossRef(collectionId, bookId)` — the entity defaults stamp `updatedAt = now, deleted = false`, which both re-adds and fresh-adds want).

Mutations that edit rows must bump `updatedAt`:
```kotlin
@Query("UPDATE highlights SET note = :note, colorId = :colorId, updatedAt = :now WHERE id = :id")
suspend fun setHighlightAnnotation(id: Long, note: String?, colorId: Int, now: Long)
```

- [ ] **Step 3: StatsDao tombstone filters**

Add `deleted = 0` to `observeSessionsSince`, `observeAllSessions`, `sessionsFor`, `totalPagesTurned`, `totalReadingMs` (e.g. `WHERE pagesTurned > 0 AND deleted = 0`). Replace `deleteSessionsFor` with:

```kotlin
@Query("UPDATE reading_sessions SET deleted = 1, updatedAt = :now WHERE bookId = :bookId")
suspend fun deleteSessionsFor(bookId: Long, now: Long)
```

- [ ] **Step 4: Repository cascade + resurrect-on-reimport**

`BookRepository.deleteBook` becomes (files still physically deleted; rows tombstoned):

```kotlin
override suspend fun deleteBook(bookId: Long) {
    withContext(Dispatchers.IO) {
        val book = dao.bookById(bookId) ?: return@withContext
        val now = System.currentTimeMillis()
        dao.deleteProgressFor(bookId, now)
        dao.deleteBookmarksFor(bookId, now)
        dao.deleteHighlightsFor(bookId, now)
        dao.deleteBookCollectionsFor(bookId, now)
        statsDao.deleteSessionsFor(bookId, now)
        dao.softDeleteBook(bookId, now)
        File(book.filePath).delete()
        book.coverPath?.let { File(it).delete() }
    }
}
```

In `doImportEpub`, the duplicate check must resurrect a tombstoned or file-less row instead of bailing:

```kotlin
val digest = Digests.partialMd5(temp)
dao.bookByDigest(digest)?.let { existing ->
    if (!existing.deleted && existing.filePath.isNotEmpty() && File(existing.filePath).exists()) {
        temp.delete()
        return@withContext ImportResult.Duplicate(existing.id)
    }
    // tombstoned or metadata-only row: re-attach the file below and revive it
}
```
…and after `moveFile(temp, dest)`, when `existing != null`, `dao.update(existing.copy(filePath = dest.absolutePath, coverPath = coverPath ?: existing.coverPath, sizeBytes = dest.length(), deleted = false, updatedAt = System.currentTimeMillis()))` and return `ImportResult.Imported(existing.id)`; otherwise insert as today. Restructure with a local `val existing = dao.bookByDigest(digest)` before the early-return check.

`markOpened` bumps LWW: `dao.update(book.copy(lastOpenedAt = now, updatedAt = now))`.

- [ ] **Step 5: Fix call sites, build, existing tests**

Callers of `deleteBookmark`/`deleteHighlight`/`deleteCollection`/`removeBookFromCollection`/`setHighlightAnnotation` (Reader/Library/Kept ViewModels) now pass `System.currentTimeMillis()`. Compile errors enumerate them.

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/tech/mrzeapple/ciphercodex/
git commit -m "feat(sync): soft-delete tombstones + deleted=0 reads"
```

---

### Task 3: Snapshot models + JSON (pure Kotlin, TDD)

**Files:**
- Create: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/Snapshot.kt`
- Test: `android/app/src/test/java/tech/mrzeapple/ciphercodex/sync/webdav/SnapshotJsonTest.kt`

**Interfaces:**
- Produces: `Snapshot` + `Snap*` data classes (fields below — later tasks rely on these exact names), `SnapshotJson.decode(text: String): Snapshot`, `SnapshotJson.encode(s: Snapshot): String`.

- [ ] **Step 1: Write the failing test**

```kotlin
package tech.mrzeapple.ciphercodex.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotJsonTest {

    /** Shaped like a real rM2 snapshot: includes notebooks/pages/strokes arrays
     *  (points_b64 blobs) that Android must skip without error. */
    private val rm2Snapshot = """
    {"deviceId":"aabb01","generatedAt":1752300000000,
     "books":[{"digest":"d1","guid":"g-b1","title":"Dune","author":"Herbert","format":"epub",
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
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "tech.mrzeapple.ciphercodex.sync.webdav.SnapshotJsonTest"`
Expected: compile FAILURE (Snapshot/SnapshotJson unresolved).

- [ ] **Step 3: Implement models**

```kotlin
package tech.mrzeapple.ciphercodex.sync.webdav

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Field names are the frozen phase3b contract — do not rename.
@Serializable data class SnapBook(
    val digest: String, val guid: String = "", val title: String = "",
    val author: String? = null, val format: String = "epub",
    val addedAt: Long = 0, val lastOpenedAt: Long? = null,
    val deleted: Int = 0, val updatedAt: Long = 0,
)
@Serializable data class SnapProgress(
    val bookDigest: String, val spineIndex: Int = 0, val charOffset: Int = 0,
    val percentage: Float = 0f, val deleted: Int = 0, val updatedAt: Long = 0,
)
@Serializable data class SnapBookmark(
    val guid: String, val bookDigest: String, val spineIndex: Int = 0,
    val charOffset: Int = 0, val percentage: Float = 0f, val label: String = "",
    val createdAt: Long = 0, val deleted: Int = 0, val updatedAt: Long = 0,
)
@Serializable data class SnapHighlight(
    val guid: String, val bookDigest: String, val spineIndex: Int = 0,
    val startChar: Int = 0, val endChar: Int = 0, val text: String = "",
    val note: String? = null, val colorId: Int = 0,
    val createdAt: Long = 0, val deleted: Int = 0, val updatedAt: Long = 0,
)
@Serializable data class SnapCollection(
    val guid: String, val name: String = "", val createdAt: Long = 0,
    val deleted: Int = 0, val updatedAt: Long = 0,
)
@Serializable data class SnapBookCollection(
    val collectionGuid: String, val bookDigest: String,
    val deleted: Int = 0, val updatedAt: Long = 0,
)
@Serializable data class SnapSession(
    val guid: String, val bookDigest: String, val startedAt: Long = 0,
    val endedAt: Long = 0, val pagesTurned: Int = 0,
    val startPercentage: Float = 0f, val endPercentage: Float = 0f,
    val deleted: Int = 0, val updatedAt: Long = 0,
)
@Serializable data class Snapshot(
    val deviceId: String = "", val generatedAt: Long = 0,
    val books: List<SnapBook> = emptyList(),
    val progress: List<SnapProgress> = emptyList(),
    val bookmarks: List<SnapBookmark> = emptyList(),
    val highlights: List<SnapHighlight> = emptyList(),
    val collections: List<SnapCollection> = emptyList(),
    val bookCollections: List<SnapBookCollection> = emptyList(),
    val sessions: List<SnapSession> = emptyList(),
)

object SnapshotJson {
    // ignoreUnknownKeys drops notebooks/pages/strokes (and future fields) on the floor.
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    fun decode(text: String): Snapshot = json.decodeFromString(Snapshot.serializer(), text)
    fun encode(s: Snapshot): String = json.encodeToString(Snapshot.serializer(), s)
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "tech.mrzeapple.ciphercodex.sync.webdav.SnapshotJsonTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/ android/app/src/test/java/tech/mrzeapple/ciphercodex/sync/webdav/
git commit -m "feat(sync): snapshot contract models + JSON codec"
```

---

### Task 4: SnapshotMerge — pure LWW core (TDD, the crux)

**Files:**
- Create: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/SnapshotMerge.kt`
- Test: `android/app/src/test/java/tech/mrzeapple/ciphercodex/sync/webdav/SnapshotMergeTest.kt`

**Interfaces:**
- Consumes: Task 3 models.
- Produces:
```kotlin
object SnapshotMerge {
    data class Merged(
        val books: Map<String, SnapBook>,                          // by digest
        val progress: Map<String, SnapProgress>,                   // by bookDigest
        val bookmarks: Map<String, SnapBookmark>,                  // by guid
        val highlights: Map<String, SnapHighlight>,                // by guid
        val collections: Map<String, SnapCollection>,              // by guid
        val bookCollections: Map<Pair<String, String>, SnapBookCollection>, // by (collectionGuid, bookDigest)
        val sessions: Map<String, SnapSession>,                    // by guid
    )
    fun merge(snapshots: List<Snapshot>): Merged
    /** LWW: true when [remote] should replace a record with (localUpdatedAt, localDeleted). */
    fun wins(remoteUpdatedAt: Long, remoteDeleted: Int, localUpdatedAt: Long, localDeleted: Int): Boolean
}
```

- [ ] **Step 1: Write the failing tests (rM2's scenarios at merge level)**

```kotlin
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
```

- [ ] **Step 2: Run to verify FAIL** (unresolved `SnapshotMerge`)

`./gradlew :app:testDebugUnitTest --tests "tech.mrzeapple.ciphercodex.sync.webdav.SnapshotMergeTest"`

- [ ] **Step 3: Implement**

```kotlin
package tech.mrzeapple.ciphercodex.sync.webdav

object SnapshotMerge {

    data class Merged(
        val books: Map<String, SnapBook>,
        val progress: Map<String, SnapProgress>,
        val bookmarks: Map<String, SnapBookmark>,
        val highlights: Map<String, SnapHighlight>,
        val collections: Map<String, SnapCollection>,
        val bookCollections: Map<Pair<String, String>, SnapBookCollection>,
        val sessions: Map<String, SnapSession>,
    )

    fun wins(remoteUpdatedAt: Long, remoteDeleted: Int, localUpdatedAt: Long, localDeleted: Int): Boolean =
        remoteUpdatedAt > localUpdatedAt ||
            (remoteUpdatedAt == localUpdatedAt && remoteDeleted == 1 && localDeleted == 0)

    private fun <K, V> lww(
        snapshots: List<Snapshot>, rows: (Snapshot) -> List<V>,
        key: (V) -> K, updatedAt: (V) -> Long, deleted: (V) -> Int,
    ): Map<K, V> {
        val out = HashMap<K, V>()
        for (snap in snapshots) for (r in rows(snap)) {
            val cur = out[key(r)]
            if (cur == null || wins(updatedAt(r), deleted(r), updatedAt(cur), deleted(cur))) out[key(r)] = r
        }
        return out
    }

    fun merge(snapshots: List<Snapshot>): Merged = Merged(
        books = lww(snapshots, { it.books }, { it.digest }, { it.updatedAt }, { it.deleted }),
        progress = lww(snapshots, { it.progress }, { it.bookDigest }, { it.updatedAt }, { it.deleted }),
        bookmarks = lww(snapshots, { it.bookmarks }, { it.guid }, { it.updatedAt }, { it.deleted }),
        highlights = lww(snapshots, { it.highlights }, { it.guid }, { it.updatedAt }, { it.deleted }),
        collections = lww(snapshots, { it.collections }, { it.guid }, { it.updatedAt }, { it.deleted }),
        bookCollections = lww(snapshots, { it.bookCollections },
            { Pair(it.collectionGuid, it.bookDigest) }, { it.updatedAt }, { it.deleted }),
        sessions = lww(snapshots, { it.sessions }, { it.guid }, { it.updatedAt }, { it.deleted }),
    )
}
```

- [ ] **Step 4: Run to verify PASS** (same command)

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/SnapshotMerge.kt android/app/src/test/java/tech/mrzeapple/ciphercodex/sync/webdav/SnapshotMergeTest.kt
git commit -m "feat(sync): pure LWW snapshot merge core + convergence tests"
```

---

### Task 5: WebDavClient (OkHttp) + PROPFIND parser (TDD on the parser)

**Files:**
- Create: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/WebDavClient.kt`
- Test: `android/app/src/test/java/tech/mrzeapple/ciphercodex/sync/webdav/WebDavXmlTest.kt`

**Interfaces:**
- Produces:
```kotlin
class WebDavClient(baseUrl: String, user: String, pass: String) {
    fun list(relDir: String): List<String>      // child names (decoded, no trailing '/'), throws IOException
    fun get(relPath: String): ByteArray         // throws IOException on non-2xx
    fun getToFile(relPath: String, dest: java.io.File)
    fun put(relPath: String, data: ByteArray)
    fun putFile(relPath: String, src: java.io.File, contentType: String)
    fun mkcol(relDir: String)                   // 201/405 both OK (idempotent)
    fun move(fromRel: String, toRel: String)    // Overwrite: T
    fun test(): Result<Unit>                    // PROPFIND base, depth 0
}
object WebDavXml { fun childNames(propfindXml: String, requestPath: String): List<String> }
```
`baseUrl` is normalized to end with `/`; all `rel*` are relative to it.

- [ ] **Step 1: Failing parser test (dufs-shaped PROPFIND response)**

```kotlin
package tech.mrzeapple.ciphercodex.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavXmlTest {
    private val dufsXml = """
    <?xml version="1.0" encoding="utf-8"?>
    <D:multistatus xmlns:D="DAV:">
      <D:response><D:href>/ccx/state/</D:href><D:propstat><D:prop>
        <D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
      <D:response><D:href>/ccx/state/aabb01.json</D:href><D:propstat><D:prop>
        <D:resourcetype></D:resourcetype></D:prop></D:propstat></D:response>
      <D:response><D:href>/ccx/state/dead%20beef.json</D:href><D:propstat><D:prop>
        <D:resourcetype></D:resourcetype></D:prop></D:propstat></D:response>
    </D:multistatus>
    """.trimIndent()

    @Test
    fun `extracts child names, skips self, decodes percent-encoding`() {
        assertEquals(listOf("aabb01.json", "dead beef.json"),
            WebDavXml.childNames(dufsXml, "/ccx/state/"))
    }

    @Test
    fun `lowercase namespace prefix also parses`() {
        val xml = dufsXml.replace("D:", "d:")
        assertEquals(listOf("aabb01.json", "dead beef.json"),
            WebDavXml.childNames(xml, "/ccx/state/"))
    }

    @Test
    fun `directory children keep no trailing slash`() {
        val xml = """<D:multistatus xmlns:D="DAV:">
          <D:response><D:href>/ccx/</D:href></D:response>
          <D:response><D:href>/ccx/books/</D:href></D:response>
        </D:multistatus>"""
        assertEquals(listOf("books"), WebDavXml.childNames(xml, "/ccx/"))
    }
}
```

- [ ] **Step 2: Run to verify FAIL**

`./gradlew :app:testDebugUnitTest --tests "tech.mrzeapple.ciphercodex.sync.webdav.WebDavXmlTest"`

- [ ] **Step 3: Implement parser + client**

```kotlin
package tech.mrzeapple.ciphercodex.sync.webdav

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** Minimal href scan — enough for dufs/Nextcloud PROPFIND (no XML lib on purpose:
 *  the JVM parser isn't available in unit tests and the shape is trivial). */
object WebDavXml {
    private val href = Regex("<[a-zA-Z]*:?href>(.*?)</[a-zA-Z]*:?href>", RegexOption.IGNORE_CASE)

    fun childNames(propfindXml: String, requestPath: String): List<String> {
        val self = requestPath.trimEnd('/')
        return href.findAll(propfindXml)
            .map { URLDecoder.decode(it.groupValues[1].trim(), "UTF-8").trimEnd('/') }
            .filter { it.isNotEmpty() && it != self }
            .map { it.substringAfterLast('/') }
            .filter { it.isNotEmpty() }
            .toList()
    }
}

class WebDavClient(baseUrl: String, user: String, pass: String) {
    private val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    private val auth = Credentials.basic(user, pass)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun req(relPath: String) =
        Request.Builder().url(base + relPath).header("Authorization", auth)

    private fun run(r: Request): Response {
        val resp = http.newCall(r).execute()
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            throw IOException("HTTP $code for ${r.method} ${r.url.encodedPath}")
        }
        return resp
    }

    fun list(relDir: String): List<String> {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
        val resp = run(req(relDir).method("PROPFIND",
            body.toRequestBody("application/xml".toMediaType()))
            .header("Depth", "1").build())
        return resp.use { WebDavXml.childNames(it.body!!.string(), "/" + it.request.url.encodedPath.trimStart('/')) }
    }

    fun get(relPath: String): ByteArray =
        run(req(relPath).get().build()).use { it.body!!.bytes() }

    fun getToFile(relPath: String, dest: File) {
        run(req(relPath).get().build()).use { resp ->
            dest.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
        }
    }

    fun put(relPath: String, data: ByteArray) {
        run(req(relPath).put(data.toRequestBody("application/json".toMediaType())).build()).close()
    }

    fun putFile(relPath: String, src: File, contentType: String) {
        run(req(relPath).put(src.asRequestBody(contentType.toMediaType())).build()).close()
    }

    fun mkcol(relDir: String) {
        val resp = http.newCall(req(relDir).method("MKCOL", null).build()).execute()
        val ok = resp.isSuccessful || resp.code == 405 // 405 = already exists
        resp.close()
        if (!ok) throw IOException("MKCOL $relDir failed")
    }

    fun move(fromRel: String, toRel: String) {
        run(req(fromRel).method("MOVE", null)
            .header("Destination", base + toRel)
            .header("Overwrite", "T").build()).close()
    }

    fun test(): Result<Unit> = runCatching {
        run(req("").method("PROPFIND",
            "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"
                .toRequestBody("application/xml".toMediaType()))
            .header("Depth", "0").build()).close()
    }
}
```
(Import `okhttp3.RequestBody.Companion.asRequestBody` for `putFile`.)

- [ ] **Step 4: Run to verify PASS**, then full unit suite: `./gradlew :app:testDebugUnitTest` → PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/WebDavClient.kt android/app/src/test/java/tech/mrzeapple/ciphercodex/sync/webdav/WebDavXmlTest.kt
git commit -m "feat(sync): WebDAV client (PROPFIND/GET/PUT/MKCOL/MOVE) + href parser"
```

---

### Task 6: SyncDao + WebDavSyncManager engine

**Files:**
- Create: `android/app/src/main/java/tech/mrzeapple/ciphercodex/data/db/SyncDao.kt`
- Create: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/WebDavSyncManager.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/data/db/AppDatabase.kt` (add `abstract fun syncDao(): SyncDao`)
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/data/BookRepository.kt` + `LibraryRepository` interface (add `attachBookFile`)

**Interfaces:**
- Consumes: Tasks 3–5 (`Snapshot`, `SnapshotMerge.merge/wins`, `WebDavClient`), Task 1/2 columns.
- Produces:
```kotlin
class WebDavSyncManager(prefs: UserPrefs, db: AppDatabase, repository: LibraryRepository) {
    data class WebDavSummary(val booksUp: Int, val booksDown: Int, val entities: Int,
                             val tombstones: Int, val error: String? = null)
    suspend fun syncNow(): WebDavSummary            // single-flight; no-op summary when unconfigured
    suspend fun syncIfDue(minIntervalMs: Long): WebDavSummary?  // null when skipped
    suspend fun testConnection(url: String, user: String, pass: String): Result<Unit>
    val running: kotlinx.coroutines.flow.StateFlow<Boolean>
}
// LibraryRepository gains:
suspend fun attachBookFile(digest: String, file: java.io.File): Boolean
```

- [ ] **Step 1: SyncDao — full-table reads + upserts + attach**

```kotlin
package tech.mrzeapple.ciphercodex.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** Sync-only access: reads INCLUDE tombstones (snapshots carry them). */
@Dao
interface SyncDao {
    @Query("SELECT * FROM books") suspend fun allBooks(): List<BookEntity>
    @Query("SELECT * FROM progress") suspend fun allProgress(): List<ProgressEntity>
    @Query("SELECT * FROM bookmarks") suspend fun allBookmarks(): List<BookmarkEntity>
    @Query("SELECT * FROM highlights") suspend fun allHighlights(): List<HighlightEntity>
    @Query("SELECT * FROM collections") suspend fun allCollections(): List<CollectionEntity>
    @Query("SELECT * FROM book_collections") suspend fun allBookCollections(): List<BookCollectionCrossRef>
    @Query("SELECT * FROM reading_sessions") suspend fun allSessions(): List<ReadingSessionEntity>

    @Query("SELECT * FROM bookmarks WHERE guid = :guid") suspend fun bookmarkByGuid(guid: String): BookmarkEntity?
    @Query("SELECT * FROM highlights WHERE guid = :guid") suspend fun highlightByGuid(guid: String): HighlightEntity?
    @Query("SELECT * FROM collections WHERE guid = :guid") suspend fun collectionByGuid(guid: String): CollectionEntity?
    @Query("SELECT * FROM reading_sessions WHERE guid = :guid") suspend fun sessionByGuid(guid: String): ReadingSessionEntity?
    @Query("SELECT * FROM book_collections WHERE collectionId = :collectionId AND bookId = :bookId")
    suspend fun bookCollection(collectionId: Long, bookId: Long): BookCollectionCrossRef?

    @Upsert suspend fun upsertBook(b: BookEntity)
    @Upsert suspend fun upsertProgress(p: ProgressEntity)
    @Upsert suspend fun upsertBookmark(b: BookmarkEntity)
    @Upsert suspend fun upsertHighlight(h: HighlightEntity)
    @Upsert suspend fun upsertCollection(c: CollectionEntity)
    @Upsert suspend fun upsertBookCollection(r: BookCollectionCrossRef)
    @Upsert suspend fun upsertSession(s: ReadingSessionEntity)

    @Query("UPDATE books SET filePath = :filePath, coverPath = :coverPath, sizeBytes = :sizeBytes WHERE digest = :digest")
    suspend fun attachBookFile(digest: String, filePath: String, coverPath: String?, sizeBytes: Long)
}
```

- [ ] **Step 2: repository.attachBookFile**

In `LibraryRepository` interface add `suspend fun attachBookFile(digest: String, file: File): Boolean`; in `BookRepository`:

```kotlin
/** Attach a sync-downloaded epub to its merged metadata-only row. Verifies the
 *  digest matches before accepting; extracts the cover. */
override suspend fun attachBookFile(digest: String, file: File): Boolean = withContext(Dispatchers.IO) {
    if (Digests.partialMd5(file) != digest) { file.delete(); return@withContext false }
    val coverPath = runCatching {
        Epub.open(file).use { doc ->
            doc.coverImageBytes()?.let { bytes ->
                File(coversDir(), "$digest.img").apply { writeBytes(bytes) }.absolutePath
            }
        }
    }.getOrNull()
    val dest = File(booksDir(), "$digest.epub")
    moveFile(file, dest)
    dao.attachBookFileRelay(digest, dest.absolutePath, coverPath, dest.length())
    true
}
```
Wire `attachBookFileRelay` however DI is cleanest: pass `SyncDao` into `BookRepository`'s constructor (`database.syncDao()` in `CipherCodexApp`) and call `syncDao.attachBookFile(...)` directly — rename accordingly.

- [ ] **Step 3: WebDavSyncManager**

```kotlin
package tech.mrzeapple.ciphercodex.sync.webdav

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tech.mrzeapple.ciphercodex.data.LibraryRepository
import tech.mrzeapple.ciphercodex.data.db.AppDatabase
import tech.mrzeapple.ciphercodex.data.db.BookCollectionCrossRef
import tech.mrzeapple.ciphercodex.data.db.BookEntity
import tech.mrzeapple.ciphercodex.data.db.BookmarkEntity
import tech.mrzeapple.ciphercodex.data.db.CollectionEntity
import tech.mrzeapple.ciphercodex.data.db.HighlightEntity
import tech.mrzeapple.ciphercodex.data.db.ProgressEntity
import tech.mrzeapple.ciphercodex.data.db.ReadingSessionEntity
import tech.mrzeapple.ciphercodex.data.prefs.UserPrefs
import java.io.File

class WebDavSyncManager(
    private val prefs: UserPrefs,
    private val db: AppDatabase,
    private val repository: LibraryRepository,
    private val cacheDir: File,
) {
    data class WebDavSummary(val booksUp: Int = 0, val booksDown: Int = 0, val entities: Int = 0,
                             val tombstones: Int = 0, val error: String? = null)

    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    suspend fun testConnection(url: String, user: String, pass: String): Result<Unit> =
        withContext(Dispatchers.IO) { WebDavClient(url, user, pass).test() }

    suspend fun syncIfDue(minIntervalMs: Long): WebDavSummary? {
        val s = prefs.current()
        if (s.webdavUrl.isEmpty()) return null
        if (System.currentTimeMillis() - s.webdavLastSyncAt < minIntervalMs) return null
        return syncNow()
    }

    suspend fun syncNow(): WebDavSummary = mutex.withLock {
        withContext(Dispatchers.IO) {
            _running.value = true
            try { doSync() } catch (e: Exception) {
                WebDavSummary(error = e.message ?: e.javaClass.simpleName)
            } finally { _running.value = false }
        }
    }

    private suspend fun doSync(): WebDavSummary {
        val settings = prefs.current()
        if (settings.webdavUrl.isEmpty()) return WebDavSummary(error = "not configured")
        val deviceId = prefs.deviceId()
        val dav = WebDavClient(settings.webdavUrl, settings.webdavUser, settings.webdavPass)
        val sync = db.syncDao()

        dav.mkcol("books/"); dav.mkcol("state/")

        // 1. Upload book files the endpoint lacks (live epubs with a local file).
        val remoteBooks = dav.list("books/").toSet()
        var booksUp = 0
        for (b in sync.allBooks()) {
            if (b.deleted || b.filePath.isEmpty()) continue
            val f = File(b.filePath)
            if (!f.exists() || "${b.digest}.epub" in remoteBooks) continue
            dav.putFile("books/${b.digest}.epub", f, "application/epub+zip")
            booksUp++
        }

        // 2. Pull every device snapshot (unparsable one aborts: better no sync than a partial merge).
        val snapshots = dav.list("state/")
            .filter { it.endsWith(".json") }
            .map { SnapshotJson.decode(dav.get("state/$it").decodeToString()) }

        // 3. Merge + apply.
        val merged = SnapshotMerge.merge(snapshots)
        val result = applyMerged(merged)

        // 4. Fetch missing book files, attach.
        var booksDown = 0
        for (digest in result.needFiles) {
            if ("$digest.epub" !in remoteBooks) continue // not uploaded anywhere yet
            val tmp = File(cacheDir, "dav-$digest.epub")
            try {
                dav.getToFile("books/$digest.epub", tmp)
                if (repository.attachBookFile(digest, tmp)) booksDown++
            } finally { tmp.delete() }
        }

        // 5. Push our snapshot (full state incl. tombstones), atomically.
        val out = SnapshotJson.encode(exportSnapshot(deviceId))
        dav.put("state/$deviceId.json.tmp", out.encodeToByteArray())
        dav.move("state/$deviceId.json.tmp", "state/$deviceId.json")

        prefs.setWebdavLastSyncAt(System.currentTimeMillis())
        return WebDavSummary(booksUp, booksDown, result.entities, result.tombstones)
    }

    private data class ApplyResult(val needFiles: List<String>, val entities: Int, val tombstones: Int)

    private suspend fun applyMerged(m: SnapshotMerge.Merged): ApplyResult {
        val sync = db.syncDao()
        var entities = 0; var tombstones = 0
        val needFiles = mutableListOf<String>()
        db.withTransaction {
            // --- books (by digest; epub only; filePath/coverPath stay local) ---
            val localBooks = sync.allBooks().associateBy { it.digest }.toMutableMap()
            for ((digest, r) in m.books) {
                if (r.format != "epub") continue
                val local = localBooks[digest]
                if (local == null) {
                    if (r.deleted == 1) continue // nothing local to tombstone; don't import a corpse
                    val row = BookEntity(title = r.title, author = r.author, filePath = "",
                        digest = digest, coverPath = null, sizeBytes = 0,
                        addedAt = r.addedAt, lastOpenedAt = r.lastOpenedAt,
                        guid = r.guid, updatedAt = r.updatedAt, deleted = false)
                    sync.upsertBook(row)
                    localBooks[digest] = sync.allBooks().first { it.digest == digest }
                    needFiles.add(digest); entities++
                } else if (SnapshotMerge.wins(r.updatedAt, r.deleted, local.updatedAt, if (local.deleted) 1 else 0)) {
                    val nowDeleted = r.deleted == 1
                    sync.upsertBook(local.copy(title = r.title, author = r.author,
                        addedAt = r.addedAt, lastOpenedAt = r.lastOpenedAt,
                        updatedAt = r.updatedAt, deleted = nowDeleted,
                        filePath = if (nowDeleted) "" else local.filePath,
                        coverPath = if (nowDeleted) null else local.coverPath))
                    if (nowDeleted && !local.deleted) {
                        if (local.filePath.isNotEmpty()) File(local.filePath).delete()
                        local.coverPath?.let { File(it).delete() }
                        tombstones++
                    } else entities++
                    localBooks[digest] = sync.allBooks().first { it.digest == digest }
                } else if (!local.deleted && local.filePath.isEmpty()) {
                    needFiles.add(digest) // row known, file still missing (earlier failed download)
                }
            }
            val bookIdByDigest = localBooks.mapValues { it.value.id }

            // --- progress (by book digest) ---
            for ((digest, r) in m.progress) {
                val bookId = bookIdByDigest[digest] ?: continue // missing parent: skip
                val local = sync.allProgress().firstOrNull { it.bookId == bookId }
                if (local == null || SnapshotMerge.wins(r.updatedAt, r.deleted, local.updatedAt, if (local.deleted) 1 else 0)) {
                    sync.upsertProgress(ProgressEntity(bookId = bookId, spineIndex = r.spineIndex,
                        charOffset = r.charOffset, percentage = r.percentage,
                        updatedAt = r.updatedAt, syncedAt = local?.syncedAt,
                        deleted = r.deleted == 1))
                    if (r.deleted == 1) tombstones++ else entities++
                }
            }

            // --- bookmarks / highlights / sessions (by guid, parent by digest) ---
            for ((guid, r) in m.bookmarks) {
                val bookId = bookIdByDigest[r.bookDigest] ?: continue
                val local = sync.bookmarkByGuid(guid)
                if (local == null || SnapshotMerge.wins(r.updatedAt, r.deleted, local.updatedAt, if (local.deleted) 1 else 0)) {
                    sync.upsertBookmark(BookmarkEntity(id = local?.id ?: 0, bookId = bookId,
                        spineIndex = r.spineIndex, charOffset = r.charOffset, percentage = r.percentage,
                        label = r.label, createdAt = r.createdAt,
                        guid = guid, updatedAt = r.updatedAt, deleted = r.deleted == 1))
                    if (r.deleted == 1) tombstones++ else entities++
                }
            }
            for ((guid, r) in m.highlights) {
                val bookId = bookIdByDigest[r.bookDigest] ?: continue
                val local = sync.highlightByGuid(guid)
                if (local == null || SnapshotMerge.wins(r.updatedAt, r.deleted, local.updatedAt, if (local.deleted) 1 else 0)) {
                    sync.upsertHighlight(HighlightEntity(id = local?.id ?: 0, bookId = bookId,
                        spineIndex = r.spineIndex, startChar = r.startChar, endChar = r.endChar,
                        text = r.text, createdAt = r.createdAt, note = r.note, colorId = r.colorId,
                        guid = guid, updatedAt = r.updatedAt, deleted = r.deleted == 1))
                    if (r.deleted == 1) tombstones++ else entities++
                }
            }
            for ((guid, r) in m.sessions) {
                val bookId = bookIdByDigest[r.bookDigest] ?: continue
                val local = sync.sessionByGuid(guid)
                if (local == null || SnapshotMerge.wins(r.updatedAt, r.deleted, local.updatedAt, if (local.deleted) 1 else 0)) {
                    sync.upsertSession(ReadingSessionEntity(id = local?.id ?: 0, bookId = bookId,
                        startedAt = r.startedAt, endedAt = r.endedAt, pagesTurned = r.pagesTurned,
                        startPercentage = r.startPercentage, endPercentage = r.endPercentage,
                        guid = guid, updatedAt = r.updatedAt, deleted = r.deleted == 1))
                    if (r.deleted == 1) tombstones++ else entities++
                }
            }

            // --- collections then memberships ---
            val collectionIdByGuid = HashMap<String, Long>()
            for ((guid, r) in m.collections) {
                val local = sync.collectionByGuid(guid)
                if (local == null || SnapshotMerge.wins(r.updatedAt, r.deleted, local.updatedAt, if (local.deleted) 1 else 0)) {
                    sync.upsertCollection(CollectionEntity(id = local?.id ?: 0, name = r.name,
                        createdAt = r.createdAt, guid = guid, updatedAt = r.updatedAt, deleted = r.deleted == 1))
                    if (r.deleted == 1) tombstones++ else entities++
                }
                (sync.collectionByGuid(guid))?.let { collectionIdByGuid[guid] = it.id }
            }
            for ((key, r) in m.bookCollections) {
                val collectionId = collectionIdByGuid[key.first]
                    ?: sync.collectionByGuid(key.first)?.id ?: continue
                val bookId = bookIdByDigest[key.second] ?: continue
                val local = sync.bookCollection(collectionId, bookId)
                if (local == null || SnapshotMerge.wins(r.updatedAt, r.deleted, local.updatedAt, if (local.deleted) 1 else 0)) {
                    sync.upsertBookCollection(BookCollectionCrossRef(collectionId = collectionId,
                        bookId = bookId, updatedAt = r.updatedAt, deleted = r.deleted == 1))
                    if (r.deleted == 1) tombstones++ else entities++
                }
            }
        }
        return ApplyResult(needFiles.distinct(), entities, tombstones)
    }

    private suspend fun exportSnapshot(deviceId: String): Snapshot {
        val sync = db.syncDao()
        val books = sync.allBooks()
        val digestById = books.associate { it.id to it.digest }
        fun d(deleted: Boolean) = if (deleted) 1 else 0
        return Snapshot(
            deviceId = deviceId,
            generatedAt = System.currentTimeMillis(),
            books = books.map { SnapBook(digest = it.digest, guid = it.guid, title = it.title,
                author = it.author, format = "epub", addedAt = it.addedAt,
                lastOpenedAt = it.lastOpenedAt, deleted = d(it.deleted), updatedAt = it.updatedAt) },
            progress = sync.allProgress().mapNotNull { p ->
                val digest = digestById[p.bookId] ?: return@mapNotNull null
                SnapProgress(bookDigest = digest, spineIndex = p.spineIndex, charOffset = p.charOffset,
                    percentage = p.percentage, deleted = d(p.deleted), updatedAt = p.updatedAt) },
            bookmarks = sync.allBookmarks().mapNotNull { b ->
                val digest = digestById[b.bookId] ?: return@mapNotNull null
                SnapBookmark(guid = b.guid, bookDigest = digest, spineIndex = b.spineIndex,
                    charOffset = b.charOffset, percentage = b.percentage, label = b.label,
                    createdAt = b.createdAt, deleted = d(b.deleted), updatedAt = b.updatedAt) },
            highlights = sync.allHighlights().mapNotNull { h ->
                val digest = digestById[h.bookId] ?: return@mapNotNull null
                SnapHighlight(guid = h.guid, bookDigest = digest, spineIndex = h.spineIndex,
                    startChar = h.startChar, endChar = h.endChar, text = h.text, note = h.note,
                    colorId = h.colorId, createdAt = h.createdAt, deleted = d(h.deleted), updatedAt = h.updatedAt) },
            collections = sync.allCollections().map { c ->
                SnapCollection(guid = c.guid, name = c.name, createdAt = c.createdAt,
                    deleted = d(c.deleted), updatedAt = c.updatedAt) },
            bookCollections = run {
                val guidByCollectionId = sync.allCollections().associate { it.id to it.guid }
                sync.allBookCollections().mapNotNull { r ->
                    val cg = guidByCollectionId[r.collectionId] ?: return@mapNotNull null
                    val bd = digestById[r.bookId] ?: return@mapNotNull null
                    SnapBookCollection(collectionGuid = cg, bookDigest = bd,
                        deleted = d(r.deleted), updatedAt = r.updatedAt) } },
            sessions = sync.allSessions().mapNotNull { s ->
                val digest = digestById[s.bookId] ?: return@mapNotNull null
                SnapSession(guid = s.guid, bookDigest = digest, startedAt = s.startedAt,
                    endedAt = s.endedAt, pagesTurned = s.pagesTurned,
                    startPercentage = s.startPercentage, endPercentage = s.endPercentage,
                    deleted = d(s.deleted), updatedAt = s.updatedAt) },
        )
    }
}
```
Note the `allProgress()` lookup inside the progress loop is O(n²) — load it once before the loop into `associateBy { it.bookId }`; same one-shot pattern for bookmarks-by-guid etc. if the implementer prefers, but per-guid point queries are fine at this scale. `prefs.webdavUrl/webdavUser/webdavPass/webdavLastSyncAt/setWebdavLastSyncAt` arrive in Task 7 — write Task 7's prefs first if compiling this task standalone, or do Steps 1–3 of Task 7 together with this one.

- [ ] **Step 4: Build + full unit suite**

`./gradlew :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/tech/mrzeapple/ciphercodex/
git commit -m "feat(sync): WebDAV sync engine — snapshot export/apply + book-file union"
```

---

### Task 7: Prefs + Settings UI + triggers + worker

**Files:**
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/data/prefs/UserPrefs.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/CipherCodexApp.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/ui/settings/SettingsScreen.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/MainActivity.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/SyncWorker.kt`
- Modify: the reader-exit push site (grep `pushProgress` under `ui/` — same place kosync pushes on leaving the reader)

**Interfaces:**
- Consumes: `WebDavSyncManager` (Task 6).
- Produces: `Settings` gains `webdavUrl: String`, `webdavUser: String`, `webdavPass: String`, `webdavLastSyncAt: Long`; `UserPrefs` gains matching setters (`setWebdavUrl` trims + ensures trailing `/`); `CipherCodexApp.webdavSync: WebDavSyncManager`.

- [ ] **Step 1: Prefs**

Add to `Keys`: `webdavUrl/webdavUser/webdavPass/webdavLastSyncAt` (string/string/string/long preference keys, names `"webdav_url"` etc.). Add the four fields to `Settings` + the `settings` flow mapping (defaults `""`/`0L`). Setters:

```kotlin
suspend fun setWebdavUrl(value: String) = context.dataStore.edit {
    val t = value.trim()
    it[Keys.webdavUrl] = if (t.isEmpty() || t.endsWith("/")) t else "$t/"
}
suspend fun setWebdavUser(value: String) = context.dataStore.edit { it[Keys.webdavUser] = value.trim() }
suspend fun setWebdavPass(value: String) = context.dataStore.edit { it[Keys.webdavPass] = value }
suspend fun setWebdavLastSyncAt(value: Long) = context.dataStore.edit { it[Keys.webdavLastSyncAt] = value }
```

- [ ] **Step 2: App wiring**

`CipherCodexApp` gains:
```kotlin
val webdavSync: WebDavSyncManager by lazy {
    WebDavSyncManager(prefs, database, repository, cacheDir)
}
```
(and pass `database.syncDao()` into `BookRepository` if Task 6 chose constructor injection.)

- [ ] **Step 3: Settings section**

In `SettingsScreen.kt`, after the existing kosync SYNC block, add a "CIPHERCODEX SYNC // WEBDAV" section using the SAME composable vocabulary the kosync section uses (read lines ~170–245 first and mirror: section header + `CipherCaption`, text fields for URL/USER/APP PASSWORD (password field uses the same visual transformation as the kosync password field), a TEST button driving a `ConnectionState`-style label via `webdavSync.testConnection(...)`, a SYNC NOW button calling `webdavSync.syncNow()` and showing the returned `WebDavSummary` (`"↑$booksUp ↓$booksDown ~$entities"` or the error), and a `LAST SYNC // ${formatLastSync(settings.webdavLastSyncAt)}` caption). Save fields through the Step-1 setters on value change, exactly as the kosync fields persist. SYNC NOW disabled while `webdavSync.running` collects true.

- [ ] **Step 4: Triggers**

- Foreground: in `MainActivity.onStart()` — `lifecycleScope.launch { (application as CipherCodexApp).webdavSync.syncIfDue(5 * 60_000L) }`.
- Reader close: at the kosync `pushProgress` on-exit call site, after the push, `webdavSync.syncIfDue(5 * 60_000L)` in the same scope.
- Worker: in `SyncWorker.doWork()` after `syncAllDirty()`:
```kotlin
val dav = app.webdavSync.syncIfDue(0L)
return if (summary.failed > 0 || dav?.error != null) Result.retry() else Result.success()
```

- [ ] **Step 5: Build + unit tests**

`./gradlew :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/tech/mrzeapple/ciphercodex/
git commit -m "feat(sync): WebDAV settings UI, foreground/reader/worker triggers"
```

---

### Task 8: End-to-end verification + ship

**Files:**
- Modify: `android/app/build.gradle.kts` (version bump only, after QA passes)

- [ ] **Step 1: Migration check on the emulator (the v6→v7 gate)**

Recipe from the repo's release ritual: `gh release download` the latest release APK, install on the `cipher` AVD (emulator-5554, boot headless with `emulator -avd cipher -no-window`), open a book, add a highlight + bookmark; then `adb install -r` the NEW release-signed build (debug won't upgrade over release), launch, `adb logcat -d | grep -iE "FATAL|Migration"` → no migration crash; library, KEPT, bookmarks intact.

- [ ] **Step 2: Live round-trip against a scratch WebDAV path**

Use the real endpoint under a scratch base so real data is untouched (ask the owner for the `ccx` app password if not at hand; alternatively `scoop install dufs && dufs -A -a user:pass .` on the host and point the emulator at `http://10.0.2.2:5000/`):

1. `curl -u ccx:PASS -X MKCOL https://kosync.cph.gg/ccx/scratch-android/`
2. In the emulator app Settings: URL `https://kosync.cph.gg/ccx/scratch-android/`, user, password → TEST → OK.
3. SYNC NOW → summary shows `↑N` books; `curl -u ccx:PASS "https://kosync.cph.gg/ccx/scratch-android/state/" -X PROPFIND -H "Depth: 1"` lists `<deviceId>.json`; `books/` lists the epubs.
4. Wipe app data (`adb shell pm clear tech.mrzeapple.ciphercodex`), reconfigure, SYNC NOW → library repopulates (books download, covers regenerate), highlight + bookmark + progress return.
5. Delete a book in the app, SYNC NOW, then on a second data-wiped sync pass: the book must NOT come back (tombstone survives both directions).
6. Cleanup: `curl -u ccx:PASS -X DELETE https://kosync.cph.gg/ccx/scratch-android/`.

Expected: all six behaviors observed; no GUI freeze during sync.

- [ ] **Step 3: Cross-device sanity (with the rM2 snapshot)**

Copy a real rM2 `state/<id>.json` (from the live `ccx/state/`) into the scratch `state/`, SYNC NOW → its epub books + highlights appear on Android; its notebooks/ink cause no errors; PDFs don't appear.

- [ ] **Step 4: Ship**

Bump `versionName` (next minor, e.g. `0.5.0`) + `versionCode` in `app/build.gradle.kts`, commit `release: v0.5.0 — WebDAV full sync`, push — CI auto-releases on the version bump (the repo's release ritual).

---

## Self-review notes (already applied)

- Contract coverage: books/progress/bookmarks/highlights/collections/bookCollections/sessions all exported+applied; ink entities parse-skipped via `ignoreUnknownKeys`; PDFs dropped at apply; `deleted` as int 0/1; `points_b64` never modeled.
- Deliberate deviation from the spec text: `book_collections` gets NO guid column (the CONTRACT keys it by collectionGuid+bookDigest; the spec's task list over-included it).
- `observeBooks` additionally filters `filePath != ''` so merged-but-not-yet-downloaded books never render as openable.
- Type consistency: `wins(remoteUpdatedAt, remoteDeleted, localUpdatedAt, localDeleted)` used identically in Tasks 4 and 6; `WebDavSummary` field names match the Settings display in Task 7.

## After this plan

The X4 firmware plan (slices X1–X3 of the spec) is written as its own plan once this ships — it consumes the same contract and the live snapshots this implementation produces.
