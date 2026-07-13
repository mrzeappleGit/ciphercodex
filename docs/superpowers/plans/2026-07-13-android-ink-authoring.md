# Android Ink Authoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Handwrite notes on Android (Boox Go 10.3 Gen 2 stylus) with full two-way ink sync
against the reMarkable 2 — create notebooks/pages, draw, erase, and have both devices'
strokes merge per-guid on the existing WebDAV wire.

**Architecture:** Room v10 gains a `strokes` table that becomes the merged local truth
(the same model the rM2's own SQLite uses). The sync ink pass changes from
"merge wire → transient memory → render PNGs" to "merge wire → Room → render PNGs from
Room". Export gains the three ink arrays by merging a second JSON object into the
snapshot — the frozen `Snapshot.kt` contract file is not touched. A Jetpack Ink editor
(front-buffer wet ink) opens from the NOTES viewer and writes strokes straight to Room.
The rM2 needs **zero code changes**.

**Tech Stack:** Kotlin, Jetpack Compose, Room 2.6.1, kotlinx.serialization, androidx.ink
(Jetpack Ink), existing WebDAV sync engine. Spec:
`docs/superpowers/specs/2026-07-13-android-ink-authoring-design.md`.

## Global Constraints

- Branch: `android-ink-authoring` off main. Shared checkout with parallel sessions — run
  `git branch --show-current` before every commit.
- **Do NOT modify** `Snapshot.kt` / `SnapshotJson` (frozen phase3b contract) or anything
  under `remarkable2-os/` — the rM2 side needs no changes.
- Wire stroke row keys (must match `InkStroke` exactly): `guid`, `pageGuid`, `tool`,
  `baseWidth`, `points_b64` (note the underscore — `@SerialName`), `createdAt`,
  `deleted`, `updatedAt`. Notebook keys: `guid,title,createdAt,deleted,updatedAt`.
  Page keys: `guid,notebookGuid,seq,deleted,updatedAt`.
- Packed point format (rM2 `storage.cpp` PackedPoint, 18 bytes little-endian):
  `float x, float y, u16 pressure, s16 tiltX, s16 tiltY, u32 tMs`. x/y normalized 0..1
  in 1404×1872 page space; pressure 0..4095; tMs relative to stroke start. Android
  writes tiltX = tiltY = 0.
- Android-authored strokes: `tool = 0`, `baseWidth = 9f` (the device-true max width).
- contentStamp formula (cross-device determinism is load-bearing):
  `if (live.isEmpty()) 0L else live.maxOf { it.updatedAt } * 31 + live.size`.
- Room conventions: `notebooks`/`notebook_pages` have NO `deleted` column — inbound
  tombstones hard-delete; Android always emits `deleted = 0` for them. `strokes` DOES
  keep tombstone rows (`deleted = 1`) so the eraser travels and stale copies stay
  out-voted.
- Migration SQL must match Room's generated schema letter-for-letter (v4→v5 lesson).
- Stylus-only input (`MotionEvent.TOOL_TYPE_STYLUS`); finger does nothing in the canvas.
- Automated E2E uses `https://kosync.cph.gg/ccx/scratch-android/` — NEVER the live
  `/ccx/` root. Credential discipline: fetch over ssh into a scratchpad file with
  redirection (never stdout), use via substitution, delete after.
- Build/test gate (PowerShell, from `android/`):
  `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL.
- Version at the gate task: versionName `0.7.0` → `0.8.0`, versionCode `29` → `30`.

## File Structure

```
android/app/src/main/java/tech/mrzeapple/ciphercodex/
  data/db/Entities.kt            MODIFY  + StrokeEntity
  data/db/NotesDao.kt            MODIFY  + stroke queries
  data/db/AppDatabase.kt         MODIFY  v10 + MIGRATION_9_10
  data/InkAuthor.kt              CREATE  notebook/page/stroke authoring ops + local re-render
  sync/webdav/InkPoints.kt       MODIFY  + encode()
  sync/webdav/InkRender.kt       CREATE  page PNG renderer (extracted from InkSync)
  sync/webdav/InkMerge.kt        MODIFY  + mergeStrokes(), entity contentStamp overload
  sync/webdav/InkSnapshot.kt     MODIFY  + encodeMerged() (export path)
  sync/webdav/InkSync.kt         MODIFY  merge into Room; render/recognize from Room
  sync/webdav/WebDavSyncManager.kt MODIFY export ink arrays in step 5
  ui/notes/InkHitTest.kt         CREATE  pure eraser hit-test
  ui/notes/InkEditorViewModel.kt CREATE  editor state, tools, undo/redo
  ui/notes/InkEditorScreen.kt    CREATE  Jetpack Ink canvas + tool row
  ui/notes/NotesScreen.kt        MODIFY  EDIT/NEW PAGE/NEW NOTEBOOK entry points
  CipherCodexApp.kt              MODIFY  + inkAuthor, + appScope
android/app/src/test/java/tech/mrzeapple/ciphercodex/
  sync/webdav/InkPointsTest.kt   MODIFY  encode round-trip
  sync/webdav/InkMergeTest.kt    CREATE  stroke LWW + stamp determinism
  sync/webdav/InkSnapshotTest.kt CREATE  encodeMerged round-trip
  ui/notes/InkHitTestTest.kt     CREATE  hit-test cases
android/gradle/libs.versions.toml MODIFY + androidx.ink
android/app/build.gradle.kts     MODIFY  dep + version bump (gate task)
```

---

### Task 1: Room v10 — `strokes` table

**Files:**
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/data/db/Entities.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/data/db/NotesDao.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/data/db/AppDatabase.kt`

**Interfaces:**
- Produces: `StrokeEntity(guid, pageGuid, tool, baseWidth, pointsB64, createdAt, updatedAt, deleted)`;
  NotesDao: `liveStrokesForPage(pageGuid): List<StrokeEntity>`,
  `observeLiveStrokesForPage(pageGuid): Flow<List<StrokeEntity>>`,
  `allStrokes(): List<StrokeEntity>`, `strokeByGuid(guid): StrokeEntity?`,
  `upsertStroke(s)`, `deleteStrokesOf(pageGuid)`.

- [ ] **Step 1: Entity** — append to `Entities.kt`:

```kotlin
/** One ink stroke, the merged local truth (mirrors the wire rows and the rM2's own
 *  strokes table). Tombstones are KEPT (deleted = 1) so the eraser travels on the
 *  wire and out-votes stale live copies — unlike notebooks/pages, which hard-delete. */
@Entity(tableName = "strokes", indices = [Index("pageGuid")])
data class StrokeEntity(
    @PrimaryKey val guid: String,
    val pageGuid: String,
    val tool: Int,
    val baseWidth: Float,
    val pointsB64: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Int,
)
```

- [ ] **Step 2: DAO** — append to `NotesDao.kt`:

```kotlin
@Query("SELECT * FROM strokes WHERE pageGuid = :pageGuid AND deleted = 0 ORDER BY createdAt")
suspend fun liveStrokesForPage(pageGuid: String): List<StrokeEntity>

@Query("SELECT * FROM strokes WHERE pageGuid = :pageGuid AND deleted = 0 ORDER BY createdAt")
fun observeLiveStrokesForPage(pageGuid: String): Flow<List<StrokeEntity>>

@Query("SELECT * FROM strokes") suspend fun allStrokes(): List<StrokeEntity>
@Query("SELECT * FROM strokes WHERE guid = :guid") suspend fun strokeByGuid(guid: String): StrokeEntity?
@Upsert suspend fun upsertStroke(s: StrokeEntity)
@Query("DELETE FROM strokes WHERE pageGuid = :pageGuid") suspend fun deleteStrokesOf(pageGuid: String)
```

- [ ] **Step 3: Database** — in `AppDatabase.kt`: add `StrokeEntity::class` to the
  `entities` list, bump `version = 10`, add and register:

```kotlin
private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // letter-for-letter what Room generates for StrokeEntity (v4->v5 lesson)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `strokes` (`guid` TEXT NOT NULL, " +
                "`pageGuid` TEXT NOT NULL, `tool` INTEGER NOT NULL, " +
                "`baseWidth` REAL NOT NULL, `pointsB64` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "`deleted` INTEGER NOT NULL, PRIMARY KEY(`guid`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_strokes_pageGuid` ON `strokes` (`pageGuid`)")
    }
}
```

- [ ] **Step 4: Verify** — `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` →
  BUILD SUCCESSFUL (KSP validates entity/DAO/migration wiring at compile time; the live
  migration is gated on the AVD in Task 8).

- [ ] **Step 5: Commit** — `git commit -m "feat(ink): Room v10 — strokes table (merged local ink truth)"`

---

### Task 2: `InkPoints.encode`

**Files:**
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/InkPoints.kt`
- Test: `android/app/src/test/java/tech/mrzeapple/ciphercodex/sync/webdav/InkPointsTest.kt`

