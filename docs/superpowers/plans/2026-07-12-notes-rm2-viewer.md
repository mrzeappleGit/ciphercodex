# NOTES Tab — rM2 Notebook Viewer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A read-only NOTES tab in the Android app that renders the reMarkable 2's handwritten notebooks from the ink data already present in downloaded sync snapshots.

**Architecture:** A separate `InkSnapshot` decoder re-reads the snapshot texts the WebDAV sync already downloads (the frozen `Snapshot` class is never touched, so Android stays structurally incapable of emitting ink). After the existing apply pass, an ink pass LWW-merges notebook/page metadata into two new Room tables and renders new-or-changed pages to PNGs; the NOTES tab is a notebook grid over those files plus a zoomable pager.

**Tech Stack:** kotlinx-serialization, Room v8, android.graphics (Bitmap/Canvas), Compose (HorizontalPager, LazyVerticalGrid). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-07-12-notes-rm2-notebook-viewer-design.md`

## Global Constraints

- Android NEVER emits ink: `Snapshot`/`SnapshotJson` (the export path) must not be modified in any way.
- Wire fields (frozen phase3b contract): notebooks `{guid,title,createdAt,deleted,updatedAt}`; pages `{guid,notebookGuid,seq,deleted,updatedAt}`; strokes `{guid,pageGuid,tool,baseWidth,points_b64,createdAt,deleted,updatedAt}`; `deleted` is int 0/1.
- `points_b64` = base64 of 18-byte little-endian records `{f32 x, f32 y, u16 pressure, i16 tiltX, i16 tiltY, u32 tMs}`; x/y page-normalized 0..1; pressure 0–4095; page is portrait 1404×1872.
- Width curve mirrors rM2 `inkStrokeWidth` (`remarkable2-os/src/inkitem.cpp:38`): floor 0.35, minW 1.0, `p = clamp((pressure/4095 − 0.35)/0.65, 0, 1)`, `width = 1 + p²·(maxW − 1)` where maxW = stroke `baseWidth` (fallback 9.0 when ≤ 1).
- Ink tombstones are HARD-deleted locally (row + PNG) — Android keeps no ink tombstones; no `deleted` columns in the new tables.
- `contentStamp = maxOf(stroke.updatedAt) * 31 + liveStrokeCount` (0 for a strokeless page).
- Room migration SQL must EXACTLY match Room's generated schema (v4→v5 lesson); migration-added tables need entities whose columns match the SQL letter-for-letter.
- Ink-pass failure never fails or rolls back book/annotation sync — catch, surface in `WebDavSummary.error`, continue.
- Malformed `points_b64` (bad base64 or length % 18 ≠ 0) skips that stroke only.
- Package `tech.mrzeapple.ciphercodex`; manual DI via `CipherCodexApp`; repo work on `Dispatchers.IO`; existing Cipher composable vocabulary.
- Build check (Windows, from `android/`): `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` — grep output for "BUILD SUCCESSFUL"; JAVA_HOME is JDK 21.

---

### Task 1: Ink wire models + point decoder + segment geometry (pure Kotlin, TDD)

**Files:**
- Create: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/InkSnapshot.kt`
- Create: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/InkPoints.kt`
- Test: `android/app/src/test/java/tech/mrzeapple/ciphercodex/sync/webdav/InkPointsTest.kt`

**Interfaces:**
- Consumes: nothing new (kotlinx-serialization already configured).
- Produces (later tasks rely on these exact names):
  - `InkSnapshot(deviceId, notebooks: List<InkNotebook>, pages: List<InkPage>, strokes: List<InkStroke>)`, `InkSnapshotJson.decode(text: String): InkSnapshot`
  - `InkNotebook(guid, title, createdAt, deleted, updatedAt)`, `InkPage(guid, notebookGuid, seq, deleted, updatedAt)`, `InkStroke(guid, pageGuid, tool, baseWidth: Float, pointsB64, createdAt, deleted, updatedAt)`
  - `InkPoint(x: Float, y: Float, pressure: Int)`, `InkPoints.decode(b64: String): List<InkPoint>`
  - `InkSegment(x0, y0, x1, y1, width: Float)` (all Float, x/y normalized 0..1, width in px), `InkGeometry.strokeSegments(points: List<InkPoint>, baseWidth: Float): List<InkSegment>`

- [ ] **Step 1: Write the failing tests**

```kotlin
package tech.mrzeapple.ciphercodex.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class InkPointsTest {

    /** Packs points exactly like rM2 storage.cpp PackedPoint (18B LE). */
    private fun pack(vararg pts: Triple<Float, Float, Int>): String {
        val buf = ByteBuffer.allocate(pts.size * 18).order(ByteOrder.LITTLE_ENDIAN)
        for ((x, y, pressure) in pts) {
            buf.putFloat(x); buf.putFloat(y)
            buf.putShort(pressure.toShort())
            buf.putShort(0); buf.putShort(0) // tilt
            buf.putInt(0) // tMs
        }
        return Base64.getEncoder().encodeToString(buf.array())
    }

    @Test
    fun `decodes 18-byte LE records`() {
        val pts = InkPoints.decode(pack(Triple(0.25f, 0.5f, 4095), Triple(0.26f, 0.51f, 2000)))
        assertEquals(2, pts.size)
        assertEquals(0.25f, pts[0].x, 1e-6f)
        assertEquals(0.5f, pts[0].y, 1e-6f)
        assertEquals(4095, pts[0].pressure)
        assertEquals(2000, pts[1].pressure)
    }

    @Test
    fun `malformed input decodes to empty, never throws`() {
        assertTrue(InkPoints.decode("not-base64!!!").isEmpty())
        // valid base64, but 17 bytes — not a multiple of 18
        assertTrue(InkPoints.decode(Base64.getEncoder().encodeToString(ByteArray(17))).isEmpty())
        assertTrue(InkPoints.decode("").isEmpty())
    }

    @Test
    fun `segments follow the rM2 pressure curve`() {
        val pts = listOf(InkPoint(0f, 0f, 4095), InkPoint(0.1f, 0f, 4095))
        val seg = InkGeometry.strokeSegments(pts, baseWidth = 9f).single()
        assertEquals(9f, seg.width, 1e-3f) // full pressure = full baseWidth
        val soft = InkGeometry.strokeSegments(
            listOf(InkPoint(0f, 0f, 0), InkPoint(0.1f, 0f, 0)), 9f).single()
        assertEquals(1f, soft.width, 1e-3f) // below floor = minW
    }

    @Test
    fun `single point becomes a dot segment`() {
        val seg = InkGeometry.strokeSegments(listOf(InkPoint(0.5f, 0.5f, 4095)), 9f).single()
        assertEquals(seg.x0, seg.x1, 0f)
        assertEquals(seg.y0, seg.y1, 0f)
    }

    @Test
    fun `ink snapshot decodes wire json and ignores unknown keys`() {
        val s = InkSnapshotJson.decode("""
        {"deviceId":"aabb01","generatedAt":1,"books":[{"digest":"d1"}],
         "notebooks":[{"guid":"n1","title":"ink","createdAt":6,"deleted":0,"updatedAt":16}],
         "pages":[{"guid":"p1","notebookGuid":"n1","seq":0,"deleted":0,"updatedAt":17}],
         "strokes":[{"guid":"s1","pageGuid":"p1","tool":0,"baseWidth":2.0,"points_b64":"AAAA",
                     "createdAt":7,"deleted":0,"updatedAt":18}]}
        """.trimIndent())
        assertEquals("aabb01", s.deviceId)
        assertEquals("ink", s.notebooks.single().title)
        assertEquals("n1", s.pages.single().notebookGuid)
        assertEquals(2.0f, s.strokes.single().baseWidth, 1e-6f)
        assertEquals("AAAA", s.strokes.single().pointsB64)
        // missing arrays parse as empty
        assertTrue(InkSnapshotJson.decode("""{"deviceId":"x"}""").notebooks.isEmpty())
    }
}
```

- [ ] **Step 2: Run to verify FAIL**

Run from `android/`: `.\gradlew.bat :app:testDebugUnitTest --tests "tech.mrzeapple.ciphercodex.sync.webdav.InkPointsTest"`
Expected: compile FAILURE (`Unresolved reference 'InkPoints'`).

- [ ] **Step 3: Implement the models**

`InkSnapshot.kt`:

```kotlin
package tech.mrzeapple.ciphercodex.sync.webdav

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** DECODE-ONLY view of the ink arrays in a device snapshot. Deliberately separate
 *  from [Snapshot] so Android's own export can never grow ink keys. */
