# Handwriting Recognition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recognize rM2 notebook handwriting into per-page text on the Android app at sync time (ML Kit Digital Ink), sync the text back over the existing WebDAV snapshots, and surface it on both the phone (viewer panel + search) and the reMarkable (TEXT overlay + notebook search).

**Architecture:** Recognition hooks the existing Android sync "ink pass" (`InkSync`), reusing its decoded strokes and `contentStamp` change detection; a pure-Kotlin line segmenter feeds ML Kit one line at a time. Text travels as a NEW additive `pageTexts` array in the frozen phase3b snapshot (Android-authored; old clients ignore it); the rM2 gains schema v4 (`page_text` table) + two SyncStore blocks to merge/re-export it.

**Tech Stack:** Kotlin/Compose + Room v9 + `com.google.mlkit:digital-ink-recognition:19.0.0` (Android); C++/Qt 6.8 + SQLite (rM2). Spec: `docs/superpowers/specs/2026-07-12-handwriting-recognition-design.md`.

## Global Constraints

- **Sequencing:** execute ONLY after the NOTES track's Task 5 ships (v7→v8 AVD gate + live-ink E2E + v0.6.0 merge of `notes-rm2-viewer`). Branch `handwriting-recognition` from that merged state. Room v9 chains after v8.
- **One checkout, two tracks:** never run a second building/committing session while implementers are dispatched (stray-commit hazard happened twice: 1d5db49, 3719963). Check `git branch --show-current` before every commit.
- **Frozen wire contract:** existing snapshot keys never change. The ONLY addition is top-level `pageTexts: [{pageGuid, text, sourceStamp, deleted, updatedAt}]`. Android still NEVER emits `notebooks`/`pages`/`strokes`.
- **Android build check:** from `android/`, `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` must print `BUILD SUCCESSFUL` (JAVA_HOME = JDK 21). Unit tests are pure JVM (JUnit 4, no Robolectric) — keep new sync logic Android-API-free.
- **rM2 build check:** from `remarkable2-os/`, `bash scripts/build.sh` then `bash scripts/test.sh` — all suites must stay green (test count grows in this plan).
- **Device safety:** before first deploy of the v4-migrating binary: `ssh root@10.11.99.1 "cp /home/root/ciphercodex/data.db /home/root/ciphercodex/data-v3-backup.db"` and `scp` it to `device-backups/data-v3-pre-hwr-2026-MM-DD.db`.
- **rM2 UI rules:** pure black/white, Theme tokens, no animation, pressed = inversion, touch ≥ 90px, glyphs limited to ← → « » ▼ · ✓ + (everything else drawn from Rectangles). Share Tech Mono never bold.
- **Recognition is contained:** failures land in the sync summary string, never abort book/ink sync (same try/catch pattern as `InkSync`).
- Language fixed `en-US`; recognition toggle default OFF (`~20MB` model downloads on enable).

---

### Task 1: Point decoder carries timestamps

**Files:**
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/InkPoints.kt`
- Test: `android/app/src/test/java/tech/mrzeapple/ciphercodex/sync/webdav/InkPointsTest.kt`

**Interfaces:**
- Consumes: existing `InkPoints.decode(b64: String): List<InkPoint>` over 18-byte LE records `{f32 x, f32 y, u16 pressure, i16 tiltX, i16 tiltY, u32 tMs}` (`remarkable2-os/docs/stroke-format.md`).
- Produces: `data class InkPoint(val x: Float, val y: Float, val pressure: Int, val t: Long = 0)` — `t` = tMs, milliseconds relative to stroke start, decoded from the u32 currently skipped. Default value keeps every existing call site and test source-compatible.

- [ ] **Step 1: Write the failing test** — add to `InkPointsTest.kt` (mirror the file's existing record-packing helper style):

```kotlin
@Test
fun `decode carries the relative timestamp`() {
    val buf = java.nio.ByteBuffer.allocate(36).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buf.putFloat(0.25f); buf.putFloat(0.5f); buf.putShort(2000); buf.putShort(0); buf.putShort(0)
    buf.putInt(0)          // first point at t=0
    buf.putFloat(0.26f); buf.putFloat(0.5f); buf.putShort(2100); buf.putShort(0); buf.putShort(0)
    buf.putInt(17)         // 17 ms later
    val b64 = java.util.Base64.getEncoder().encodeToString(buf.array())
    val pts = InkPoints.decode(b64)
    assertEquals(2, pts.size)
    assertEquals(0L, pts[0].t)
    assertEquals(17L, pts[1].t)
}
```

- [ ] **Step 2: Run test to verify it fails** — from `android/`: `.\gradlew.bat :app:testDebugUnitTest --tests "*InkPointsTest*"` → FAIL (`t` unresolved).

- [ ] **Step 3: Implement** — in `InkPoints.kt`: add `val t: Long = 0` to `InkPoint`; in `decode`, replace the skip (`buf.short; buf.short; buf.int // tiltX, tiltY, tMs — unused in v1`) with `buf.short; buf.short; val tMs = buf.int.toLong() and 0xFFFFFFFFL` and pass `t = tMs`. Keep tilt skipped.

- [ ] **Step 4: Run tests to verify pass** — same command, plus the full `:app:testDebugUnitTest` (existing InkPoints/InkMerge tests must stay green).

- [ ] **Step 5: Commit** — `git add` the two files; `git commit -m "feat(hwr): decode stroke point timestamps (tMs) for recognition"`

---

### Task 2: Line segmentation (pure Kotlin)