**Interfaces:**
- Produces: `InkPoints.encode(points: List<InkPoint>): String` — exact inverse of the
  existing `decode`.

- [ ] **Step 1: Failing test** — add to the existing `InkPointsTest.kt`:

```kotlin
@Test
fun `encode is the exact inverse of decode`() {
    val pts = listOf(
        InkPoint(0.0f, 1.0f, 0, 0L),
        InkPoint(0.25f, 0.75f, 4095, 40L),
        InkPoint(0.5f, 0.5f, 2048, 4_294_967_295L), // u32 max survives
    )
    assertEquals(pts, InkPoints.decode(InkPoints.encode(pts)))
    assertEquals("", InkPoints.encode(emptyList()))
}
```

- [ ] **Step 2: Verify FAIL** —
  `.\gradlew.bat :app:testDebugUnitTest --tests "*InkPointsTest*"` → unresolved `encode`.

- [ ] **Step 3: Implement** — add to the `InkPoints` object:

```kotlin
/** Inverse of [decode]: 18-byte little-endian PackedPoint rows, tilt written as 0. */
fun encode(points: List<InkPoint>): String {
    if (points.isEmpty()) return ""
    val buf = ByteBuffer.allocate(points.size * BYTES_PER_POINT).order(ByteOrder.LITTLE_ENDIAN)
    for (p in points) {
        buf.putFloat(p.x)
        buf.putFloat(p.y)
        buf.putShort((p.pressure and 0xFFFF).toShort())
        buf.putShort(0) // tiltX
        buf.putShort(0) // tiltY
        buf.putInt((p.t and 0xFFFFFFFFL).toInt())
    }
    return Base64.getEncoder().encodeToString(buf.array())
}
```

- [ ] **Step 4: Verify PASS**, full suite green.

- [ ] **Step 5: Commit** — `git commit -m "feat(ink): InkPoints.encode — packed-point wire encoding"`

---

### Task 3: `InkRender` extraction + `InkAuthor`

**Files:**
- Create: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/InkRender.kt`
- Create: `android/app/src/main/java/tech/mrzeapple/ciphercodex/data/InkAuthor.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/InkSync.kt` (use InkRender)
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/CipherCodexApp.kt`

**Interfaces:**
- Consumes: `InkPoints.encode` (Task 2), `StrokeEntity`/NotesDao (Task 1),
  `InkGeometry.strokeSegments`, `Guids.new()`.
