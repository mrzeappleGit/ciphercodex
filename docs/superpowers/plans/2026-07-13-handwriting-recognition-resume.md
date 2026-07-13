# RESUME — handwriting recognition execution (paused after Task 4 of 12)

**Paused:** 2026-07-13, owner request, clean task boundary.
**Branch:** `handwriting-recognition` (base 7dcd601; contains main's v0.6.0 merge + the rM2
sleep feature + the plan docs). Working tree was clean at pause.
**Plan:** `docs/superpowers/plans/2026-07-12-handwriting-recognition.md` (12 tasks)
**Spec:** `docs/superpowers/specs/2026-07-12-handwriting-recognition-design.md`
**Authoritative ledger:** `.superpowers/sdd/progress.md` (PLAN 3 section) — trust it and
`git log` over conversation memory.

## How to resume

1. `git branch --show-current` → must be `handwriting-recognition`. Check
   `git log --oneline -8` against the ledger — a parallel session shares this checkout
   (stray commit 8627e2a "Plan: X4 firmware WebDAV sync" already rides along; more may
   appear; never run two committing sessions at once).
2. Invoke `superpowers:subagent-driven-development` and resume at **Task 5**.
   Per task: `task-brief PLAN 5` (script in the skill's scripts/), record BASE =
   `git rev-parse --short HEAD` immediately before dispatching, implementer subagent
   (haiku for transcription tasks, sonnet for integration), then
   `review-package <BASE-or-implementer-parent> <task-head>` scoped to the task's own
   commits (strays interleave), reviewer subagent, ledger line.
3. Android build check: from `android/`, PowerShell `.\gradlew.bat :app:assembleDebug
   :app:testDebugUnitTest` → BUILD SUCCESSFUL (JDK 21). rM2 (Tasks 9+): from
   `remarkable2-os/`, `bash scripts/build.sh && bash scripts/test.sh` (Docker Desktop must
   be running; image `ccx-rm2-sdk:3.27.0.97`).

## State: Tasks 1–4 COMPLETE (all reviews approved)

| Task | Commit | Delivered |
|---|---|---|
| 1 | aad0be5 | `InkPoint` gained `t: Long = 0` (tMs decoded, unsigned) |
| 2 | df0b084 | `sync/recognition/InkLines.kt` — `RecStroke`, `InkLines.segment` y-band segmenter |
| 3 | a131bfc | Room v9 — `PageTextEntity` + NotesDao methods + `MIGRATION_8_9` |
| 4 | 93bc8f5 | ML Kit 19.0.0 dep, `HandwritingRecognizer` wrapper, `handwritingRecognition` pref (default OFF) + Settings toggle w/ model download |

Interfaces later tasks consume (verified in code, use exactly):
- `InkPoint(x: Float, y: Float, pressure: Int, t: Long = 0)` — x/y normalized 0..1
- `RecStroke(points: List<InkPoint>, createdAt: Long)`; `InkLines.segment(List<RecStroke>): List<List<RecStroke>>`
- `PageTextEntity(pageGuid, text, sourceStamp, updatedAt)`; NotesDao: `pageText/allPageTexts/observeAllPageTexts/upsertPageText/deletePageText`
- `HandwritingRecognizer`: `modelDownloaded()`, `downloadModel()`, `recognizeLine(line, preContext): String`, `close()` — blocking, Dispatchers.IO only. ML Kit 19.0.0 package paths CONFIRMED against the AAR: recognizer classes `com.google.mlkit.vision.digitalink.recognition.*`, results `...digitalink.common.*`.
- Prefs pattern: `UserPrefs.kt` `handwritingRecognition` follows the `einkMode` boolean shape.

## Remaining: Tasks 5–12

- **5** recognition pass in `InkSync` (RecognitionPass + hard-delete page_texts with pages) — sonnet
- **6** `pageTexts` wire array (Snapshot/SnapshotMerge/exportSnapshot/applyMerged) — sonnet
- **7** NOTES UI (viewer text panel + search) — sonnet
- **8** Android gate: AVD v8→v9 migration + E2E vs `https://kosync.cph.gg/ccx/scratch-android/` (never live /ccx/) + version bump (next minor above 0.6.0; versionCode 28→29). Emulator name `cipher`.
- **9** rM2 schema v4 `page_text` (do NOT touch kSyncedTables — backfill trap) — sonnet
- **10** rM2 SyncStore merge+re-export `pageText` + test_sync tests — sonnet
- **11** rM2 UI: TEXT rail overlay + notebook search — sonnet
- **12** hardware E2E: REQUIRED device backup first (`cp data.db data-v3-backup.db` on device + scp to device-backups/), deploy, full loop with the phone; needs the OWNER present (device wake, writing a test line). Then STATUS.md/memory updates and superpowers:finishing-a-development-branch (final whole-branch review BEFORE merge, most capable model, package via `review-package 7dcd601 HEAD`).

Minor findings ledgered for the final review to triage: multi-band-overlap greedy merge +
upper-middle median (Task 2), per-toggle recognizer instance + broad catch vs
CancellationException (Task 4).

## Environment facts that bite

- Device: rM2 over USB `root@10.11.99.1`; sleeps after 10 min idle now (wake = power
  button) — a "device unreachable" during rM2 tasks may just mean it fell asleep.
- WebDAV server: dufs at `https://kosync.cph.gg/ccx/` (`ssh hostinger`); scratch area for
  Android E2E is `ccx/scratch-android/`; nginx access log shows the request trail.
- The rM2 shell's own webdav DB lives at `/home/root/ciphercodex/data.db` (no sqlite3 CLI
  on device — pull the db+wal and inspect with host python).