**Files:**
- Create: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/recognition/InkLines.kt`
- Test: `android/app/src/test/java/tech/mrzeapple/ciphercodex/sync/recognition/InkLinesTest.kt`

**Interfaces:**
- Consumes: `InkPoint` from Task 1.
- Produces:
  - `data class RecStroke(val points: List<InkPoint>, val createdAt: Long)`
  - `object InkLines { fun segment(strokes: List<RecStroke>): List<List<RecStroke>> }` — lines ordered top-to-bottom; strokes within a line keep writing (createdAt) order; empty-point strokes dropped.

- [ ] **Step 1: Write the failing tests**

```kotlin
package tech.mrzeapple.ciphercodex.sync.recognition

import org.junit.Assert.*
import org.junit.Test
import tech.mrzeapple.ciphercodex.sync.webdav.InkPoint

class InkLinesTest {
    private fun stroke(yTop: Float, yBottom: Float, at: Long) = RecStroke(
        listOf(InkPoint(0.1f, yTop, 2000, 0), InkPoint(0.3f, yBottom, 2000, 30)), at)

    @Test
    fun `two vertically separated rows become two lines in top-down order`() {
        // written bottom row first — order in must not dictate line order out
        val lines = InkLines.segment(listOf(stroke(0.50f, 0.53f, 1000), stroke(0.10f, 0.13f, 2000)))
        assertEquals(2, lines.size)
        assertEquals(2000L, lines[0][0].createdAt)  // top line
        assertEquals(1000L, lines[1][0].createdAt)
    }

    @Test
    fun `a delayed i-dot overlapping a line's band joins that line`() {
        val body = stroke(0.30f, 0.36f, 1000)
        val dot = stroke(0.29f, 0.295f, 5000)  // above but within tolerance of the band
        val lines = InkLines.segment(listOf(body, stroke(0.60f, 0.66f, 2000), dot))
        assertEquals(2, lines.size)
        assertEquals(listOf(1000L, 5000L), lines[0].map { it.createdAt })
    }

    @Test
    fun `empty strokes are dropped and empty input yields no lines`() {
        assertTrue(InkLines.segment(emptyList()).isEmpty())
        assertTrue(InkLines.segment(listOf(RecStroke(emptyList(), 1L))).isEmpty())
    }
}
```

- [ ] **Step 2: Run to verify FAIL** — `.\gradlew.bat :app:testDebugUnitTest --tests "*InkLinesTest*"` → FAIL (unresolved `InkLines`).

- [ ] **Step 3: Implement `InkLines.kt`**

```kotlin
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
```

- [ ] **Step 4: Run to verify PASS** — same command.

- [ ] **Step 5: Commit** — `git commit -m "feat(hwr): y-band line segmentation for stroke recognition"`

---

### Task 3: Room v9 — `page_texts` table

**Files:**
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/data/db/Entities.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/data/db/NotesDao.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/data/db/AppDatabase.kt`

**Interfaces:**
- Produces:
  - `@Entity(tableName = "page_texts") data class PageTextEntity(@PrimaryKey val pageGuid: String, val text: String, val sourceStamp: Long, val updatedAt: Long)`
  - NotesDao: `suspend fun pageText(guid: String): PageTextEntity?`, `suspend fun allPageTexts(): List<PageTextEntity>`, `fun observeAllPageTexts(): Flow<List<PageTextEntity>>`, `@Upsert suspend fun upsertPageText(t: PageTextEntity)`, `suspend fun deletePageText(pageGuid: String)`
  - `MIGRATION_8_9` and `@Database(version = 9)`.

- [ ] **Step 1: Entity** — append to `Entities.kt`:

```kotlin
/** Recognized handwriting per rM2 page. Derived from strokes at sync time (sourceStamp =
 *  the contentStamp it was computed from); hard-deleted with its page like all ink data. */
@Entity(tableName = "page_texts")
data class PageTextEntity(
    @PrimaryKey val pageGuid: String,
    val text: String,
    val sourceStamp: Long,
    val updatedAt: Long,
)
```

- [ ] **Step 2: DAO** — add to `NotesDao.kt` (imports: `kotlinx.coroutines.flow.Flow`):

```kotlin
@Query("SELECT * FROM page_texts WHERE pageGuid = :guid")
suspend fun pageText(guid: String): PageTextEntity?

@Query("SELECT * FROM page_texts")
suspend fun allPageTexts(): List<PageTextEntity>

@Query("SELECT * FROM page_texts")
fun observeAllPageTexts(): Flow<List<PageTextEntity>>

@Upsert
suspend fun upsertPageText(t: PageTextEntity)

@Query("DELETE FROM page_texts WHERE pageGuid = :pageGuid")
suspend fun deletePageText(pageGuid: String)
```

- [ ] **Step 3: Migration** — in `AppDatabase.kt`: bump `version = 9`, add `PageTextEntity::class` to entities, add to companion + `.addMigrations(...)`:

```kotlin
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // letter-for-letter what Room generates for PageTextEntity (v4->v5 lesson)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `page_texts` (`pageGuid` TEXT NOT NULL, " +
            "`text` TEXT NOT NULL, `sourceStamp` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`pageGuid`))")
    }
}
```