- Produces:
  - `object InkRender { const val PAGE_W = 1404; const val PAGE_H = 1872; fun renderPage(strokes: List<Pair<String, Float>>, dest: File): Boolean }`
    — each pair is `(pointsB64, baseWidth)`; body is InkSync's current private
    `renderPage` verbatim (temp-then-rename atomicity comment included), mapping pairs
    where it used `InkStroke` fields.
  - `class InkAuthor(db: AppDatabase, notebooksDir: File)` with:
    `suspend fun createNotebook(title: String): String`,
    `suspend fun createPage(notebookGuid: String): String`,
    `suspend fun commitStroke(pageGuid: String, points: List<InkPoint>): StrokeEntity?`,
    `suspend fun eraseStroke(guid: String): StrokeEntity?` (returns the pre-tombstone row for undo),
    `suspend fun restoreStroke(s: StrokeEntity)`,
    `suspend fun renderPageNow(pageGuid: String)`.

- [ ] **Step 1: InkRender** — extract InkSync's `renderPage` into the new object; delete
  the private method and the `PAGE_W/PAGE_H` companion from `InkSync`, replacing call
  sites with `InkRender.renderPage(strokes.map { it.pointsB64 to it.baseWidth }, dest)`
  and `InkRender.PAGE_W`. Grep for other `InkSync.PAGE_W` consumers first
  (`Grep "InkSync.PAGE"`); update any.

- [ ] **Step 2: InkAuthor** — new file:

```kotlin
package tech.mrzeapple.ciphercodex.data

import java.io.File
import tech.mrzeapple.ciphercodex.data.db.AppDatabase
import tech.mrzeapple.ciphercodex.data.db.NotebookEntity
import tech.mrzeapple.ciphercodex.data.db.NotebookPageEntity
import tech.mrzeapple.ciphercodex.data.db.StrokeEntity
import tech.mrzeapple.ciphercodex.sync.Guids
import tech.mrzeapple.ciphercodex.sync.webdav.InkMerge
import tech.mrzeapple.ciphercodex.sync.webdav.InkPoint
import tech.mrzeapple.ciphercodex.sync.webdav.InkPoints
import tech.mrzeapple.ciphercodex.sync.webdav.InkRender

/** Local ink authoring: notebooks, pages, and strokes born on this device.
 *  Everything lands in Room with fresh guids/updatedAt and rides the next export. */
class InkAuthor(private val db: AppDatabase, private val notebooksDir: File) {
    private val dao get() = db.notesDao()

    suspend fun createNotebook(title: String): String {
        val now = System.currentTimeMillis()
        val guid = Guids.new()
        dao.upsertNotebook(NotebookEntity(guid = guid, title = title, createdAt = now, updatedAt = now))
        return guid
    }

    suspend fun createPage(notebookGuid: String): String {
        val now = System.currentTimeMillis()
        val guid = Guids.new()
        val seq = (dao.allPages().filter { it.notebookGuid == notebookGuid }
            .maxOfOrNull { it.seq } ?: -1) + 1
        dao.upsertPage(NotebookPageEntity(guid = guid, notebookGuid = notebookGuid,
            seq = seq, updatedAt = now, contentStamp = -1, imagePath = ""))
        return guid
    }

    /** Degenerate strokes (no points) are dropped, never stored. */
    suspend fun commitStroke(pageGuid: String, points: List<InkPoint>): StrokeEntity? {
        if (points.isEmpty()) return null
        val now = System.currentTimeMillis()
        val s = StrokeEntity(guid = Guids.new(), pageGuid = pageGuid, tool = 0,
            baseWidth = 9f, pointsB64 = InkPoints.encode(points),
            createdAt = now, updatedAt = now, deleted = 0)
        dao.upsertStroke(s)
        return s
    }

    suspend fun eraseStroke(guid: String): StrokeEntity? {
        val s = dao.strokeByGuid(guid) ?: return null
        if (s.deleted == 1) return null
        dao.upsertStroke(s.copy(deleted = 1, updatedAt = System.currentTimeMillis()))
        return s
    }

    /** Undo of an erase (or redo of an add): fresh updatedAt so it wins LWW. */
    suspend fun restoreStroke(s: StrokeEntity) {
        dao.upsertStroke(s.copy(deleted = 0, updatedAt = System.currentTimeMillis()))
    }

    /** Re-render this page's PNG + stamp from current Room strokes, so the viewer and
     *  thumbnails are correct immediately (and offline), not only after the next sync. */
    suspend fun renderPageNow(pageGuid: String) {
        val page = dao.pageByGuid(pageGuid) ?: return
        val live = dao.liveStrokesForPage(pageGuid)
        val stamp = InkMerge.contentStamp(live)
        if (stamp == page.contentStamp && page.imagePath.isNotEmpty()) return
        notebooksDir.mkdirs()
        val dest = File(notebooksDir, "$pageGuid.png")
        if (InkRender.renderPage(live.map { it.pointsB64 to it.baseWidth }, dest)) {
            dao.setPageImage(pageGuid, dest.absolutePath, stamp)
        }
    }
}
```

  `InkMerge.contentStamp(List<StrokeEntity>)` does not exist yet — Task 4 adds it. For
  THIS task compute inline: `if (live.isEmpty()) 0L else live.maxOf { it.updatedAt } * 31 + live.size`
  and leave a `// Task 4 replaces with InkMerge.contentStamp(live)` marker only if Task 4
  is not yet merged when you build; if you are executing tasks in order, implement the
  overload in Task 4 and use the inline formula here.

- [ ] **Step 3: App DI** — in `CipherCodexApp`:

```kotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tech.mrzeapple.ciphercodex.data.InkAuthor

/** Outlives any ViewModel: editor-exit render + sync must survive screen close. */
val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
val inkAuthor: InkAuthor by lazy { InkAuthor(database, File(filesDir, "notebooks")) }
```

