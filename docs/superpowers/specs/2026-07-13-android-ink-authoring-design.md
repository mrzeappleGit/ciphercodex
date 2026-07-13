# Android ink authoring — design

**Date:** 2026-07-13
**Goal:** Handwrite notes on Android (owner's Boox Go 10.3 Gen 2, stylus) and sync ink
across devices with the reMarkable 2 — full two-way editing, including both devices
adding ink to the same page.

This deliberately retires the **"Android never emits ink"** rule from the
webdav-sync-android track. That rule was scaffolding for the viewer era (Android had no
local stroke store, so it could not round-trip ink faithfully). With a first-class
stroke store it no longer applies. The wire contract does not change — Android starts
emitting arrays the rM2 already emits and merges.

## Decisions (owner-approved)

- **Edit scope:** full two-way. Any notebook, any page, either device; strokes merge
  per-guid so simultaneous same-page edits interleave safely.
- **Ink tech:** Jetpack Ink (`androidx.ink`) — front-buffer wet ink + motion
  prediction; device-agnostic (Boox, phone, AVD). No Boox SDK.
- **Storage:** Approach A — Room v10 `strokes` table as the merged local truth,
  keeping the existing PNG render pipeline for thumbnails/viewer.
- Stylus-only input in v1 (finger ignored; free palm rejection).
- Stroke-level eraser only (tombstone whole strokes — matches the wire model and the
  rM2's eraser).
- Page geometry stays rM2 3:4, 1404×1872 normalized 0..1 (the Boox Go 10.3 panel is
  the same resolution — 1:1 mapping).
- Debounced auto-sync on leaving the editor (Android twin of the rM2's Home-return
  trigger).
- v1 scope cuts: no notebook/page deletion UI on Android (rM2 owns that; the eraser
  covers strokes); no finger-drawing setting; no per-notebook pen styles.

## 1. Storage (Room v9 → v10)

New entity, mirroring the wire stroke rows:

```
StrokeEntity(
  guid: String @PrimaryKey,
  pageGuid: String,        // indexed
  pointsB64: String,       // existing InkPoints encoding (x,y norm 0..1, pressure, tMs)
  baseWidth: Float,
  createdAt: Long,
  updatedAt: Long,
  deleted: Int             // 0 live, 1 tombstone
)
```

- `MIGRATION_9_10` creates the table + index on `pageGuid`. No backfill: the next sync
  merge populates it from peer snapshots (full-state snapshots make this free).
- No dirty/outbound marker: snapshots are full-state, so export emits every row
  (live + tombstones), exactly like the rM2 exports its DB.
- Notebook/page creation on Android mints fresh guids into the existing
  notebook/page entities with authored `updatedAt`.
- `page_texts` unchanged. Recognition needs zero changes: locally authored strokes
  change the page's contentStamp, so the existing stamp-gated pass re-recognizes
  Boox handwriting automatically (it becomes searchable on both devices).

## 2. Editor UI

- NOTES viewer (`PageViewer`) gains an EDIT (pencil) action → `InkEditorScreen` for
  the current page.
- Wet ink: Jetpack Ink `InProgressStrokesView` (front-buffer, predicted); finished
  strokes rendered from Room over the page (pressure-weighted widths, same look as
  the PNG renderer).
- Tools: pen (pressure), stroke eraser (hit-test → tombstone with fresh
  `updatedAt`), session-local undo/redo (undo of a new stroke deletes the row; undo
  of an erase resurrects it — session-only, not persisted), page prev/next/add.
- NOTES tab gains new-notebook; viewer gains new-page.
- Input policy: stylus (`TOOL_TYPE_STYLUS`) only; finger events ignored in the canvas.
- Stroke commit: each finished stroke converts to wire points (normalize to 3:4
  page space, pressure scaled to the rM2's 12-bit range, per-point tMs) and upserts
  to Room immediately — process death loses at most the in-progress stroke.
- Exit (back/nav) → debounced sync trigger.

## 3. Sync rework (InkSync + WebDavSyncManager)

- **Merge:** wire strokes now merge into the Room `strokes` table, LWW per stroke
  guid with tombstone-tie semantics (the same `wins()` used everywhere). The PNG
  render + recognition pass reads merged strokes from Room instead of the transient
  in-memory map; contentStamp computation unchanged (same inputs, new source).
- **Export:** `exportSnapshot` adds notebooks/pages/strokes from Room — full state,
  field names exactly as the rM2 emits them today. `SnapshotMerge` extends the
  existing `lww()` pattern to the ink arrays.
- **Deletes:** eraser tombstones strokes (deleted=1, fresh updatedAt) — the rM2
  already merges stroke tombstones. Page/notebook tombstones arriving from the rM2
  keep their existing Android handling (hard-delete + page_texts cleanup).
- **Conflict safety:** per-guid stroke identity means same-page concurrent edits
  union; the rM2's write-implies-existence resurrection covers ink written while a
  delete merges.
- **rM2 side: no code changes.** SyncStore merges notebooks/pages/strokes by guid
  regardless of author. The E2E gate must prove the untested direction
  (Android-authored → rM2 renders + recognizes-via-phone round trip).

## 4. Error handling

- Editor writes are local Room upserts — no network on the drawing path.
- Sync containment unchanged: ink pass failures land in the summary, never abort
  book sync; recognition containment (v0.7.0) unchanged.
- A stroke that fails wire encoding (degenerate: zero points) is dropped at commit,
  never stored.
- Unknown `pageGuid` on inbound strokes: existing missing-parent skip (converges
  next sync).

## 5. Testing & ship gates

- Unit (pure JVM): `InkPoints.encode`↔`decode` round-trip incl. pressure scaling
  and tMs; Room-merge LWW cases (newer wins, tombstone tie, resurrect); eraser
  tombstone semantics; editor viewmodel undo/redo.
- Migration gate: v9→v10 on the AVD over real data.
- E2E vs scratch (`ccx/scratch-android/`): inject stylus strokes on the AVD,
  export, verify snapshot ink arrays; feed a real rM2 snapshot, edit a synced page,
  verify union merge.
- Hardware E2E (owner present): write on the Boox → rM2 renders it; write on the
  rM2 → Boox editor shows it; same-page both-devices union; eraser propagates;
  recognition picks up Boox handwriting.
- Version: **v0.8.0**, versionCode 30. Final whole-branch review before merge.