- [ ] **Step 4: Verify build** — `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL (KSP validates entity/DAO agreement; the migration itself gates in Task 8's AVD run).

- [ ] **Step 5: Commit** — `git commit -m "feat(hwr): Room v9 — page_texts table for recognized handwriting"`

---

### Task 4: ML Kit dependency + recognizer wrapper + Settings opt-in

**Files:**
- Modify: `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts`
- Create: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/recognition/HandwritingRecognizer.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/data/prefs/` (the existing Prefs class: add `handwritingRecognition: Boolean`, default false, follow the exact pattern of the nearest boolean pref)
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/ui/settings/` (SettingsScreen + SettingsViewModel: one toggle row)

**Interfaces:**
- Consumes: `RecStroke` (Task 2).
- Produces:

```kotlin
class HandwritingRecognizer {
    /** true when the en-US model is on disk (blocking, call on Dispatchers.IO). */
    fun modelDownloaded(): Boolean
    /** one-time ~20MB download; throws on failure (blocking, Dispatchers.IO). */
    fun downloadModel()
    /** recognize ONE segmented line; returns best candidate text, "" when none. */
    fun recognizeLine(line: List<RecStroke>, preContext: String): String
    fun close()
}
```

- [ ] **Step 1: Dependency** — `libs.versions.toml`: under `[versions]` add `mlkitDigitalInk = "19.0.0"`; under `[libraries]` add `mlkit-digital-ink = { group = "com.google.mlkit", name = "digital-ink-recognition", version.ref = "mlkitDigitalInk" }`. In `app/build.gradle.kts` dependencies: `implementation(libs.mlkit.digital.ink)`. Run `.\gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL (repo `google()` already included via settings.gradle.kts).

- [ ] **Step 2: Wrapper** — create `HandwritingRecognizer.kt`. NOTE: v19.0.0 packages are `com.google.mlkit.vision.digitalink.recognition.*` (Ink, DigitalInkRecognition, DigitalInkRecognizerOptions, DigitalInkRecognitionModel, DigitalInkRecognitionModelIdentifier, RecognitionContext, WritingArea) and `com.google.mlkit.vision.digitalink.common.*` (RecognitionResult/Candidate) — verify against the AAR if an import 404s; older docs show pre-restructure paths.

```kotlin
package tech.mrzeapple.ciphercodex.sync.recognition

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager

private const val PAGE_W = 1404f
private const val PAGE_H = 1872f

/** Thin blocking adapter over ML Kit Digital Ink (call only from Dispatchers.IO).
 *  Everything testable (segmentation, staleness, orchestration) lives OUTSIDE this class. */
class HandwritingRecognizer {
    private val model = DigitalInkRecognitionModel.builder(
        DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")!!).build()
    private val manager = RemoteModelManager.getInstance()
    private val client by lazy {
        DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
    }

    fun modelDownloaded(): Boolean = Tasks.await(manager.isModelDownloaded(model))

    fun downloadModel() {
        Tasks.await(manager.download(model, DownloadConditions.Builder().build()))
    }

    fun recognizeLine(line: List<RecStroke>, preContext: String): String {
        if (line.isEmpty()) return ""
        val ink = Ink.builder().apply {
            for (s in line) addStroke(Ink.Stroke.builder().apply {
                for (p in s.points)
                    addPoint(Ink.Point.create(p.x * PAGE_W, p.y * PAGE_H, s.createdAt + p.t))
            }.build())
        }.build()
        val top = line.minOf { s -> s.points.minOf { it.y } } * PAGE_H
        val bottom = line.maxOf { s -> s.points.maxOf { it.y } } * PAGE_H
        val ctx = RecognitionContext.builder()
            .setPreContext(preContext.takeLast(20))
            .setWritingArea(WritingArea(PAGE_W, ((bottom - top) * 1.6f).coerceAtLeast(60f)))
            .build()
        val result = Tasks.await(client.recognize(ink, ctx))
        return result.candidates.firstOrNull()?.text ?: ""
    }

    fun close() = client.close()
}
```

- [ ] **Step 3: Pref + Settings row** — add `handwritingRecognition` boolean pref (default false) following the existing pref pattern. SettingsScreen: under the WebDAV section add a toggle row "HANDWRITING RECOGNITION" with caption "recognize rM2 notes to text at sync (~20MB one-time model)". On enable, the SettingsViewModel launches (IO scope, try/catch): `HandwritingRecognizer().downloadModel()`, surfacing "downloading model… / model ready / download failed: …" through the same status-text mechanism the WebDAV rows use; on failure flip the pref back off.

- [ ] **Step 4: Verify** — `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit** — `git commit -m "feat(hwr): ML Kit digital-ink dependency, recognizer wrapper, opt-in setting"`

---

### Task 5: Recognition pass inside the sync ink pass

**Files:**
- Create: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/recognition/RecognitionPass.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/InkSync.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/WebDavSyncManager.kt`
- Test: `android/app/src/test/java/tech/mrzeapple/ciphercodex/sync/recognition/RecognitionPassTest.kt`

**Interfaces:**
- Consumes: `InkLines.segment`, `HandwritingRecognizer.recognizeLine`, `InkMerge.contentStamp`, `NotesDao` (Task 3), `InkSync.apply`'s per-page loop (the render hook), `InkStroke` wire rows (`pointsB64`, `createdAt`).
- Produces:
  - `class RecognitionPass(private val recognize: (List<RecStroke>, String) -> String)` with
    `fun textFor(strokes: List<RecStroke>): String` — segments, recognizes line by line with rolling preContext, joins with `\n`, trims. Pure logic; the lambda keeps ML Kit out of unit tests.
  - `InkSync` constructor gains `private val recognition: RecognitionHook?` where `data class RecognitionHook(val pass: RecognitionPass)`; `InkResult` gains `val pagesRecognized: Int`.
  - `InkSync` hard-deletes `page_texts` rows alongside page hard-deletes.

- [ ] **Step 1: Write the failing test**

