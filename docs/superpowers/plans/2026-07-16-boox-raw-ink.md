# Boox Raw Ink Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** rM2-class wet-ink latency + hardware pressure width in the Android ink editor on Onyx/Boox devices, by swapping only the wet-ink overlay for the Onyx Pen SDK's raw drawing path.

**Architecture:** On Onyx devices (`Build.MANUFACTURER == "ONYX"`), the PEN-mode overlay in `InkEditorScreen` becomes a plain view driven by Onyx `TouchHelper` — the e-ink controller draws the wet stroke in hardware. The finished stroke's `TouchPointList` is converted by a pure function into the existing `List<InkPoint>` and fed to the untouched `vm.commitStroke(...)` → Room → render/sync pipeline. While raw mode is active the EPD defers normal view refreshes, so the committed-strokes Canvas stays live; after undo/redo the VM bumps a `repaintTick` and the overlay briefly releases raw mode so the repaint reaches the screen. Non-Onyx devices keep the current Jetpack Ink path byte-for-byte.

**Tech Stack:** Kotlin, Jetpack Compose, `com.onyx.android.sdk:onyxsdk-pen:1.4.11` (repo.boox.com), JUnit4, Room (unchanged).

**Spec:** `docs/superpowers/specs/2026-07-16-boox-raw-ink-design.md`

## Global Constraints

- Onyx SDK classes may ONLY be referenced behind `isOnyxDevice()` (or inside `BooxRawInk.kt`, which is only entered behind that gate). Non-Onyx behavior must be unchanged.
- Wire format is frozen: `InkPoint(x: Float 0..1, y: Float 0..1, pressure: Int 0..4095, t: Long ms-since-stroke-start)`; 18-byte PackedPoint encoding; Room schema, sync protocol, rM2 side change ZERO.
- Onyx dependency pinned to exactly `1.4.11`; `isAllowInsecureProtocol = true` scoped to the `repo.boox.com` repo only, with a content filter for `com.onyx.*`.
- All gradle commands run from `G:\nextcloud\projects\cipherCodex\android` using `.\gradlew.bat`.
- Repo branch: `android-ink-authoring`. Commit after every task.
- Editor UX contract (unchanged): toolbar PEN/ERASE/UNDO/REDO/+PAGE/‹/› keep working; erase tool uses the existing Compose gesture path.

---

### Task 1: Onyx SDK dependency + device gate + point conversion (TDD)

**Files:**
- Modify: `android/settings.gradle.kts` (add Onyx maven repo)
- Modify: `android/gradle/libs.versions.toml` (version + library entry)
- Modify: `android/app/build.gradle.kts` (dependency)
- Modify: `android/app/proguard-rules.pro` (keep rules)
- Create: `android/app/src/main/java/tech/mrzeapple/ciphercodex/ui/notes/BooxRawInk.kt`
- Test: `android/app/src/test/java/tech/mrzeapple/ciphercodex/ui/notes/BooxRawInkMathTest.kt`

**Interfaces:**
- Consumes: `InkPoint` from `tech.mrzeapple.ciphercodex.sync.webdav.InkPoints` (`data class InkPoint(val x: Float, val y: Float, val pressure: Int, val t: Long = 0)`).
- Produces (Task 2 relies on these exact names):
  - `fun isOnyxDevice(): Boolean` (top-level in `BooxRawInk.kt`)
  - `object BooxRawInkMath { const val FALLBACK_MAX_PRESSURE = 4096f; fun rawPointToInk(x: Float, y: Float, pressure: Float, timestampMs: Long, strokeStartMs: Long, viewW: Float, viewH: Float, maxPressure: Float): InkPoint }`

- [ ] **Step 1: Add the Onyx maven repository**