@Serializable data class InkNotebook(
    val guid: String, val title: String = "", val createdAt: Long = 0,
    val deleted: Int = 0, val updatedAt: Long = 0,
)
@Serializable data class InkPage(
    val guid: String, val notebookGuid: String, val seq: Int = 0,
    val deleted: Int = 0, val updatedAt: Long = 0,
)
@Serializable data class InkStroke(
    val guid: String, val pageGuid: String, val tool: Int = 0,
    val baseWidth: Float = 0f, @SerialName("points_b64") val pointsB64: String = "",
    val createdAt: Long = 0, val deleted: Int = 0, val updatedAt: Long = 0,
)
@Serializable data class InkSnapshot(
    val deviceId: String = "",
    val notebooks: List<InkNotebook> = emptyList(),
    val pages: List<InkPage> = emptyList(),
    val strokes: List<InkStroke> = emptyList(),
)

object InkSnapshotJson {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    fun decode(text: String): InkSnapshot = json.decodeFromString(InkSnapshot.serializer(), text)
}
```

`InkPoints.kt`:

```kotlin
package tech.mrzeapple.ciphercodex.sync.webdav

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** One pen sample. x/y are page-normalized 0..1; pressure is raw 0..4095. */
data class InkPoint(val x: Float, val y: Float, val pressure: Int)