```kotlin
package tech.mrzeapple.ciphercodex.sync.recognition

import org.junit.Assert.*
import org.junit.Test
import tech.mrzeapple.ciphercodex.sync.webdav.InkPoint

class RecognitionPassTest {
    private fun stroke(y: Float, at: Long) =
        RecStroke(listOf(InkPoint(0.1f, y, 2000, 0), InkPoint(0.4f, y + 0.03f, 2000, 40)), at)

    @Test
    fun `lines are recognized top-down with rolling preContext and joined by newline`() {
        val calls = mutableListOf<Pair<Int, String>>()  // (line stroke count, preContext)
        val pass = RecognitionPass { line, pre -> calls.add(line.size to pre); "line${calls.size}" }
        val text = pass.textFor(listOf(stroke(0.6f, 1L), stroke(0.1f, 2L)))
        assertEquals("line1\nline2", text)
        assertEquals("", calls[0].second)          // first line: no context
        assertEquals("line1", calls[1].second)     // second line sees prior text
    }

    @Test
    fun `no ink yields empty text without invoking the recognizer`() {
        var called = false
        val pass = RecognitionPass { _, _ -> called = true; "x" }
        assertEquals("", pass.textFor(emptyList()))
        assertFalse(called)
    }
}
```

- [ ] **Step 2: Verify FAIL** — `--tests "*RecognitionPassTest*"` → unresolved `RecognitionPass`.

- [ ] **Step 3: Implement `RecognitionPass.kt`**

```kotlin
package tech.mrzeapple.ciphercodex.sync.recognition

/** Whole-page orchestration: segment -> recognize per line -> join. The recognize lambda
 *  is the ML Kit boundary so this stays a pure-JVM unit. */
class RecognitionPass(private val recognize: (List<RecStroke>, String) -> String) {
    fun textFor(strokes: List<RecStroke>): String {
        val sb = StringBuilder()
        for (line in InkLines.segment(strokes)) {
            val t = recognize(line, sb.toString().takeLast(20))
            if (t.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(t)
            }
        }
        return sb.toString().trim()
    }
}
```

- [ ] **Step 4: Verify PASS**, commit the pure part — `git commit -m "feat(hwr): page recognition orchestration (segment + rolling pre-context)"`

- [ ] **Step 5: Wire into `InkSync`** — following its existing structure exactly:
  - Constructor: `class InkSync(db: AppDatabase, notebooksDir: File, private val recognition: RecognitionPass? = null)`.
  - Deletion phase (phase 1 transaction): wherever pages are hard-deleted (tombstoned pages, `deletePagesOf`, tombstoned notebooks), also `dao.deletePageText(pageGuid)` for each removed page guid (guids are already collected there for PNG deletion).
  - Per-page loop (phase 2), after the render-or-skip decision, add an independent staleness check so text catches up even when the PNG was already current:

```kotlin
var recognized = 0
// inside the per-page loop, alongside the render check (stamp already computed):
if (recognition != null) {
    val existing = dao.pageText(page.guid)
    if (existing?.sourceStamp != stamp) {
        val text = if (strokes.isEmpty()) "" else recognition.textFor(strokes.map {
            RecStroke(InkPoints.decode(it.pointsB64), it.createdAt)
        })
        if (text.isEmpty()) dao.deletePageText(page.guid)
        else dao.upsertPageText(PageTextEntity(page.guid, text, stamp,
                                               System.currentTimeMillis()))
        recognized++
    }
}
```

  - `InkResult` gains `pagesRecognized: Int` (default 0), returned from `apply`.
  - `WebDavSyncManager`: where `InkSync` is constructed, build the hook when enabled and ready — wrap in the same containment as the ink pass:

```kotlin
val recognition = if (prefs.handwritingRecognition()) {
    try {
        val hw = HandwritingRecognizer()
        if (hw.modelDownloaded()) RecognitionPass(hw::recognizeLine) else null
    } catch (t: Throwable) { null }  // recognition must never break sync
} else null
```

  (Match the actual prefs accessor name from Task 4. Pass `recognition` into `InkSync`; append `pagesRecognized` to the summary the same way `pagesRendered` is reported.)

- [ ] **Step 6: Verify** — full `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL, all green.

- [ ] **Step 7: Commit** — `git commit -m "feat(hwr): recognize changed pages during the sync ink pass"`

---

### Task 6: `pageTexts` on the wire (Android emit + merge + apply)

**Files:**
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/Snapshot.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/SnapshotMerge.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/sync/webdav/WebDavSyncManager.kt`
- Test: `android/app/src/test/java/tech/mrzeapple/ciphercodex/sync/webdav/SnapshotJsonTest.kt`, `.../SnapshotMergeTest.kt`

**Interfaces:**
- Produces (wire, additive — the ONLY change ever made to the frozen contract):

```kotlin
@Serializable
data class SnapPageText(
    val pageGuid: String,
    val text: String,
    val sourceStamp: Long = -1,
    val deleted: Int = 0,
    val updatedAt: Long,
)
// Snapshot gains: val pageTexts: List<SnapPageText> = emptyList()
```

  Identity = `pageGuid`; LWW by `updatedAt`, tie → tombstone wins (existing `wins()`).

- [ ] **Step 1: Failing tests** — `SnapshotJsonTest`: a snapshot WITHOUT `pageTexts` still decodes (old peers) and one WITH it round-trips; `SnapshotMergeTest`: two snapshots with the same `pageGuid` — newer `updatedAt` wins; tie with `deleted=1` wins.

```kotlin
@Test
fun `pageTexts round-trips and old snapshots decode without it`() {
    val snap = Snapshot(deviceId = "a", generatedAt = 1L,
        pageTexts = listOf(SnapPageText("pg1", "hello", 42L, 0, 100L)))
    val decoded = SnapshotJson.decode(SnapshotJson.encode(snap))
    assertEquals("hello", decoded.pageTexts.single().text)
    val old = SnapshotJson.decode("""{"deviceId":"b","generatedAt":2}""")
    assertTrue(old.pageTexts.isEmpty())
}
```