- [ ] **Step 4: Verify** — full build + unit suite green (render behavior unchanged —
  existing tests cover the pipeline indirectly; no new pure-JVM seam here).

- [ ] **Step 5: Commit** — `git commit -m "feat(ink): InkAuthor — local notebook/page/stroke authoring + shared InkRender"`

---

### Task 4: InkSync merges into Room; render/recognize from Room

**Files:**
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/InkMerge.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/InkSync.kt`
- Test: `android/app/src/test/java/tech/mrzeapple/ciphercodex/sync/webdav/InkMergeTest.kt` (create)

**Interfaces:**
- Consumes: Task 1 DAO methods, `SnapshotMerge.wins`, existing `InkMerge.lww`.
- Produces: `InkMerge.mergeStrokes(snapshots): Map<String, InkStroke>` (ALL winners,
  tombstones included); `InkMerge.contentStamp(live: List<StrokeEntity>): Long` overload.
  `InkSync.apply` behavior change: Room `strokes` is the merge target and render source.

- [ ] **Step 1: Failing tests** — new `InkMergeTest.kt`:

```kotlin
package tech.mrzeapple.ciphercodex.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.mrzeapple.ciphercodex.data.db.StrokeEntity

class InkMergeTest {
    private fun snap(vararg strokes: InkStroke) = InkSnapshot(deviceId = "d", strokes = strokes.toList())
    private fun stroke(guid: String, deleted: Int, at: Long) =
        InkStroke(guid = guid, pageGuid = "pg", deleted = deleted, updatedAt = at)

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
```

- [ ] **Step 2: Verify FAIL** —
  `.\gradlew.bat :app:testDebugUnitTest --tests "*InkMergeTest*"` → unresolved
  `mergeStrokes` / ambiguous `contentStamp`.

- [ ] **Step 3: InkMerge** — add:

```kotlin
/** LWW per stroke guid, tombstones INCLUDED — the caller persists winners so a
 *  tombstone can out-vote a stale live copy arriving later. */
fun mergeStrokes(snapshots: List<InkSnapshot>): Map<String, InkStroke> =
    lww(snapshots, { it.strokes }, { it.guid }, { it.updatedAt }, { it.deleted })

@JvmName("contentStampEntities")
fun contentStamp(liveStrokes: List<tech.mrzeapple.ciphercodex.data.db.StrokeEntity>): Long =
    if (liveStrokes.isEmpty()) 0L
    else liveStrokes.maxOf { it.updatedAt } * 31 + liveStrokes.size
```

  (`liveStrokesByPage` and the wire `contentStamp` stay — the recognition/render loop
  stops using them but keep the wire overload for the determinism test; delete
  `liveStrokesByPage` if InkSync no longer calls it and nothing else does.)
  Swap InkAuthor's inline stamp formula (Task 3) to `InkMerge.contentStamp(live)` now.

- [ ] **Step 4: InkSync rework** — in `apply()`:

  1. Replace `val strokesByPage = InkMerge.liveStrokesByPage(snaps)` with
     `val strokes = InkMerge.mergeStrokes(snaps)`.
  2. In the notebook-tombstone branch, add `dao.deleteStrokesOf(it.guid)` beside the
     existing `dao.deletePageText(it.guid)` in the per-page loop.
  3. In the page-tombstone branch, add `dao.deleteStrokesOf(guid)` beside
     `dao.deletePageText(guid)`.
  4. After the pages loop, INSIDE the same transaction, add the stroke merge:

```kotlin
for ((guid, s) in strokes) {
    val local = dao.strokeByGuid(guid)
    if (local == null) {
        if (s.deleted == 1) continue // nothing local to tombstone; don't import a corpse
        if (dao.pageByGuid(s.pageGuid) == null) continue // missing parent: converges next sync
        dao.upsertStroke(StrokeEntity(guid = guid, pageGuid = s.pageGuid, tool = s.tool,
            baseWidth = s.baseWidth, pointsB64 = s.pointsB64, createdAt = s.createdAt,
            updatedAt = s.updatedAt, deleted = 0))
    } else if (SnapshotMerge.wins(s.updatedAt, s.deleted, local.updatedAt, local.deleted)) {
        dao.upsertStroke(local.copy(pageGuid = s.pageGuid, pointsB64 = s.pointsB64,
            baseWidth = s.baseWidth, updatedAt = s.updatedAt, deleted = s.deleted))
    }
}
```

  5. Render/recognize loop: Room is now the truth. Replace the wire-driven guard and
     stroke source —

```kotlin
for (page in dao.allPages()) {
    val live = dao.liveStrokesForPage(page.guid)
    val stamp = InkMerge.contentStamp(live)
    // ... recognition block unchanged except the mapping:
    //     RecStroke(InkPoints.decode(it.pointsB64), it.createdAt)  — now from StrokeEntity
    if (stamp == page.contentStamp && page.imagePath.isNotEmpty()) continue
    val dest = File(notebooksDir, "${page.guid}.png")
    if (InkRender.renderPage(live.map { it.pointsB64 to it.baseWidth }, dest)) {
        dao.setPageImage(page.guid, dest.absolutePath, stamp)
        rendered++
    }
}
```

     DELETE the old `if (!pages.containsKey(page.guid)) continue` guard AND its
     comment block — its reason (a missing snapshot must not blank a page) is void now
     that strokes persist locally; a failed snapshot decode simply merges nothing new.
  6. Update the class doc comment: Android now authors and emits ink; Room `strokes`
     is the merged truth.

- [ ] **Step 5: Verify** — focused tests pass, then the full gate. Existing tests that
  asserted the transient pipeline may need their expectations updated to the Room path —
  judge each: the assertion's intent (LWW, tombstone, containment) must survive.

- [ ] **Step 6: Commit** — `git commit -m "feat(ink): sync merges strokes into Room; render + recognize from local truth"`

---

### Task 5: Export the ink arrays

**Files:**
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/InkSnapshot.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/WebDavSyncManager.kt`
- Test: `android/app/src/test/java/tech/mrzeapple/ciphercodex/sync/webdav/InkSnapshotTest.kt` (create)

**Interfaces:**
- Consumes: Task 1 DAO (`allNotebooks/allPages/allStrokes`), `Snapshot`/`SnapshotJson`
  (READ ONLY — do not modify that file).
- Produces: `InkSnapshotJson.encodeMerged(base: Snapshot, ink: InkSnapshot): String` —
  one JSON object carrying both the frozen book arrays and the ink arrays.

- [ ] **Step 1: Failing test** — new `InkSnapshotTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Verify FAIL** — unresolved `encodeMerged`.

- [ ] **Step 3: Implement** — in `InkSnapshot.kt`, replace the decode-only doc comment
  (the "can never grow ink keys" rationale is retired by this feature — say so) and add:

```kotlin
object InkSnapshotJson {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    fun decode(text: String): InkSnapshot = json.decodeFromString(InkSnapshot.serializer(), text)