/** A drawable segment in normalized page coords; width is in page pixels. */
data class InkSegment(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val width: Float)

object InkPoints {
    private const val BYTES_PER_POINT = 18 // rM2 storage.cpp PackedPoint, little-endian

    /** Malformed input (bad base64 / not a multiple of 18 bytes) → empty list, never throws. */
    fun decode(b64: String): List<InkPoint> {
        val bytes = try { Base64.getDecoder().decode(b64) } catch (_: IllegalArgumentException) { return emptyList() }
        if (bytes.isEmpty() || bytes.size % BYTES_PER_POINT != 0) return emptyList()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<InkPoint>(bytes.size / BYTES_PER_POINT)
        while (buf.remaining() >= BYTES_PER_POINT) {
            val x = buf.float
            val y = buf.float
            val pressure = buf.short.toInt() and 0xFFFF
            buf.short; buf.short; buf.int // tiltX, tiltY, tMs — unused in v1
            out.add(InkPoint(x, y, pressure))
        }
        return out
    }
}

object InkGeometry {
    private const val PRESSURE_MAX = 4095f
    private const val FLOOR = 0.35f // mirrors rM2 inkStrokeWidth (inkitem.cpp:38)
    private const val MIN_W = 1f
    private const val DEFAULT_MAX_W = 9f

    private fun width(pressure: Int, maxW: Float): Float {
        val p = (((pressure / PRESSURE_MAX) - FLOOR) / (1f - FLOOR)).coerceIn(0f, 1f)
        return MIN_W + p * p * (maxW - MIN_W)
    }

    fun strokeSegments(points: List<InkPoint>, baseWidth: Float): List<InkSegment> {
        val maxW = if (baseWidth > MIN_W) baseWidth else DEFAULT_MAX_W
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) {
            val p = points[0]
            return listOf(InkSegment(p.x, p.y, p.x, p.y, width(p.pressure, maxW)))
        }
        return points.zipWithNext { a, b ->
            InkSegment(a.x, a.y, b.x, b.y, width(b.pressure, maxW))
        }
    }
}
```

- [ ] **Step 4: Run to verify PASS**

Same command as Step 2. Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/InkSnapshot.kt android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/InkPoints.kt android/app/src/test/java/tech/mrzeapple/ciphercodex/sync/webdav/InkPointsTest.kt
git commit -m "feat(notes): ink wire models + point decoder + pressure-width geometry"
```