```kotlin
@Test
fun `pageTexts merge is LWW by pageGuid with tombstone tie-break`() {
    val a = Snapshot(deviceId = "a", generatedAt = 1L,
        pageTexts = listOf(SnapPageText("pg", "old", 1L, 0, 100L)))
    val b = Snapshot(deviceId = "b", generatedAt = 2L,
        pageTexts = listOf(SnapPageText("pg", "new", 2L, 0, 200L)))
    assertEquals("new", SnapshotMerge.merge(listOf(a, b)).pageTexts.single().text)
    val tomb = Snapshot(deviceId = "c", generatedAt = 3L,
        pageTexts = listOf(SnapPageText("pg", "", 2L, 1, 200L)))
    assertEquals(1, SnapshotMerge.merge(listOf(b, tomb)).pageTexts.single().deleted)
}
```

(Adapt constructor calls to `Snapshot`'s real field list — every other field has defaults per the frozen contract's `encodeDefaults=true`; if not, build via the existing test helpers in those files.)

- [ ] **Step 2: Verify FAIL**, then implement:
  - `Snapshot.kt`: add `SnapPageText` + the `pageTexts` field with `= emptyList()` default.
  - `SnapshotMerge.kt`: `pageTexts = lww(snapshots.map { it.pageTexts }, { it.pageGuid }, { it.updatedAt }, { it.deleted })` — follow the exact `lww()` call shape used for `bookmarks`.
  - `WebDavSyncManager.exportSnapshot()`: populate from `db.notesDao().allPageTexts()`:
    `pageTexts = texts.map { SnapPageText(it.pageGuid, it.text, it.sourceStamp, 0, it.updatedAt) }`.
  - `applyMerged()`: new branch — for each merged `SnapPageText`: `deleted == 1` → `notesDao.deletePageText(pageGuid)`; else upsert `PageTextEntity(pageGuid, text, sourceStamp, updatedAt)` **only when** it beats the local row (`wins(remote.updatedAt, remote.deleted, local?.updatedAt ?: -1, 0)`), and only when `notesDao.pageByGuid(pageGuid) != null` (missing-parent skip, converges next sync).

- [ ] **Step 3: Verify PASS** — full unit test run + assembleDebug.

- [ ] **Step 4: Commit** — `git commit -m "feat(hwr): pageTexts snapshot array — emit, LWW merge, apply"`

---

### Task 7: NOTES tab UI — text panel + search

**Files:**
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/ui/notes/NotesViewModel.kt`
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/ui/notes/NotesScreen.kt`

**Interfaces:**
- Consumes: `notesDao().observeAllPageTexts()` (Task 3), existing `NotebookCard`/`PageViewer`/`rememberPageBitmap`.
- Produces: `NotesViewModel.pageTexts: StateFlow<Map<String, String>>` (pageGuid → text) and `NotesViewModel.query: MutableStateFlow<String>`; `notebooks` flow filtered by query against notebook title OR any page's text (case-insensitive contains).

- [ ] **Step 1: ViewModel** — add `observeAllPageTexts()` into the existing `combine`; expose `pageTexts` map; add `query` and filter the `NotebookCard` list: a card matches when `notebook.title.contains(q, true)` or any of its pages' texts contains `q` (empty query = all).

- [ ] **Step 2: Screen** — NotesScreen: a search `TextField` above the grid (existing Cipher theme components; visible only when there are notebooks). PageViewer: a "TEXT" toggle in the header caption row; when on, a panel (scrollable `Text`, `CipherPanel` styling) below/over the pager showing `pageTexts[card.pages[pagerState.currentPage].guid] ?: "No text recognized yet — sync with recognition enabled."`.

- [ ] **Step 3: Verify** — `.\gradlew.bat :app:assembleDebug` BUILD SUCCESSFUL; manual: launch on the `cipher` AVD, NOTES tab renders, search field filters, TEXT panel shows placeholder copy.

- [ ] **Step 4: Commit** — `git commit -m "feat(hwr): NOTES search + per-page recognized-text panel"`

---

### Task 8: Android gate — migration + E2E + version bump

**Files:**
- Modify: `android/app/build.gradle.kts` (versionCode +1, versionName next minor above whatever NOTES shipped)

- [ ] **Step 1: Full build + tests** — `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL.
- [ ] **Step 2: Migration gate on the `cipher` AVD** — install the last released APK (v0.6.0 from the NOTES merge), create data, then install this build: app opens, NOTES intact (v8→v9 migration ran; `page_texts` empty).
- [ ] **Step 3: E2E against the scratch server** — point the AVD app at `https://kosync.cph.gg/ccx/scratch-android/` (the NOTES track's E2E convention — never the live `/ccx/` data): enable HANDWRITING RECOGNITION (model downloads), copy a real rM2 snapshot into `state/`, sync. Verify: pages render AND `pagesRecognized > 0` in the summary, NOTES text panel shows plausible text, search finds it, and the published Android snapshot at `state/<android-id>.json` contains a `pageTexts` array (fetch with curl and grep). Sync again → `pagesRecognized == 0` (stamp gating).
- [ ] **Step 4: Commit** — `git commit -m "release: vX.Y.Z — handwriting recognition (Android side)"` (exact version chosen at execution; do NOT merge to main yet — the rM2 side follows).

---

### Task 9: rM2 schema v4 — `page_text` table

**Files:**
- Modify: `remarkable2-os/src/storage/storage.cpp` (+ `storage.h` for new accessors)
- Test: `remarkable2-os/tests/test_storage_v2.cpp`