In `android/settings.gradle.kts`, replace the `dependencyResolutionManagement` block with:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("http://repo.boox.com/repository/maven-public/")
            // Onyx publishes over plain HTTP; risk contained by the exact version pin
            // in libs.versions.toml and the group content filter below.
            isAllowInsecureProtocol = true
            content {
                includeGroupByRegex("com\\.onyx.*")
                // Transitives of onyxsdk-base that exist ONLY on repo.boox.com
                // (jcenter-era or Onyx forks): hugo fork, easypermissions 0.2.1,
                // mmkv 1.0.19.
                includeGroup("com.jakewharton.hugo.fix")
                includeModule("pub.devrel", "easypermissions")
                includeModule("com.tencent", "mmkv")
            }
        }
    }
}
```

> **Network note (2026-07-16):** repo.boox.com is unreachable from the dev PC
> (China-hosted; connection refused internationally). The full `com.onyx` closure
> (onyxsdk-pen 1.4.11 → onyxsdk-base 1.7.6 → onyxsdk-device 1.2.30 +
> onyxsdk-commons-io 2.5 + hugo-annotations 1.2.3 + easypermissions 0.2.1 +
> mmkv 1.0.19) is mirrored at `C:\Users\mrzea\boox-maven-mirror` and served by the
> machine-local init script `C:\Users\mrzea\.gradle\init.d\boox-local-mirror.gradle`
> (content-filtered to those groups, consulted before the repos above). The
> committed settings.gradle.kts stays canonical for machines that can reach
> repo.boox.com.

- [ ] **Step 2: Add the pinned dependency**

In `android/gradle/libs.versions.toml`, add under `[versions]`:

```toml
onyxsdkPen = "1.4.11"
```

and under `[libraries]`:

```toml
onyxsdk-pen = { module = "com.onyx.android.sdk:onyxsdk-pen", version.ref = "onyxsdkPen" }
```

In `android/app/build.gradle.kts` `dependencies { }`, after `implementation(libs.androidx.ink.strokes)` add:

```kotlin
// Boox raw-ink fast path; classes only referenced behind isOnyxDevice().
implementation(libs.onyxsdk.pen)
```

- [ ] **Step 3: Add proguard keep rules**

Append to `android/app/proguard-rules.pro`:

```
# Onyx Pen SDK — raw input dispatches through JNI/reflection into SDK classes
-keep class com.onyx.** { *; }
-dontwarn com.onyx.**
```

- [ ] **Step 4: Write the failing test**

Create `android/app/src/test/java/tech/mrzeapple/ciphercodex/ui/notes/BooxRawInkMathTest.kt`:

```kotlin
package tech.mrzeapple.ciphercodex.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class BooxRawInkMathTest {
    @Test
    fun mapsViewCoordsPressureAndTime() {
        val p = BooxRawInkMath.rawPointToInk(
            x = 702f, y = 936f, pressure = 2048f, timestampMs = 1100L,
            strokeStartMs = 1000L, viewW = 1404f, viewH = 1872f, maxPressure = 4096f,
        )
        assertEquals(0.5f, p.x, 1e-6f)
        assertEquals(0.5f, p.y, 1e-6f)
        assertEquals(2047, p.pressure) // 2048/4096 * 4095 = 2047.5, truncated
        assertEquals(100L, p.t)
    }

    @Test
    fun clampsEverythingOutOfRange() {
        val p = BooxRawInkMath.rawPointToInk(
            x = -5f, y = 99999f, pressure = 99999f, timestampMs = 500L,
            strokeStartMs = 1000L, viewW = 1404f, viewH = 1872f, maxPressure = 4096f,
        )
        assertEquals(0f, p.x, 0f)
        assertEquals(1f, p.y, 0f)
        assertEquals(4095, p.pressure)
        assertEquals(0L, p.t) // timestamp before stroke start clamps to 0
    }

    @Test
    fun nonPositiveMaxPressureUsesFallback() {
        val p = BooxRawInkMath.rawPointToInk(
            x = 0f, y = 0f, pressure = 4096f, timestampMs = 0L,
            strokeStartMs = 0L, viewW = 100f, viewH = 100f, maxPressure = 0f,
        )
        assertEquals(4095, p.pressure) // 4096/4096 * 4095, clamped
    }

    @Test
    fun zeroViewSizeDoesNotDivideByZero() {
        val p = BooxRawInkMath.rawPointToInk(
            x = 10f, y = 10f, pressure = 100f, timestampMs = 0L,
            strokeStartMs = 0L, viewW = 0f, viewH = 0f, maxPressure = 4096f,
        )
        assertEquals(1f, p.x, 0f) // 10 / max(0,1) = 10, clamped to 1
        assertEquals(1f, p.y, 0f)
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run (from `android/`): `.\gradlew.bat :app:testDebugUnitTest --tests "tech.mrzeapple.ciphercodex.ui.notes.BooxRawInkMathTest"`
Expected: FAIL — unresolved reference `BooxRawInkMath` (compile error counts as the failing state).

- [ ] **Step 6: Write the implementation**

Create `android/app/src/main/java/tech/mrzeapple/ciphercodex/ui/notes/BooxRawInk.kt`:

```kotlin
package tech.mrzeapple.ciphercodex.ui.notes

import android.os.Build
import tech.mrzeapple.ciphercodex.sync.webdav.InkPoint

/** Gate for the Boox raw-ink fast path. Onyx SDK classes may only be referenced
 *  behind this check (or in code only reachable behind it). */
fun isOnyxDevice(): Boolean = Build.MANUFACTURER.equals("ONYX", ignoreCase = true)

object BooxRawInkMath {
    /** Wacom EMR digitizers report 4096 pressure levels.
     *  ponytail: fallback + calibration knob in one — if the Go 10.3 Gen 2's
     *  EpdController.getMaxTouchPressure() reads 0/absent, hardware QA tunes this. */
    const val FALLBACK_MAX_PRESSURE = 4096f

    /** One raw SDK touch point (view-local px, raw pressure, epoch-ms timestamp) →
     *  wire InkPoint (page-normalized 0..1, pressure 0..4095, ms since stroke start). */
    fun rawPointToInk(
        x: Float,
        y: Float,
        pressure: Float,
        timestampMs: Long,
        strokeStartMs: Long,
        viewW: Float,
        viewH: Float,
        maxPressure: Float,
    ): InkPoint {
        val maxP = if (maxPressure > 0f) maxPressure else FALLBACK_MAX_PRESSURE
        return InkPoint(
            x = (x / viewW.coerceAtLeast(1f)).coerceIn(0f, 1f),
            y = (y / viewH.coerceAtLeast(1f)).coerceIn(0f, 1f),
            pressure = ((pressure / maxP) * 4095f).toInt().coerceIn(0, 4095),
            t = (timestampMs - strokeStartMs).coerceAtLeast(0L),
        )
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "tech.mrzeapple.ciphercodex.ui.notes.BooxRawInkMathTest"`
Expected: PASS (4 tests). First run also proves the Onyx repo resolves `onyxsdk-pen:1.4.11` (dependency download happens even though nothing imports it yet).

- [ ] **Step 8: Verify the Onyx SDK classes resolve at compile time**

Temporarily add these imports at the top of `BooxRawInk.kt`, run `.\gradlew.bat :app:compileDebugKotlin`, then REMOVE them again before committing (Task 2 adds the real usages):

```kotlin
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.data.TouchPointList
import com.onyx.android.sdk.api.device.epd.EpdController
```

Expected: compiles (warnings about unused imports are fine). **If `EpdController` alone is unresolved:** note it in your report — Task 2 has a documented fallback (use `FALLBACK_MAX_PRESSURE`, no EpdController) and needs to know. If `TouchHelper`/`RawInputCallback`/`TouchPointList` are unresolved, STOP and report — the dependency coordinates are wrong.

- [ ] **Step 9: Run the full unit suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: all green (existing suite + 4 new).

- [ ] **Step 10: Commit**

```powershell
git add android/settings.gradle.kts android/gradle/libs.versions.toml android/app/build.gradle.kts android/app/proguard-rules.pro android/app/src/main/java/tech/mrzeapple/ciphercodex/ui/notes/BooxRawInk.kt android/app/src/test/java/tech/mrzeapple/ciphercodex/ui/notes/BooxRawInkMathTest.kt
git commit -m "feat(ink): Onyx Pen SDK dependency + Boox raw-point conversion"
```

---

### Task 2: Raw-ink overlay + repaint tick + screen integration

**Files:**
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/ui/notes/BooxRawInk.kt` (add overlay composable)
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/ui/notes/InkEditorViewModel.kt` (repaintTick)
- Modify: `android/app/src/main/java/tech/mrzeapple/ciphercodex/ui/notes/InkEditorScreen.kt` (Onyx branch)

**Interfaces:**
- Consumes: `isOnyxDevice()`, `BooxRawInkMath.rawPointToInk(...)`, `BooxRawInkMath.FALLBACK_MAX_PRESSURE` (Task 1); `vm.commitStroke(points: List<InkPoint>)` and `vm.repaintTick: MutableStateFlow<Int>` (this task); `InkRender.PAGE_W` (existing, = 1404).
- Produces: `@Composable fun BooxRawInkOverlay(vm: InkEditorViewModel, modifier: Modifier = Modifier)`.

- [ ] **Step 1: Add `repaintTick` to the ViewModel**

In `InkEditorViewModel.kt`, after the `val canRedo = MutableStateFlow(false)` line, add:

```kotlin
    /** Bumped after an undo/redo lands in Room. The Boox raw-ink overlay collects this
     *  and briefly releases raw drawing so the repainted stroke set reaches the screen
     *  (raw mode defers normal view refreshes). Non-Boox path ignores it. */
    val repaintTick = MutableStateFlow(0)
```

In `undo()`, change the `when` block's surrounding code so the tick bumps only when an op was actually popped — the full method becomes:

```kotlin
    fun undo() {
        viewModelScope.launch {
            opMutex.withLock {
                val op = undoStack.removeLastOrNull() ?: return@withLock
                when (op) {
                    // eraseStroke == null: stroke already gone (e.g. sync merge) — drop the op.
                    is Add -> if (author.eraseStroke(op.s.guid) != null) redoStack.addLast(op)
                    is Erase -> { author.restoreStroke(op.s); redoStack.addLast(op) }
                }
                bump()
                repaintTick.value++
            }
        }
    }
```

And `redo()` becomes:

```kotlin
    fun redo() {
        viewModelScope.launch {
            opMutex.withLock {
                val op = redoStack.removeLastOrNull() ?: return@withLock
                when (op) {
                    is Add -> { author.restoreStroke(op.s); undoStack.addLast(op) }
                    // eraseStroke == null: stroke already gone — drop the op.
                    is Erase -> if (author.eraseStroke(op.s.guid) != null) undoStack.addLast(op)
                }
                bump()
                repaintTick.value++
            }
        }
    }
```

(Only change in each: the added `repaintTick.value++` line after `bump()`.)

- [ ] **Step 2: Add the overlay composable**

Append to `BooxRawInk.kt`:

```kotlin
private class RawInkView(ctx: android.content.Context) : android.view.View(ctx) {
    var helper: com.onyx.android.sdk.pen.TouchHelper? = null
    var opened = false
}

/** Boox wet-ink layer: the EPD controller draws the stroke in hardware (rM2-class
 *  latency, pressure-width via the fountain brush); the finished stroke's points are
 *  converted and committed through the exact pipeline the Jetpack Ink path uses.
 *  While raw drawing is enabled the EPD defers normal view refreshes, so the
 *  committed-strokes Canvas underneath recomposes invisibly per commit — the screen
 *  catches up whenever raw mode is released (undo/redo pulse, tool switch, dispose). */
@androidx.compose.runtime.Composable
fun BooxRawInkOverlay(
    vm: InkEditorViewModel,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    val helperRef = androidx.compose.runtime.remember {
        arrayOfNulls<com.onyx.android.sdk.pen.TouchHelper>(1)
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        // Raw mode locks its screen region; never leave it enabled while paused.
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE ->
                    helperRef[0]?.setRawDrawingEnabled(false)
                androidx.lifecycle.Lifecycle.Event.ON_RESUME ->
                    helperRef[0]?.setRawDrawingEnabled(true)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.flow.drop(vm.repaintTick, 1).collect {
            // Release raw mode so the undone/redone stroke list repaints on the EPD,
            // then re-arm. ponytail: 200ms settle covers Room emission + recompose +
            // draw; tune on hardware if the repaint races it.
            helperRef[0]?.setRawDrawingEnabled(false)
            kotlinx.coroutines.delay(200)
            helperRef[0]?.setRawDrawingEnabled(true)
        }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val view = RawInkView(ctx)
            val callback = object : com.onyx.android.sdk.pen.RawInputCallback() {
                override fun onBeginRawDrawing(b: Boolean, p: com.onyx.android.sdk.data.note.TouchPoint) {}
                override fun onEndRawDrawing(b: Boolean, p: com.onyx.android.sdk.data.note.TouchPoint) {}
                override fun onRawDrawingTouchPointMoveReceived(p: com.onyx.android.sdk.data.note.TouchPoint) {}
                override fun onRawDrawingTouchPointListReceived(list: com.onyx.android.sdk.pen.data.TouchPointList) {
                    val pts = list.points ?: return
                    if (pts.isEmpty()) return
                    val maxP = runCatching {
                        com.onyx.android.sdk.api.device.epd.EpdController.getMaxTouchPressure()
                    }.getOrDefault(BooxRawInkMath.FALLBACK_MAX_PRESSURE)
                    val t0 = pts.first().timestamp
                    val viewW = view.width.toFloat()
                    val viewH = view.height.toFloat()
                    vm.commitStroke(pts.map { p ->
                        BooxRawInkMath.rawPointToInk(
                            p.x, p.y, p.pressure, p.timestamp, t0, viewW, viewH, maxP,
                        )
                    })
                }
                // Hardware eraser end / erase gestures unused — ERASE is a toolbar tool
                // on the normal (non-raw) path.
                override fun onBeginRawErasing(b: Boolean, p: com.onyx.android.sdk.data.note.TouchPoint) {}
                override fun onEndRawErasing(b: Boolean, p: com.onyx.android.sdk.data.note.TouchPoint) {}
                override fun onRawErasingTouchPointMoveReceived(p: com.onyx.android.sdk.data.note.TouchPoint) {}
                override fun onRawErasingTouchPointListReceived(l: com.onyx.android.sdk.pen.data.TouchPointList) {}
            }
            val helper = com.onyx.android.sdk.pen.TouchHelper.create(view, callback)
            view.helper = helper
            helperRef[0] = helper
            view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val rv = v as RawInkView
                if (!rv.opened && v.width > 0 && v.height > 0) {
                    rv.opened = true
                    val limit = android.graphics.Rect()
                    // ponytail: local rect per the Onyx demo; if strokes land offset on
                    // hardware, switch to getGlobalVisibleRect (known one-line fix).
                    v.getLocalVisibleRect(limit)
                    helper.setStrokeWidth(9f * v.width / tech.mrzeapple.ciphercodex.sync.webdav.InkRender.PAGE_W)
                        .setStrokeStyle(com.onyx.android.sdk.pen.TouchHelper.STROKE_STYLE_FOUNTAIN)
                        .setStrokeColor(android.graphics.Color.BLACK)
                        .setLimitRect(limit, ArrayList())
                        .openRawDrawing()
                    helper.setRawDrawingEnabled(true)
                }
            }
            view
        },
        onRelease = { v ->
            (v as RawInkView).helper?.closeRawDrawing()
            helperRef[0] = null
        },
    )
}
```

Notes for the implementer:
- Convert the fully-qualified names above to imports at the top of the file — fully-qualified is shown only so every referenced symbol is explicit. Keep the existing `isOnyxDevice()` gate comment intact.
- `LocalLifecycleOwner` import: use `androidx.lifecycle.compose.LocalLifecycleOwner`; if that doesn't resolve with the project's lifecycle 2.8.7, use `androidx.compose.ui.platform.LocalLifecycleOwner` (deprecated alias, fine).
- `kotlinx.coroutines.flow.drop(vm.repaintTick, 1)` is the extension `vm.repaintTick.drop(1)` — write it as the extension call.
- **EpdController fallback (from Task 1 Step 8's report):** if `EpdController` did not resolve, delete the `runCatching { ... }` lookup and pass `BooxRawInkMath.FALLBACK_MAX_PRESSURE` directly as `maxP`.
- `TouchPointList.points` / `TouchPoint.x/.y/.pressure/.timestamp` are the SDK's public accessors. If any accessor name differs at compile time (e.g. `getPoints()` exposes a different property name in Kotlin), adapt at the call site only — `BooxRawInkMath.rawPointToInk` and its tests must not change.

- [ ] **Step 3: Branch the wet-ink layer in `InkEditorScreen`**

In `InkEditorScreen.kt`, inside `if (tool == EditorTool.PEN) { key(pageGuid) { ... } }`, wrap the existing `AndroidView(...)` call in an Onyx branch, so the block becomes:

```kotlin
                    key(pageGuid) {
                        if (isOnyxDevice()) {
                            // Boox: hardware raw ink (rM2-class latency + pressure);
                            // same commitStroke pipeline, no wet-view handoff needed.
                            BooxRawInkOverlay(vm, Modifier.fillMaxSize())
                        } else {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    // ... existing factory body, UNCHANGED ...
                                },
                            )
                        }
                    }
```

Do not modify anything inside the existing `AndroidView` factory. No other change in this file.

- [ ] **Step 4: Compile + full unit suite**

Run: `.\gradlew.bat :app:compileDebugKotlin` then `.\gradlew.bat :app:testDebugUnitTest`
Expected: compiles; all tests green (the overlay has no JVM-testable logic — its math is Task 1's tested function; the repaintTick bump is a one-line state change exercised on hardware).

- [ ] **Step 5: Non-Onyx smoke test on the emulator**

An emulator (`Build.MANUFACTURER` = "Google") must take the Jetpack Ink branch. Build and install the debug APK on the running emulator if one is available (`.\gradlew.bat :app:installDebug`), open a notebook page, draw with the mouse-as-stylus disabled — mouse/finger input starts no stroke (existing behavior), and the app must not crash on the NOTES editor. If no emulator is available in the session, state that in the report; the compile + suite green is the gate, and non-Onyx smoke rides Task 4's checklist.

- [ ] **Step 6: Commit**

```powershell
git add android/app/src/main/java/tech/mrzeapple/ciphercodex/ui/notes/BooxRawInk.kt android/app/src/main/java/tech/mrzeapple/ciphercodex/ui/notes/InkEditorViewModel.kt android/app/src/main/java/tech/mrzeapple/ciphercodex/ui/notes/InkEditorScreen.kt
git commit -m "feat(ink): Boox raw-drawing wet ink — hardware latency + pressure"
```

---

### Task 3: Release build gate + version bump

**Files:**
- Modify: `android/app/build.gradle.kts:17-18` (versionCode 31, versionName "0.9.0")

**Interfaces:**
- Consumes: everything from Tasks 1–2.
- Produces: a minified release APK that R8 accepts with the Onyx SDK on the classpath; v0.9.0 identifiers.

- [ ] **Step 1: Bump the version**

In `android/app/build.gradle.kts` `defaultConfig`:

```kotlin
        versionCode = 31
        versionName = "0.9.0"
```

- [ ] **Step 2: Full unit suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: all green.

- [ ] **Step 3: Release build (R8 + Onyx SDK is the real test here)**

Run: `.\gradlew.bat :app:assembleRelease` (this is a long build — run it in the foreground with a 600000ms timeout)
Expected: BUILD SUCCESSFUL, `app/build/outputs/apk/release/app-release.apk` produced. If R8 fails on missing classes referenced by the Onyx SDK, add the specific `-dontwarn <package>.**` lines R8's error output names to `proguard-rules.pro` (do NOT loosen the `-keep com.onyx.**` rule) and rebuild.

- [ ] **Step 4: Commit**

```powershell
git add android/app/build.gradle.kts android/app/proguard-rules.pro
git commit -m "release: v0.9.0 — Boox raw ink (hardware wet-ink latency + pressure)"
```

(If proguard-rules.pro is unchanged, commit just build.gradle.kts.)

---

### Task 4: Hardware QA on the Boox Go 10.3 Gen 2 (NEEDS OWNER)

No code. Owner installs the v0.9.0 APK on the Boox Go 10.3 Gen 2 and runs this checklist; findings loop back as fix tasks. This extends the already-pending v0.8.0 Task 9 hardware session (two-way loop vs rM2).

- [ ] Wet-ink latency: write a line of text — ink must track the pen like the Boox notes app / rM2, not trail like v0.8.0.
- [ ] Pressure: light vs heavy strokes show visibly different width **while writing** (hardware fountain brush) AND after the stroke settles (our renderer).
- [ ] Stroke alignment: hardware ink and the committed render land in the same place (if offset: switch `getLocalVisibleRect` → `getGlobalVisibleRect` in `BooxRawInk.kt` — known calibration point).
- [ ] Pressure calibration: synced strokes on the rM2 show a width range comparable to rM2-native strokes (if flat/blown out: tune the max-pressure normalization — `FALLBACK_MAX_PRESSURE` / `EpdController.getMaxTouchPressure()` path).
- [ ] Undo/redo while PEN active: stroke disappears/reappears within ~200ms pulse; no ghost hardware ink left behind (if the repaint races the pulse: raise the 200ms settle delay).
- [ ] Stroke integrity: one physical stroke = one committed stroke (draw a long slow spiral, then check the page has ONE new stroke row / the rM2 renders it as one). If the SDK delivers `onRawDrawingTouchPointListReceived` in multiple batches per stroke, strokes fragment into several Room rows with per-batch t0 — fix would buffer batches between onBeginRawDrawing/onEndRawDrawing.
- [ ] Per-stroke flicker check: writing several strokes must NOT visibly refresh/settle after each stroke. If it does (EPD not deferring view refreshes in raw mode on this firmware), apply the documented contingency: filter wet-session guids out of the committed Canvas in the VM until the next repaint event (small VM-only change — see spec §5).
- [ ] Erase tool: toolbar ERASE still erases by touch; switching ERASE→PEN re-arms raw drawing.
- [ ] Page nav ‹/›/+PAGE and DONE: raw layer closes cleanly (no locked screen region afterward), strokes persist, editor-close sync fires.
- [ ] Two-way loop vs rM2 (v0.8.0 Task 9 checklist): Boox-written page appears on rM2 with correct geometry/pressure; rM2-written page renders on Boox.
- [ ] Non-Onyx smoke: debug APK on emulator or any regular Android device — editor opens, Jetpack Ink path draws, no Onyx class loaded (no crash).
- [ ] After QA passes: push branch, tag/release per the project's Android release ritual.