---

### Task 2: Room v8 — notebooks/pages tables + NotesDao

**Files:**
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/data/db/Entities.kt` (append two entities)
- Create: `android/app/src/main/java/tech/mrzeapple/ciphercodex/data/db/NotesDao.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/data/db/AppDatabase.kt`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `NotebookEntity(id, guid, title, createdAt, updatedAt)`, `NotebookPageEntity(id, guid, notebookGuid, seq, updatedAt, contentStamp, imagePath)`, `AppDatabase.notesDao(): NotesDao`, and the `NotesDao` methods exactly as below.

- [ ] **Step 1: Entities**

Append to `Entities.kt` (match the file's existing style; note NO `deleted` columns — ink tombstones hard-delete):

```kotlin
/** rM2 notebook metadata mirrored from sync snapshots. Read-only on Android;
 *  ink tombstones hard-delete rows, so no deleted column. */
@Entity(tableName = "notebooks", indices = [Index(value = ["guid"], unique = true)])
data class NotebookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val guid: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "notebook_pages",
    indices = [Index(value = ["guid"], unique = true), Index("notebookGuid")],
)
data class NotebookPageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val guid: String,
    val notebookGuid: String,
    val seq: Int,
    val updatedAt: Long,
    /** max(stroke.updatedAt)*31 + liveStrokeCount at last render; -1 = never rendered. */
    val contentStamp: Long,
    val imagePath: String,
)
```

- [ ] **Step 2: NotesDao**

```kotlin
package tech.mrzeapple.ciphercodex.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Notebook metadata access. UI observes; the sync ink pass writes. */
@Dao
interface NotesDao {
    @Query("SELECT * FROM notebooks ORDER BY updatedAt DESC")
    fun observeNotebooks(): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebook_pages ORDER BY seq")
    fun observeAllPages(): Flow<List<NotebookPageEntity>>

    @Query("SELECT * FROM notebooks") suspend fun allNotebooks(): List<NotebookEntity>
    @Query("SELECT * FROM notebook_pages") suspend fun allPages(): List<NotebookPageEntity>
    @Query("SELECT * FROM notebooks WHERE guid = :guid") suspend fun notebookByGuid(guid: String): NotebookEntity?
    @Query("SELECT * FROM notebook_pages WHERE guid = :guid") suspend fun pageByGuid(guid: String): NotebookPageEntity?

    @Upsert suspend fun upsertNotebook(n: NotebookEntity)
    @Upsert suspend fun upsertPage(p: NotebookPageEntity)

    @Query("UPDATE notebook_pages SET imagePath = :imagePath, contentStamp = :contentStamp WHERE guid = :guid")
    suspend fun setPageImage(guid: String, imagePath: String, contentStamp: Long)