**Interfaces:**
- Produces:
  - Table: `page_text(id INTEGER PK, page_id INTEGER NOT NULL REFERENCES pages(id) ON DELETE CASCADE, text TEXT NOT NULL DEFAULT '', source_stamp INTEGER NOT NULL DEFAULT -1, guid TEXT, deleted INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0)` + `UNIQUE INDEX page_text_guid(guid)` + `UNIQUE INDEX page_text_page(page_id)`.
  - `QString Storage::pageText(qint64 pageId)` — live text or empty.
  - `QVector<NotebookInfo> Storage::notebooks(const QString &query)` — notebooks whose title OR any live page text matches `LIKE '%q%'` (existing no-arg `notebooks()` unchanged).
  - `deleteNotebook` cascade additionally tombstones `page_text`.
- **Warnings from the codebase:** do NOT add `page_text` to `kSyncedTables` (that list feeds `backfillV3`, which runs during the v<3 step before v4 exists — a v2 device migration would abort). New rows are born with guid/updated_at, no backfill needed.

- [ ] **Step 1: Failing tests** — in `test_storage_v2.cpp`: change the version assert from 3 to 4; add `assert(tableExists(db, "page_text"));` and a cascade check (insert notebook/page/page_text via SQL, `DELETE FROM pages ...`, assert `COUNT(*)` on page_text is 0 via the FK cascade). Run `bash scripts/test.sh` → FAIL (version still 3).

- [ ] **Step 2: Implement** — in `storage.cpp`:

```c
// Sync'd recognized handwriting (Phase HWR). Authored by the Android app from stroke
// data; one row per page; travels as the snapshot's "pageText" array.
const char *const kSchemaV4 =
    "CREATE TABLE page_text(id INTEGER PRIMARY KEY,"
    "  page_id INTEGER NOT NULL REFERENCES pages(id) ON DELETE CASCADE,"
    "  text TEXT NOT NULL DEFAULT '',"
    "  source_stamp INTEGER NOT NULL DEFAULT -1,"
    "  guid TEXT, deleted INTEGER NOT NULL DEFAULT 0,"
    "  updated_at INTEGER NOT NULL DEFAULT 0);"
    "CREATE UNIQUE INDEX page_text_guid ON page_text(guid);"
    "CREATE UNIQUE INDEX page_text_page ON page_text(page_id);";
```

  In `migrate()` after the v3 step, mirroring its exact Tx shape:

```c
if (v < 4) {
    Tx tx(db);
    if (!(tx.active && exec(db, kSchemaV4)
          && exec(db, "UPDATE schema_version SET version=4") && tx.commit()))
        return false;
    v = 4;
}
```

  In `deleteNotebook`, alongside the strokes tombstone statement:

```c
Stmt pt(m_db, "UPDATE page_text SET deleted = 1, updated_at = ?, text = ''"
              "  WHERE page_id IN (SELECT id FROM pages WHERE notebook_id = ?)");
```

  New accessors (follow `strokes()`/`notebooks()` patterns):

