# RESUME HERE — WebDAV sync (Android), paused 2026-07-12

Handoff doc for the next Claude session. The work is mid-execution and cleanly pausable/resumable.

## What this is

Executing `docs/superpowers/plans/2026-07-12-webdav-sync-android.md` (8 tasks) via the
**superpowers:subagent-driven-development** skill: fresh implementer subagent per task → task
review (spec + quality) → ledger. Design spec (owner-approved):
`docs/superpowers/specs/2026-07-12-webdav-sync-android-x4-design.md`. Wire contract (frozen):
`remarkable2-os/docs/phase3b-contracts.md`.

## Exact state

- Branch: `webdav-sync-android` (branched from main at `591b398`). Do NOT work on main;
  merging to main is the ship step (version bump on main auto-releases via CI).
- Ledger (authoritative over memory): `.superpowers/sdd/progress.md` —
  **Task 1 complete** (`d63f2cc`, Room v7 migration, review clean). Tasks 2–8 pending.
- Task briefs/reports/diffs live in `.superpowers/sdd/` (git-ignored scratch; `git clean -fdx`
  destroys it — recover from `git log` + this doc).
- Session task list (TaskList tool) mirrors the 8 plan tasks; #1 completed.

## ⚠️ Stray commit from a parallel session

`1d5db49` ("Phase 3 review fixes: sync DB-contention, PDFium thread-safety, +11 more",
touches only `remarkable2-os/`) was committed onto `webdav-sync-android` by a PARALLEL Claude
session working on the reMarkable in this same checkout. Handling:

- It is harmless here — it rides along and reaches main in the eventual merge. If the rM2
  session needs it on main sooner, cherry-pick it to main; do NOT rebase/drop it blindly.
- Lesson: two sessions share this working tree. While this plan is executing (subagents edit
  files + commit), do not run another session that builds/commits in this repo, or at minimum
  never simultaneously with a dispatched implementer.

## How to resume

1. `git checkout webdav-sync-android`, read `.superpowers/sdd/progress.md`, `git log --oneline
   591b398..HEAD` to confirm state.
2. Invoke the **superpowers:subagent-driven-development** skill for
   `docs/superpowers/plans/2026-07-12-webdav-sync-android.md`, resuming at the first task not
   in the ledger (Task 2). The plan file is self-contained (complete code per task).
3. Per-task dispatch notes already decided (keep them):
   - Extract each brief with the skill's `scripts/task-brief PLAN 2` (bash). Record the base
     commit BEFORE dispatching; build the review diff with `scripts/review-package BASE HEAD`.
   - Models: Task 2 sonnet; Tasks 3/4/5 haiku (plan contains complete code — transcription);
     Task 6 sonnet; Task 7 sonnet; reviewers sonnet; final whole-branch review on the most
     capable model.
   - **Task 6 dispatch must also include Task 7 Step 1** (the UserPrefs
     webdavUrl/webdavUser/webdavPass/webdavLastSyncAt fields) or Task 6 won't compile; then
     Task 7's brief says Step 1 may already be done.
   - Environment for implementers: build from `android/` with
     `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`; grep output for
     "BUILD SUCCESSFUL" (piping to tail masks the exit code); JAVA_HOME is JDK 21.
4. Task 8 needs things only the owner can provide/do: the `ccx` WebDAV app password for
   `https://kosync.cph.gg/ccx/` (or run a local dufs), and the `cipher` emulator
   (emulator-5554, boot headless: `emulator -avd cipher -no-window`). adb from Git Bash needs
   `MSYS_NO_PATHCONV=1`; adb file args must be relative/Windows paths.
5. After Task 8 passes: final whole-branch review (superpowers:requesting-code-review
   template, package via `scripts/review-package $(git merge-base main HEAD) HEAD`), fix wave
   as ONE subagent if findings, then **superpowers:finishing-a-development-branch** — merge to
   main with the `versionName` bump = auto-release (v0.5.0).

## After Android ships

Write the X4 firmware plan (spec slices X1–X3) with **superpowers:writing-plans** as its own
plan file, then execute it the same way. X4 work happens in `x4-os/` which is a SEPARATE git
repo (gitignored from this one, remote `mrzeappleGit/ciphercodex-os`, branch `develop`) with
its own `.claude/skills` (HAL/heap/scope discipline) that implementers must follow.