    @Query("DELETE FROM notebooks WHERE guid = :guid") suspend fun deleteNotebook(guid: String)
    @Query("DELETE FROM notebook_pages WHERE guid = :guid") suspend fun deletePage(guid: String)
    @Query("DELETE FROM notebook_pages WHERE notebookGuid = :notebookGuid")
    suspend fun deletePagesOf(notebookGuid: String)
}
```

- [ ] **Step 3: AppDatabase v8 + migration**

In `AppDatabase.kt`: add both entities to the `entities = [...]` list, bump `version = 8`, add `abstract fun notesDao(): NotesDao`, add `MIGRATION_7_8` to the companion and to `.addMigrations(...)`:

```kotlin
private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `notebooks` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`guid` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_notebooks_guid` ON `notebooks` (`guid`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `notebook_pages` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`guid` TEXT NOT NULL, " +
                "`notebookGuid` TEXT NOT NULL, " +
                "`seq` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "`contentStamp` INTEGER NOT NULL, " +
                "`imagePath` TEXT NOT NULL)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_notebook_pages_guid` ON `notebook_pages` (`guid`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_notebook_pages_notebookGuid` ON `notebook_pages` (`notebookGuid`)")
    }
}
```

- [ ] **Step 4: Build + existing tests**

Run from `android/`: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (Room validates entity↔SQL match at compile; runtime match is checked in Task 5's migration gate).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/tech/mrzeapple/ciphercodex/data/db/
git commit -m "feat(notes): Room v8 — notebooks/notebook_pages metadata tables + NotesDao"
```

---

### Task 3: Ink merge core (TDD) + renderer + sync wiring

**Files:**
- Create: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/InkMerge.kt`
- Create: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/InkSync.kt`
- Test: `android/app/src/test/java/tech/mrzeapple/ciphercodex/sync/webdav/InkMergeTest.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/WebDavSyncManager.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/CipherCodexApp.kt`

**Interfaces:**
- Consumes: Task 1 (`InkSnapshot*`, `InkPoints`, `InkGeometry`), Task 2 (`NotesDao`, entities), existing `SnapshotMerge.wins`.
- Produces: `InkMerge.mergeNotebooks/mergePages/liveStrokesByPage/contentStamp` (below); `InkSync(db, notebooksDir).apply(texts: List<String>)`; `WebDavSyncManager` gains a `notebooksDir: File` constructor parameter (5th, after `cacheDir`).

- [ ] **Step 1: Write the failing merge tests**

```kotlin
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
```

- [ ] **Step 2: Run to verify FAIL**

Run from `android/`: `.\gradlew.bat :app:testDebugUnitTest --tests "tech.mrzeapple.ciphercodex.sync.webdav.InkMergeTest"`
Expected: compile FAILURE (`Unresolved reference 'InkMerge'`).

- [ ] **Step 3: Implement InkMerge (pure)**

```kotlin
package tech.mrzeapple.ciphercodex.sync.webdav

/** Pure LWW merge of the ink arrays across device snapshots. Reuses the
 *  frozen tie rules from SnapshotMerge.wins. */
object InkMerge {

    private fun <V> lww(
        snapshots: List<InkSnapshot>, rows: (InkSnapshot) -> List<V>,
        key: (V) -> String, updatedAt: (V) -> Long, deleted: (V) -> Int,
    ): Map<String, V> {
        val out = HashMap<String, V>()
        for (snap in snapshots) for (r in rows(snap)) {
            val cur = out[key(r)]
            if (cur == null || SnapshotMerge.wins(updatedAt(r), deleted(r), updatedAt(cur), deleted(cur))) {
                out[key(r)] = r
            }
        }
        return out
    }

    fun mergeNotebooks(snapshots: List<InkSnapshot>): Map<String, InkNotebook> =
        lww(snapshots, { it.notebooks }, { it.guid }, { it.updatedAt }, { it.deleted })

    fun mergePages(snapshots: List<InkSnapshot>): Map<String, InkPage> =
        lww(snapshots, { it.pages }, { it.guid }, { it.updatedAt }, { it.deleted })

    /** LWW per stroke guid first (a tombstone beats an older live copy), then
     *  group the surviving live strokes by page. */
    fun liveStrokesByPage(snapshots: List<InkSnapshot>): Map<String, List<InkStroke>> =
        lww(snapshots, { it.strokes }, { it.guid }, { it.updatedAt }, { it.deleted })
            .values.filter { it.deleted == 0 }
            .groupBy { it.pageGuid }

    fun contentStamp(liveStrokes: List<InkStroke>): Long =
        if (liveStrokes.isEmpty()) 0L
        else liveStrokes.maxOf { it.updatedAt } * 31 + liveStrokes.size
}
```

- [ ] **Step 4: Run to verify PASS**

Same command as Step 2. Expected: PASS (5 tests).

- [ ] **Step 5: InkSync (renderer + DB apply, Android side — no unit test; geometry and merge are already covered)**

`InkSync.kt`:

```kotlin
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
            val strokes = strokesByPage[page.guid].orEmpty()
            val stamp = InkMerge.contentStamp(strokes)
            if (stamp == page.contentStamp && page.imagePath.isNotEmpty()) continue
            val dest = File(notebooksDir, "${page.guid}.png")
            renderPage(strokes, dest)
            dao.setPageImage(page.guid, dest.absolutePath, stamp)
            rendered++
        }
        return InkResult(notebooks.count { it.value.deleted == 0 }, rendered, removed)
    }

    /** White 1404x1872 page, black pressure-width ink. Atomic: temp then rename. */
    private fun renderPage(strokes: List<InkStroke>, dest: File) {
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
            if (!tmp.renameTo(dest)) { dest.delete(); tmp.renameTo(dest) }
        } finally {
            bmp.recycle()
        }
    }
}
```

- [ ] **Step 6: Wire into WebDavSyncManager + CipherCodexApp**

In `WebDavSyncManager.kt`:

1. Constructor gains a 5th parameter: `private val notebooksDir: File,` (after `cacheDir`), and a field `private val inkSync = InkSync(db, notebooksDir)`.
2. In `doSync()`, replace the step-2 block

```kotlin
        // 2. Pull every device snapshot (unparsable one aborts: better no sync than a partial merge).
        val snapshots = dav.list("state/")
            .filter { it.endsWith(".json") }
            .map { SnapshotJson.decode(dav.get("state/$it").decodeToString()) }