```cpp
QString Storage::pageText(qint64 pageId)
{
    Stmt q(m_db, "SELECT text FROM page_text WHERE page_id = ? AND deleted = 0");
    sqlite3_bind_int64(q.s, 1, pageId);
    return q.row() ? colText(q.s, 0) : QString();
}

QVector<Storage::NotebookInfo> Storage::notebooks(const QString &query)
{
    QVector<NotebookInfo> out;
    Stmt q(m_db,
        "SELECT DISTINCT n.id, n.title,"
        "  (SELECT COUNT(*) FROM pages p2 WHERE p2.notebook_id = n.id AND p2.deleted = 0)"
        "  FROM notebooks n"
        "  LEFT JOIN pages p ON p.notebook_id = n.id AND p.deleted = 0"
        "  LEFT JOIN page_text pt ON pt.page_id = p.id AND pt.deleted = 0"
        "  WHERE n.deleted = 0 AND (n.title LIKE ? OR pt.text LIKE ?)"
        "  ORDER BY n.updated_at DESC");
    const QByteArray like = ("%" + query + "%").toUtf8();
    sqlite3_bind_text(q.s, 1, like.constData(), like.size(), SQLITE_TRANSIENT);
    sqlite3_bind_text(q.s, 2, like.constData(), like.size(), SQLITE_TRANSIENT);
    while (q.row())
        out.append({sqlite3_column_int64(q.s, 0), colText(q.s, 1), sqlite3_column_int(q.s, 2)});
    return out;
}
```

  (Match `NotebookInfo`'s real fields; declare both in `storage.h`.)

- [ ] **Step 3: Verify** — `bash scripts/build.sh && bash scripts/test.sh` → all suites green with the new asserts.

- [ ] **Step 4: Commit** — `git commit -m "feat(rm2): schema v4 — page_text table (recognized handwriting, synced)"`

---

### Task 10: rM2 SyncStore — merge + re-export `pageText`

**Files:**
- Modify: `remarkable2-os/src/sync/syncstore.cpp`
- Test: `remarkable2-os/tests/test_sync.cpp`

**Interfaces:**
- Consumes: wire rows `{pageGuid, text, sourceStamp, deleted, updatedAt}` under top-level key `"pageTexts"` (must match Task 6's `SnapPageText` field names exactly), `idByGuid`, `metaByGuid`, `shouldApply`, `mergeTable`, `Tx::restart`.
- Produces: `exportSnapshot` emits `"pageTexts"`; `applyMerged` applies it in a segment AFTER pages (parent order), missing parent → skip.

- [ ] **Step 1: Failing tests** — add to `test_sync.cpp` (using `makeDev`/`push`/`runSql`/`scalarText`/`scalarInt`) and register in `main()`:

```cpp
static void testPageTextSync()
{
    Dev A = makeDev("device-a");   // stands in for the Android author
    Dev B = makeDev("device-b");
    const qint64 nb = A.st->createNotebook("N");
    const qint64 pg = A.st->createPage(nb);
    push(A, B);
    const QString pgGuid = scalarText(A.db(), "SELECT guid FROM pages");
    runSql(A.db(), QStringLiteral(
        "INSERT INTO page_text(page_id, text, source_stamp, guid, deleted, updated_at)"
        " VALUES(%1, 'hello world', 7, 'ptguid0000000000000000000000000a', 0, 1000)").arg(pg));

    push(A, B);   // text follows the page
    assert(scalarText(B.db(), "SELECT text FROM page_text") == "hello world");
    assert(scalarInt(B.db(), "SELECT source_stamp FROM page_text") == 7);

    // LWW: newer text on A replaces B's copy
    runSql(A.db(), QStringLiteral("UPDATE page_text SET text='newer', updated_at=2000"));
    push(A, B);
    assert(scalarText(B.db(), "SELECT text FROM page_text") == "newer");

    // missing parent: a text row for an unknown page is skipped, not inserted
    QJsonObject snap;
    snap.insert("deviceId", "ghost");
    QJsonArray arr;
    QJsonObject r;
    r.insert("pageGuid", "nosuchpage0000000000000000000000");
    r.insert("text", "orphan"); r.insert("sourceStamp", 1);
    r.insert("deleted", 0); r.insert("updatedAt", 9999);
    arr.append(r); snap.insert("pageTexts", arr);
    B.ss->applyMerged({snap}, B.id);
    assert(scalarInt(B.db(), "SELECT COUNT(*) FROM page_text") == 1);

    freeDev(A); freeDev(B);
    printf("  page-text OK\n");
}
```

  Run `bash scripts/test.sh` → FAIL (text doesn't travel yet).

- [ ] **Step 2: Implement in `syncstore.cpp`:**
  - `exportSnapshot`: add a block following the strokes block's JOIN pattern:

```cpp
{
    QJsonArray a;
    Stmt q(db, "SELECT pt.guid, pg.guid, pt.text, pt.source_stamp, pt.deleted, pt.updated_at"
               "  FROM page_text pt JOIN pages pg ON pg.id = pt.page_id");
    while (q.row()) {
        QJsonObject r;
        r.insert(QStringLiteral("guid"), colText(q.s, 0));
        r.insert(QStringLiteral("pageGuid"), colText(q.s, 1));
        r.insert(QStringLiteral("text"), colText(q.s, 2));
        r.insert(QStringLiteral("sourceStamp"), double(sqlite3_column_int64(q.s, 3)));
        r.insert(QStringLiteral("deleted"), sqlite3_column_int(q.s, 4));
        r.insert(QStringLiteral("updatedAt"), double(sqlite3_column_int64(q.s, 5)));
        a.append(r);
    }
    snap.insert(QStringLiteral("pageTexts"), a);
}
```

  - `applyMerged`: merge keyed by pageGuid — `const Merged pageTexts = mergeTable(snaps, QStringLiteral("pageTexts"), [](const QJsonObject &r) { return jStr(r, "pageGuid"); });` — then a new segment AFTER the pages/strokes segments (`if (!tx.restart()) return stats;`): for each winner: `pageId = idByGuid(db, "pages", jStr(r, "pageGuid")); if (pageId < 0) continue;` then look up the local row BY `page_id` (one text per page — `SELECT guid, deleted, updated_at FROM page_text WHERE page_id = ?`), run `shouldApply`, and INSERT (minting `newGuid()` when the wire row's `guid` is absent — the Android author sends none; the `progress` pattern) or UPDATE `text/source_stamp/deleted/updated_at WHERE page_id = ?`. Tally via `tally(rDel)`.

- [ ] **Step 3: Verify** — `bash scripts/build.sh && bash scripts/test.sh` → `page-text OK`, all suites green (including the pre-existing seven sync tests).

- [ ] **Step 4: Commit** — `git commit -m "feat(rm2): sync pageTexts — merge Android-recognized text, re-export"`

---

### Task 11: rM2 UI — TEXT overlay + notebook search

**Files:**
- Modify: `remarkable2-os/src/notebookcontroller.h/.cpp` (two Q_INVOKABLEs)
- Modify: `remarkable2-os/src/qml/PageScreen.qml`
- Modify: `remarkable2-os/src/qml/NotebookListScreen.qml`

**Interfaces:**
- Consumes: `Storage::pageText(pageId)`, `Storage::notebooks(query)` (Task 9), `RailBtn` component, `Theme` tokens, `Keyboard.qml` (auto-appears for any focused TextInput), `reader.onSyncedDataChanged` (NotebookListScreen already reloads on it).
- Produces:
  - `Q_INVOKABLE QString NotebookController::pageText(qint64 pageId)` → `m_storage ? m_storage->pageText(pageId) : QString()`
  - `Q_INVOKABLE QVariantList NotebookController::notebooks(const QString &query)` — overload mapping `Storage::notebooks(query)` to the same `{id,title,pageCount}` maps as the no-arg version.

- [ ] **Step 1: Controller methods** — implement both (follow the existing `notebooks()` body); build must stay green.

- [ ] **Step 2: PageScreen TEXT overlay** — add to the rail's top column after AREA (before the UNDO divider): `RailBtn { glyph: "T"; label: "TEXT"; active: pageScreen.showText; onTapped: pageScreen.showText = !pageScreen.showText }` with `property bool showText: false` and `property string recognizedText: ""` on `pageScreen`; loading on toggle and page change: `recognizedText = controller.pageText(pageIds[pageIndex])`. Overlay (z: 2, above ink, right of rail):

```qml
Rectangle {  // recognized-text overlay: read-only, derived data
    visible: pageScreen.showText
    z: 2
    anchors { top: parent.top; right: parent.right; margins: 40 }
    width: 560
    height: Math.min(1400, textCol.implicitHeight + 96)
    color: "white"
    border { color: "black"; width: Theme.chip }
    Rectangle {  // hard-offset elevation
        z: -1
        x: Theme.lift; y: Theme.lift
        width: parent.width; height: parent.height
        color: "white"
        border { color: "black"; width: Theme.chip }
    }
    Flickable {
        anchors { fill: parent; margins: 32 }
        contentHeight: textCol.implicitHeight
        clip: true
        Column {
            id: textCol
            width: parent.width
            spacing: 12
            Text {
                text: "RECOGNIZED TEXT"
                font { family: Theme.mono; pixelSize: Theme.micro; letterSpacing: 2 }
            }
            Text {
                width: parent.width
                wrapMode: Text.Wrap
                text: pageScreen.recognizedText !== "" ? pageScreen.recognizedText
                      : "No text yet — sync after writing; the phone recognizes at its next sync."
                font { family: Theme.reading; pixelSize: 26 }
            }
        }
    }
    TapHandler { onTapped: pageScreen.showText = false }  // tap anywhere to close
}
```

  Also refresh when a sync merges while open: extend Main.qml's existing pattern is NOT needed — PageScreen can simply re-read on `showText` toggle and on `loadPage`; add `recognizedText` reload inside `loadPage()`.

- [ ] **Step 3: NotebookListScreen search** — add a 96px search row between the header band and the list (copy LibraryScreen's search-row structure verbatim: bordered row, TextInput with `Theme.reading` 26px, placeholder "Search notebooks & notes..."), a `property string query: ""`, and change `reload()` to `nbList.items = nbList.query === "" ? nbList.controller.notebooks() : nbList.controller.notebooks(nbList.query)`; reload on text change. The on-screen keyboard appears automatically (focused TextInput) — verify the ListView shrinks (it anchors within the screen; Main.qml already shortens the StackView when the keyboard opens).

- [ ] **Step 4: Verify** — `bash scripts/build.sh && bash scripts/test.sh` green (qmlcachegen validates the QML).

- [ ] **Step 5: Commit** — `git commit -m "feat(rm2): TEXT overlay on notebook pages + notebook search over recognized text"`

---

### Task 12: Hardware E2E + ship

**Files:**
- Modify: `remarkable2-os/STATUS.md` (DONE entry + gaps update), memory note.

- [ ] **Step 1: Device backup (REQUIRED before the v4 binary touches the device):**

```bash
ssh root@10.11.99.1 "cp /home/root/ciphercodex/data.db /home/root/ciphercodex/data-v3-backup.db"
scp root@10.11.99.1:/home/root/ciphercodex/data-v3-backup.db device-backups/data-v3-pre-hwr-$(date +%F).db
```

- [ ] **Step 2: Deploy rM2** — `bash scripts/deploy.sh`; check `shell.log` clean; confirm `ssh root@10.11.99.1` + sqlite via a pulled DB copy that `schema_version` is 4 (pull data.db+wal to scratchpad, `python -c "...sqlite3..."` — no sqlite3 CLI on device).
- [ ] **Step 3: Full loop on real hardware:** write a line of text in an rM2 notebook → back out to Home (auto-sync pushes) → Android (real phone or AVD with recognition enabled) syncs → verify NOTES text panel + `pagesRecognized ≥ 1` → rM2: return to Home (auto-sync pulls) → open the notebook page → TEXT overlay shows the recognized line → notebook search finds a word from it.
- [ ] **Step 4: Convergence checks:** second sync on both sides recognizes nothing new (stamp gating); erase the line on the rM2, double sync, text row goes empty/absent on both sides; delete the notebook on the rM2, sync, Android page_texts row is gone.
- [ ] **Step 5: Update `remarkable2-os/STATUS.md`** — DONE entry for handwriting recognition (schema v4, wire array, UI surfaces, hardware-verified) + move any deferred nits into KNOWN GAPS (e.g., recognition language setting, PageScreen live refresh of the open overlay on merge).
- [ ] **Step 6: Commit + finish** — `git commit -m "feat: handwriting recognition E2E verified on hardware — STATUS updated"`, then use superpowers:finishing-a-development-branch (merge decision includes the Android version release; coordinate with the NOTES track's merge state).

---

## Self-review notes (already applied)

- **Spec coverage:** phone-side recognition (T4/T5), stroke-not-bitmap input incl. timestamps (T1), line segmentation for ML Kit's single-line assumption (T2), derived-data containment (T5 hook + try/catch), opt-in model download (T4), wire contract additive-only (T6/T10 key names match: `pageTexts` / `pageGuid` / `sourceStamp`), staleness via contentStamp (T5), deletes per each side's convention (T5 hard-delete, T9 cascade tombstone), both UI surfaces (T7/T11), sequencing after NOTES Task 5 (Global Constraints), hardware E2E (T12).
- **Type consistency:** `InkPoint.t: Long` (T1) consumed by T2/T4/T5; `RecStroke` defined once (T2), used T4/T5; `PageTextEntity(pageGuid, text, sourceStamp, updatedAt)` (T3) used T5/T6/T7; wire `SnapPageText` field names = rM2 JSON keys (T6 ↔ T10); `Storage::pageText`/`notebooks(query)` (T9) consumed by T11's Q_INVOKABLEs.
- **Known ceilings (deliberate):** greedy line banding merges column layouts (`ponytail:` comment in T2); en-US only; recognition reruns per changed page only, but a full-notebook rewrite recognizes every page in one sync (acceptable: bounded by writing speed); rM2 TEXT overlay doesn't live-refresh if a merge lands while it is open (reload on toggle/page-change only).