    /** One wire object: the frozen Snapshot fields plus the ink arrays. Snapshot.kt
     *  stays untouched and the two decode failure domains stay separate — a malformed
     *  ink row degrades the ink pass, never book sync. */
    fun encodeMerged(base: Snapshot, ink: InkSnapshot): String {
        val b = json.encodeToJsonElement(Snapshot.serializer(), base).jsonObject
        val i = json.encodeToJsonElement(InkSnapshot.serializer(), ink).jsonObject
        return JsonObject(b + i).toString()
    }
}
```

  Imports: `kotlinx.serialization.json.JsonObject`, `kotlinx.serialization.json.jsonObject`,
  `kotlinx.serialization.json.encodeToJsonElement`. Both objects carry `deviceId`; pass
  the same value so the overwrite is a no-op.

- [ ] **Step 4: Wire into doSync** — in `WebDavSyncManager.doSync()` step 5, replace

```kotlin
val out = SnapshotJson.encode(exportSnapshot(deviceId))
```

  with

```kotlin
val out = InkSnapshotJson.encodeMerged(exportSnapshot(deviceId), exportInk(deviceId))
```

  and add:

```kotlin
/** Full local ink state. Notebooks/pages have no deleted column (inbound tombstones
 *  hard-delete), so they always emit deleted = 0; stroke tombstones DO travel. */
private suspend fun exportInk(deviceId: String): InkSnapshot {
    val dao = db.notesDao()
    return InkSnapshot(
        deviceId = deviceId,
        notebooks = dao.allNotebooks().map {
            InkNotebook(guid = it.guid, title = it.title, createdAt = it.createdAt,
                deleted = 0, updatedAt = it.updatedAt) },
        pages = dao.allPages().map {
            InkPage(guid = it.guid, notebookGuid = it.notebookGuid, seq = it.seq,
                deleted = 0, updatedAt = it.updatedAt) },
        strokes = dao.allStrokes().map {
            InkStroke(guid = it.guid, pageGuid = it.pageGuid, tool = it.tool,
                baseWidth = it.baseWidth, pointsB64 = it.pointsB64,
                createdAt = it.createdAt, deleted = it.deleted, updatedAt = it.updatedAt) },
    )
}
```

  Also update the step-5 comment and the InkSync class doc if Task 4 didn't already.

- [ ] **Step 5: Verify** — focused test green, full gate green.

- [ ] **Step 6: Commit** — `git commit -m "feat(ink): export notebooks/pages/strokes — Android emits ink"`

---

### Task 6: Jetpack Ink dependency + editor ViewModel + hit-test

**Files:**
- Modify: `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts` (dependency only)
- Create: `android/app/src/main/java/tech/mrzeapple/ciphercodex/ui/notes/InkHitTest.kt`
- Create: `android/app/src/main/java/tech/mrzeapple/ciphercodex/ui/notes/InkEditorViewModel.kt`
- Test: `android/app/src/test/java/tech/mrzeapple/ciphercodex/ui/notes/InkHitTestTest.kt`

**Interfaces:**
- Consumes: `InkAuthor` (Task 3), `NotesDao.observeLiveStrokesForPage` (Task 1),
  `CipherCodexApp.appScope/inkAuthor/webdavSync`.
- Produces:
  - `InkHitTest.strokeAt(strokes: List<Pair<String, List<InkPoint>>>, x: Float, y: Float, tol: Float): String?`
  - `InkEditorViewModel(app: CipherCodexApp, pageGuid: String)` with
    `strokes: StateFlow<List<StrokeEntity>>`, `tool: MutableStateFlow<EditorTool>`
    (`enum class EditorTool { PEN, ERASE }`), `commitStroke(points: List<InkPoint>)`,
    `eraseAt(x: Float, y: Float)`, `undo()`, `redo()`,
    `canUndo/canRedo: StateFlow<Boolean>`, `onEditorClosed()`; companion
    `factory(app, pageGuid)`.

- [ ] **Step 1: Dependency** — `libs.versions.toml`:
  `ink = "1.0.0-alpha05"` under `[versions]`;
  `androidx-ink-authoring = { module = "androidx.ink:ink-authoring", version.ref = "ink" }`
  under `[libraries]`. Add `implementation(libs.androidx.ink.authoring)` to
  `app/build.gradle.kts`. If alpha05 does not resolve from google(), check the newest
  available `androidx.ink:ink-authoring` version (e.g.
  `curl https://maven.google.com/androidx/ink/ink-authoring/maven-metadata.xml`) and pin
  that instead — record the chosen version in your report. `ink-authoring` pulls
  strokes/brush/geometry/rendering transitively.

- [ ] **Step 2: Failing hit-test test** — `InkHitTestTest.kt`:

```kotlin
package tech.mrzeapple.ciphercodex.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mrzeapple.ciphercodex.sync.webdav.InkPoint

class InkHitTestTest {
    private fun pts(vararg xy: Pair<Float, Float>) = xy.map { InkPoint(it.first, it.second, 2000) }

    @Test
    fun `hits the nearest stroke within tolerance, misses outside`() {
        val strokes = listOf(
            "a" to pts(0.1f to 0.5f, 0.4f to 0.5f),   // horizontal segment
            "b" to pts(0.5f to 0.52f),                  // single dot, slightly closer to probe 2
        )
        assertEquals("a", InkHitTest.strokeAt(strokes, 0.25f, 0.505f, tol = 0.012f)) // near mid-segment
        assertEquals("b", InkHitTest.strokeAt(strokes, 0.5f, 0.53f, tol = 0.012f))   // near the dot
        assertNull(InkHitTest.strokeAt(strokes, 0.9f, 0.9f, tol = 0.012f))            // far from both
        assertNull(InkHitTest.strokeAt(emptyList(), 0.5f, 0.5f, tol = 0.012f))
    }
}
```

- [ ] **Step 3: Verify FAIL**, then implement `InkHitTest.kt`:

```kotlin
package tech.mrzeapple.ciphercodex.ui.notes

import kotlin.math.hypot
import tech.mrzeapple.ciphercodex.sync.webdav.InkPoint

/** Eraser hit-test in normalized page coords. tol mixes the x/y scales slightly
 *  (0..1 spans 1404 px in x, 1872 px in y) — acceptable for a fingertip-sized eraser. */
object InkHitTest {
    fun strokeAt(strokes: List<Pair<String, List<InkPoint>>>, x: Float, y: Float, tol: Float): String? {
        var best: String? = null
        var bestD = tol
        for ((guid, pts) in strokes) {
            if (pts.isEmpty()) continue
            if (pts.size == 1) {
                val d = hypot(pts[0].x - x, pts[0].y - y)
                if (d <= bestD) { bestD = d; best = guid }
                continue
            }
            for (i in 0 until pts.size - 1) {
                val d = segDist(x, y, pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y)
                if (d <= bestD) { bestD = d; best = guid }
            }
        }
        return best
    }

    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
```

- [ ] **Step 4: ViewModel** — `InkEditorViewModel.kt`:

```kotlin
package tech.mrzeapple.ciphercodex.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.mrzeapple.ciphercodex.CipherCodexApp
import tech.mrzeapple.ciphercodex.data.db.StrokeEntity
import tech.mrzeapple.ciphercodex.sync.webdav.InkPoint
import tech.mrzeapple.ciphercodex.sync.webdav.InkPoints

enum class EditorTool { PEN, ERASE }

class InkEditorViewModel(private val app: CipherCodexApp, val pageGuid: String) : ViewModel() {
    private val author = app.inkAuthor

    val strokes: StateFlow<List<StrokeEntity>> =
        app.database.notesDao().observeLiveStrokesForPage(pageGuid)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tool = MutableStateFlow(EditorTool.PEN)

    private sealed interface Op { val s: StrokeEntity }
    private data class Add(override val s: StrokeEntity) : Op
    private data class Erase(override val s: StrokeEntity) : Op

    private val undoStack = ArrayDeque<Op>()
    private val redoStack = ArrayDeque<Op>()
    val canUndo = MutableStateFlow(false)
    val canRedo = MutableStateFlow(false)
    private fun bump() { canUndo.value = undoStack.isNotEmpty(); canRedo.value = redoStack.isNotEmpty() }

    fun commitStroke(points: List<InkPoint>) {
        viewModelScope.launch {
            author.commitStroke(pageGuid, points)?.let {
                undoStack.addLast(Add(it)); redoStack.clear(); bump()
            }
        }
    }

    fun eraseAt(x: Float, y: Float) {
        val candidates = strokes.value.map { it.guid to InkPoints.decode(it.pointsB64) }
        val hit = InkHitTest.strokeAt(candidates, x, y, tol = 0.012f) ?: return
        viewModelScope.launch {
            author.eraseStroke(hit)?.let { undoStack.addLast(Erase(it)); redoStack.clear(); bump() }
        }
    }

    fun undo() {
        val op = undoStack.removeLastOrNull() ?: return
        viewModelScope.launch {
            when (op) { is Add -> author.eraseStroke(op.s.guid); is Erase -> author.restoreStroke(op.s) }
            redoStack.addLast(op); bump()
        }
    }

    fun redo() {
        val op = redoStack.removeLastOrNull() ?: return
        viewModelScope.launch {
            when (op) { is Add -> author.restoreStroke(op.s); is Erase -> author.eraseStroke(op.s.guid) }
            undoStack.addLast(op); bump()
        }
    }

    /** Survives VM death: render + push on the app scope (the rM2's Home-return twin). */
    fun onEditorClosed() {
        app.appScope.launch {
            author.renderPageNow(pageGuid)
            app.webdavSync.syncNow()
        }
    }

    companion object {
        fun factory(app: CipherCodexApp, pageGuid: String) = viewModelFactory {
            initializer { InkEditorViewModel(app, pageGuid) }
        }
    }
}
```

- [ ] **Step 5: Verify** — hit-test tests green, full gate green.

- [ ] **Step 6: Commit** — `git commit -m "feat(ink): Jetpack Ink dependency, editor viewmodel, eraser hit-test"`

---

### Task 7: Editor screen + NOTES entry points

**Files:**
- Create: `android/app/src/main/java/tech/mrzeapple/ciphercodex/ui/notes/InkEditorScreen.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/ui/notes/NotesScreen.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/ui/notes/NotesViewModel.kt` (creation helpers)

**Interfaces:**
- Consumes: `InkEditorViewModel` (Task 6), `InkAuthor` via app, `InkGeometry.strokeSegments`,
  `InkRender.PAGE_W/PAGE_H`, Cipher components (`CipherCaption`, `CipherPanel`,
  `CipherTextField`), Jetpack Ink `InProgressStrokesView`.
- Produces: `InkEditorScreen(pageGuid: String, title: String, onPrevPage: (() -> Unit)?, onNextPage: (() -> Unit)?, onAddPage: () -> Unit, onClose: () -> Unit)`.

- [ ] **Step 1: Editor screen** — structure (adapt to real Jetpack Ink API names — the
  library is the one unknown in this codebase; **verify signatures against the AAR/docs
  the way HandwritingRecognizer's were, and record what you found in your report**):

```kotlin
@Composable
fun InkEditorScreen(
    pageGuid: String, title: String,
    onPrevPage: (() -> Unit)?, onNextPage: (() -> Unit)?,
    onAddPage: () -> Unit, onClose: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as CipherCodexApp
    val vm: InkEditorViewModel = viewModel(key = pageGuid,
        factory = InkEditorViewModel.factory(app, pageGuid))
    val strokes by vm.strokes.collectAsState()
    val tool by vm.tool.collectAsState()
    BackHandler { vm.onEditorClosed(); onClose() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        // Header: title/DONE left; PEN · ERASE · UNDO · REDO · +PAGE · ‹ › right —
        // CipherCaption row, active tool in c.cyan, others c.muted (TEXT-toggle pattern).
        // ... captions calling vm.tool.value = ..., vm.undo(), vm.redo(), onAddPage,
        //     onPrevPage/onNextPage (hidden when null), DONE -> { vm.onEditorClosed(); onClose() }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(InkRender.PAGE_W.toFloat() / InkRender.PAGE_H).background(Color.White)) {
                // 1) Committed strokes: Canvas drawing InkGeometry.strokeSegments(...)
                //    scaled by size.width/size.height (page-normalized -> box px),
                //    black round-cap lines — the exact PNG-renderer look.
                Canvas(Modifier.fillMaxSize()) {
                    for (s in strokes) {
                        val pts = InkPoints.decode(s.pointsB64)
                        for (seg in InkGeometry.strokeSegments(pts, s.baseWidth)) {
                            drawLine(Color.Black,
                                Offset(seg.x0 * size.width, seg.y0 * size.height),
                                Offset(seg.x1 * size.width, seg.y1 * size.height),
                                strokeWidth = seg.width * (size.width / InkRender.PAGE_W),
                                cap = StrokeCap.Round)
                        }
                    }
                }
                if (tool == EditorTool.PEN) {
                    // 2) Wet ink: AndroidView { InProgressStrokesView } with a touch
                    //    listener that ONLY accepts TOOL_TYPE_STYLUS:
                    //    DOWN -> startStroke(event, pointerId, brush)
                    //    MOVE -> addToStroke(...); UP -> finishStroke(...)
                    //    CANCEL -> cancelStroke(...)
                    //    Brush: StockBrushes pressure pen family, black,
                    //    size = 9f * viewWidth / InkRender.PAGE_W.
                    //    addFinishedStrokesListener: for each finished stroke, read its
                    //    StrokeInputBatch (x px, y px, elapsedTimeMillis, pressure 0..1)
                    //    -> InkPoint(x/viewW, y/viewH, (pressure * 4095).toInt().coerceIn(0, 4095), t = elapsedMs)
                    //    -> vm.commitStroke(points) -> removeFinishedStrokes(ids).
                } else {
                    // 3) Eraser: pointerInput(pageGuid) { awaitEachGesture { ... } } —
                    //    stylus-only changes; on down + each move:
                    //    vm.eraseAt(pos.x / boxWidthPx, pos.y / boxHeightPx)
                }
            }
        }
    }
}
```

  Rules that are NOT optional: stylus-only in both tools; commitStroke conversion clamps
  pressure into 0..4095; wet-ink layer must be removed from the view once committed
  (no double-draw with the Canvas layer); a stroke finished with zero/one input still
  goes through `commitStroke` (single dot renders via the existing single-point branch).

- [ ] **Step 2: Creation helpers** — `NotesViewModel` gains:

```kotlin
private val author = (application as CipherCodexApp).inkAuthor

/** Returns the new page guid so the UI can jump straight into the editor. */
suspend fun newNotebook(title: String): String {
    val nb = author.createNotebook(title.ifBlank { "Notebook" })
    return author.createPage(nb)
}
suspend fun newPage(notebookGuid: String): String = author.createPage(notebookGuid)
```

- [ ] **Step 3: NotesScreen wiring** —
  - Replace `var open by remember { mutableStateOf<NotebookCard?>(null) }` with a small
    state: `var open: NotebookCard?` plus `var editingPageGuid: String?`. When
    `editingPageGuid != null`, show `InkEditorScreen` (title from the open card;
    onPrev/onNext move through `card.pages` sorted by seq, null at the ends; onAddPage
    launches `vm.newPage(card.notebook.guid)` and switches `editingPageGuid` to the
    result; onClose returns to the viewer with the pager on the edited page).
  - `PageViewer` header row gains an `EDIT` caption (same pattern as `TEXT`) →
    `onEdit(card.pages[pager.currentPage].guid)` hoisted up to NotesScreen.
  - NOTES grid header: a `+ NEW` caption beside the search field opens a small
    `CipherPanel` dialog with a `CipherTextField` for the title and CREATE/CANCEL
    captions → `scope.launch { editingPageGuid = vm.newNotebook(title) }`.
  - The empty state ("NOTES SYNC FROM YOUR REMARKABLE 2") also gets the `+ NEW` action —
    a fresh install can now author before ever syncing. Update the empty-state copy to
    `"SYNC FROM YOUR REMARKABLE 2 — OR START WRITING"`.

- [ ] **Step 4: Verify** — full gate green; then a manual pass on the `cipher` AVD:
  create a notebook, draw with `adb shell input stylus swipe 400 800 800 900` (repeat a
  few, varied), strokes appear and persist across editor close/reopen; ERASE removes a
  stroke under the stylus path; UNDO/REDO both directions; DONE re-renders the
  thumbnail in the grid. (Emulator `cipher` may still be running from the previous
  track — `adb devices` first.)

- [ ] **Step 5: Commit** — `git commit -m "feat(ink): stylus editor — Jetpack Ink canvas, eraser, undo, notebook/page creation"`

---

### Task 8: AVD gate — migration + E2E + version bump

**Files:**
- Modify: `android/app/build.gradle.kts` (versionName `0.8.0`, versionCode `30`)

- [ ] **Step 1: Full build + tests** — the standard gate, green.
- [ ] **Step 2: Migration gate** — on the `cipher` AVD with v0.7.0-era data (v9 DB):
  install this build over it → app opens, NOTES intact, `strokes` table exists and is
  empty (adb root + pulled DB, same technique as the v8→v9 gate).
- [ ] **Step 3: E2E vs scratch** (`https://kosync.cph.gg/ccx/scratch-android/`, NEVER
  live `/ccx/`; credential via file substitution, deleted after):
  1. Point the app at scratch. Create a notebook, draw 2–3 stylus strokes, DONE.
  2. Auto-sync fires on editor close — verify via the app's sync status, then curl the
     published snapshot: it contains `notebooks`, `pages`, `strokes` arrays with
     `points_b64`, AND the book arrays (old contract intact).
  3. Copy a real rM2 snapshot into scratch `state/` (server-side over ssh). Sync. The
     rM2 notebook appears alongside the authored one; open one of ITS pages in the
     editor, add a stroke, DONE, sync. Curl again: that page now carries BOTH the rM2's
     strokes and the new one (union, no replacement), all rM2 stroke guids still live.
  4. Erase the added stroke, DONE, sync: its row goes `deleted: 1` on the wire, the
     rM2 originals untouched.
  5. Second sync with no edits: no re-render churn (page stamps stable), snapshot
     stable.
  6. If recognition is enabled during the test, drawn scribbles may recognize as
     garbage text — acceptable; verify only that the recognition pass didn't error.
  7. Clean up scratch server-side; verify gone.
- [ ] **Step 4: Version bump + commit** — versionName `0.8.0`, versionCode `30`;
  `git commit -m "release: v0.8.0 — Android ink authoring (Boox handwriting)"`.
  Do NOT push, do NOT merge.

---

### Task 9: Hardware E2E + ship (owner present)

**Files:**
- Modify: `remarkable2-os/STATUS.md` (cross-device DONE entry), memory notes.

- [ ] **Step 1: Boox install** — v0.8.0 debug (or CI release) APK onto the owner's Boox
  Go 10.3 Gen 2, configured against live `https://kosync.cph.gg/ccx/`.
- [ ] **Step 2: Full loop** — owner writes on the Boox (new notebook + a line) →
  sync → rM2 pulls on Home-return → notebook + ink render on the rM2 (this is the
  never-tested direction: rM2 rendering foreign-authored ink); owner writes on the rM2 →
  Boox editor shows those strokes after sync; both devices add ink to the SAME page →
  union survives on both.
- [ ] **Step 3: Eraser + recognition** — erase a stroke on the Boox → disappears on the
  rM2 after its next pull; with recognition on, the Boox-written line lands in
  `page_texts`, searchable on both devices (rM2 TEXT overlay included).
- [ ] **Step 4: Editor feel on e-ink** — owner judgment: wet-ink latency acceptable?
  If the Boox's default refresh mode fights the front-buffer rendering, note it in
  STATUS.md KNOWN GAPS (per-app refresh mode is a Boox system setting, not our code).
- [ ] **Step 5: Ship** — STATUS.md DONE entry + gaps; ledger + memory updates;
  superpowers:finishing-a-development-branch (final whole-branch review on the most
  capable model BEFORE merge; review package `scripts/review-package <branch-base> HEAD`).

---

## Self-review notes (already applied)

- **Spec coverage:** storage/Approach A (T1), editor + tools + stylus-only +
  creation (T6/T7), symmetric sync merge (T4), export/emission (T5), encode (T2),
  local re-render + exit-sync trigger (T3/T6), migration + E2E gates (T8), hardware +
  ship (T9). rM2 untouched per spec.
- **Type consistency:** `StrokeEntity` field order fixed in T1 and used positionally in
  T4's test; `InkMerge.contentStamp` entity overload (T4) consumed by `InkAuthor` (T3
  note) and `InkSync` (T4); `InkRender.renderPage(List<Pair<String, Float>>, File)`
  consumed by T3 and T4; `EditorTool`/VM API defined T6, consumed T7.
- **Deliberate ceilings:** eraser tolerance mixes x/y scales (comment in InkHitTest);
  wet-ink brush look only approximates the committed render (final look comes from the
  shared renderer); undo/redo is session-local; no notebook/page deletion UI (spec cut);
  Jetpack Ink API names in T7 are indicative — the implementer verifies against the
  actual artifact and reports.