```

with (keep the raw texts for the ink pass):

```kotlin
        // 2. Pull every device snapshot (unparsable one aborts: better no sync than a partial merge).
        val stateTexts = dav.list("state/")
            .filter { it.endsWith(".json") }
            .map { dav.get("state/$it").decodeToString() }
        val snapshots = stateTexts.map { SnapshotJson.decode(it) }
```

3. After step 5 (the PUT/MOVE push) and before `prefs.setWebdavLastSyncAt(...)`, add the contained ink pass:

```kotlin
        // 6. Ink pass (rM2 notebooks -> local viewer). Contained: never fails book sync.
        val inkError = try { inkSync.apply(stateTexts); null } catch (e: Exception) {
            "notes: ${e.message ?: e.javaClass.simpleName}"
        }
```

and change the return to carry it:

```kotlin
        return WebDavSummary(booksUp, booksDown, result.entities, result.tombstones, error = inkError)
```

In `CipherCodexApp.kt`, the `webdavSync` lazy becomes:

```kotlin
    val webdavSync: WebDavSyncManager by lazy {
        WebDavSyncManager(prefs, database, repository, cacheDir, File(filesDir, "notebooks"))
    }
```

(add `import java.io.File`).

- [ ] **Step 7: Build + full unit suite**

Run from `android/`: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/tech/mrzeapple/ciphercodex/ android/app/src/test/java/tech/mrzeapple/ciphercodex/
git commit -m "feat(notes): ink pass — LWW notebook/page merge, PNG page renderer, sync wiring"
```

---

### Task 4: NOTES tab UI

**Files:**
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/ui/components/CipherIcons.kt` (add one icon)
- Create: `android/app/src/main/java/tech/mrzeapple/ciphercodex/ui/notes/NotesViewModel.kt`
- Create: `android/app/src/main/java/tech/mrzeapple/ciphercodex/ui/notes/NotesScreen.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/ui/MainScaffold.kt`

**Interfaces:**
- Consumes: Task 2 (`NotesDao.observeNotebooks/observeAllPages`, entities).
- Produces: `NotesScreen()` composable; `CipherIconNotes`.

- [ ] **Step 1: Icon**

Append to `CipherIcons.kt` (feather file-text, matching the existing strokeIcon style):

```kotlin
/** Document with text lines — the NOTES (rM2 notebooks) tab. */
val CipherIconNotes: ImageVector = strokeIcon(
    "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z",
    "M14 2v6h6",
    "M16 13H8",
    "M16 17H8",
)
```

- [ ] **Step 2: ViewModel**

`NotesViewModel.kt`:

```kotlin
package tech.mrzeapple.ciphercodex.ui.notes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import tech.mrzeapple.ciphercodex.CipherCodexApp
import tech.mrzeapple.ciphercodex.data.db.NotebookEntity
import tech.mrzeapple.ciphercodex.data.db.NotebookPageEntity

/** One card in the NOTES grid: a notebook plus its rendered pages (by seq). */
data class NotebookCard(
    val notebook: NotebookEntity,
    val pages: List<NotebookPageEntity>,
) {
    val coverPath: String? get() = pages.firstOrNull { it.imagePath.isNotEmpty() }?.imagePath
}

class NotesViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = (application as CipherCodexApp).database.notesDao()

    val notebooks: StateFlow<List<NotebookCard>> =
        combine(dao.observeNotebooks(), dao.observeAllPages()) { nbs, pages ->
            val bynb = pages.groupBy { it.notebookGuid }
            nbs.map { NotebookCard(it, bynb[it.guid].orEmpty().sortedBy { p -> p.seq }) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
```

- [ ] **Step 3: Screen**

`NotesScreen.kt` — notebook grid + full-screen zoomable pager. Read the LIBRARY/KEPT screens first and mirror their scaffolding vocabulary (`CipherPanel`/`CipherCaption`/header composables — use the exact names found there). Core structure:

```kotlin
package tech.mrzeapple.ciphercodex.ui.notes

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decode a PNG off the main thread, downsampled to roughly maxDim on the long side. */
@Composable
private fun rememberPageBitmap(path: String, maxDim: Int) = produceState<android.graphics.Bitmap?>(null, path) {
    value = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

@Composable
fun NotesScreen(vm: NotesViewModel = viewModel()) {
    val cards by vm.notebooks.collectAsState()
    var open by remember { mutableStateOf<NotebookCard?>(null) }

    val current = open
    if (current != null) {
        PageViewer(card = cards.firstOrNull { it.notebook.guid == current.notebook.guid } ?: current,
            onClose = { open = null })
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("NOTES", style = MaterialTheme.typography.titleLarge)
        if (cards.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("NOTES SYNC FROM YOUR REMARKABLE 2",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                items(cards, key = { it.notebook.guid }) { card ->
                    Column(Modifier.clickable { open = card }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1404f / 1872f)
                            .background(MaterialTheme.colorScheme.surfaceVariant)) {
                            val cover = card.coverPath
                            if (cover != null) {
                                val bmp by rememberPageBitmap(cover, maxDim = 480)
                                bmp?.let {
                                    Image(it.asImageBitmap(), contentDescription = card.notebook.title,
                                        modifier = Modifier.fillMaxSize())
                                }
                            }
                        }
                        Text(card.notebook.title.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 6.dp))
                        Text("${card.pages.size} PAGES",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun PageViewer(card: NotebookCard, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val pager = rememberPagerState { card.pages.size }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Text("${card.notebook.title.uppercase()} // PAGE ${pager.currentPage + 1}/${card.pages.size}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(16.dp).clickable(onClick = onClose))
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { index ->
            val page = card.pages[index]
            var scale by remember { mutableStateOf(1f) }
            var offX by remember { mutableStateOf(0f) }
            var offY by remember { mutableStateOf(0f) }
            Box(
                Modifier.fillMaxSize().pointerInput(page.guid) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offX += pan.x; offY += pan.y
                        if (scale == 1f) { offX = 0f; offY = 0f }
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                if (page.imagePath.isNotEmpty()) {
                    val bmp by rememberPageBitmap(page.imagePath, maxDim = 1872)
                    bmp?.let {
                        Image(it.asImageBitmap(), contentDescription = null,
                            modifier = Modifier.fillMaxSize().graphicsLayer(
                                scaleX = scale, scaleY = scale,
                                translationX = offX, translationY = offY))
                    }
                }
            }
        }
    }
}
```

(Adjust imports/`collectAsState` to match the project's existing screens; mirror the exact header/caption composables the other tabs use — e.g. if KEPT uses a `CipherCaption` header, use that instead of the raw `Text` shown above.)

- [ ] **Step 4: MainScaffold — insert the tab**

In `MainScaffold.kt`: import `CipherIconNotes` and `NotesScreen`, insert into `tabs` after KEPT, and shift the `when`:

```kotlin
    val tabs = listOf(
        CipherIconLibrary to "LIBRARY",
        CipherIconKept to "KEPT",
        CipherIconNotes to "NOTES",
        CipherIconStats to "STATS",
        CipherIconApps to "APPS",
        CipherIconSettings to "SET",
    )
```

```kotlin
            when (tab) {
                0 -> LibraryScreen(onOpenBook = onOpenBookDetail, onOpenOpds = onOpenOpds)
                1 -> KeptScreen(onOpenBook = onOpenReader)
                2 -> NotesScreen()
                3 -> StatsScreen()
                4 -> AppsScreen()
                else -> SettingsScreen()
            }
```

- [ ] **Step 5: Build + full unit suite**

Run from `android/`: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/tech/mrzeapple/ciphercodex/ui/
git commit -m "feat(notes): NOTES tab — notebook grid + zoomable page viewer"
```

---

### Task 5: End-to-end verification + ship v0.6.0

**Files:**
- Modify: `android/app/build.gradle.kts` (version bump only, after QA passes)

- [ ] **Step 1: Migration gate (v7→v8)**

On the `cipher` AVD (`emulator -avd cipher -no-window`, emulator-5554): install the v0.5.0 release APK (`gh release download v0.5.0 --pattern "*.apk"`), open it once (creates a v7 DB), then `adb install -r` the new release-signed build (KEYSTORE_PASSWORD recipe in `keystore/SIGNING.md`). Launch; `adb logcat -d | grep -iE "FATAL|Migration"` → no migration crash; library intact.

- [ ] **Step 2: Live ink round-trip against a scratch WebDAV path**

Using the live endpoint under `https://kosync.cph.gg/ccx/scratch-android/` ONLY (user `ccx`; password from the owner/VPS — never committed or logged; real `books/`/`state/` are read-only):

1. MKCOL `scratch-android/` + `scratch-android/state/`; copy one real rM2 `state/<id>.json` (read-only GET from `ccx/state/` — it contains a notebook with 400+ strokes) into the scratch `state/`.
2. Configure the app at the scratch URL → SYNC NOW → no error in the summary.
3. NOTES tab shows the rM2 notebook: correct title, page count, thumbnails; pages open, swipe, pinch-zoom; strokes visibly pressure-weighted.
4. SYNC NOW again → fast no-op (contentStamp unchanged ⇒ no re-render; verify via timing or logcat).
5. GET `scratch-android/state/<androidDeviceId>.json` → contains NO `notebooks`/`pages`/`strokes` keys (Android still never emits ink).
6. Tombstone: edit the copied rM2 json locally — set the notebook's `deleted:1` with a higher `updatedAt` — PUT it back, SYNC NOW → notebook disappears from NOTES and its PNGs are gone from `filesDir/notebooks/` (`adb shell run-as tech.mrzeapple.ciphercodex ls files/notebooks`).
7. Cleanup: DELETE `scratch-android/`, confirm 404.

- [ ] **Step 3: Ship**

After all gates pass: bump `versionName = "0.6.0"` and `versionCode` +1 in `android/app/build.gradle.kts`:

```bash
git add android/app/build.gradle.kts
git commit -m "release: v0.6.0 — NOTES tab (rM2 notebook viewer)"
```

Do NOT push — merge/release is handled by the finishing-a-development-branch flow after the final whole-branch review.

---

## Self-review notes (already applied)

- Spec coverage: InkSnapshot isolation (Task 1), Room v8 metadata-only (Task 2), LWW + hard-delete tombstones + contentStamp render (Task 3), contained ink-pass failure via `WebDavSummary.error` (Task 3 Step 6), NOTES tab grid/pager/empty-state (Task 4), E2E incl. never-emit check + tombstone check (Task 5).
- Type consistency: `InkStroke.baseWidth: Float` consumed by `InkGeometry.strokeSegments(points, baseWidth)`; `contentStamp` formula identical in spec, `InkMerge.contentStamp`, and `NotebookPageEntity` docs; `notebooksDir` parameter name matches between `WebDavSyncManager` and `CipherCodexApp`.
- Page metadata upsert keeps existing `contentStamp`/`imagePath` (`local.copy(...)`) so a seq/updatedAt change alone doesn't force a re-render; new pages start at `contentStamp = -1` which never equals a computed stamp (≥ 0), guaranteeing first render — including blank pages (stamp 0 ≠ -1).
