# NOTES — read-only rM2 notebook viewer (Android)

**Date:** 2026-07-12 · **Status:** owner-approved · **Scope:** CipherCodex Android app only

A fifth main tab, NOTES, that displays the reMarkable 2's handwritten notebooks —
rendered from the ink data already present in the WebDAV sync snapshots. Strictly
read-only: Android never creates, edits, or deletes ink, and never emits ink on the wire.

## Owner decisions

- Notes = **viewing rM2 ink notebooks** (not typed notes).
- Approach: **render pages to PNG at sync time**; Room stores metadata only, never strokes.
- Placement: **new NOTES main tab** (LIBRARY · KEPT · NOTES · STATS · APPS).

## Data source (no wire changes)

Sync already GETs every `state/<deviceId>.json`. The rM2's snapshot carries, per the
frozen phase3b contract:

- `notebooks`: `{ guid, title, createdAt, deleted, updatedAt }`
- `pages`: `{ guid, notebookGuid, seq, deleted, updatedAt }`
- `strokes`: `{ guid, pageGuid, tool, baseWidth, points_b64, createdAt, deleted, updatedAt }`

`points_b64` = base64 of 18-byte little-endian records `{ float x, y; uint16 pressure;
int16 tiltX, tiltY; uint32 tMs }` (`remarkable2-os/src/storage/storage.cpp` PackedPoint,
`docs/stroke-format.md`).

**Isolation rule:** the existing `Snapshot`/`SnapshotJson` classes are NOT modified. A new
`InkSnapshot` (`@Serializable`, `ignoreUnknownKeys`) with only `notebooks`/`pages`/`strokes`
re-decodes the same downloaded snapshot text. Android's exported snapshot therefore remains
structurally incapable of emitting ink keys.

## Storage — Room v7→v8

Two metadata-only tables (no `deleted` columns — see tombstone rule below):

- `notebooks(id PK, guid UNIQUE, title, createdAt, updatedAt)`
- `notebook_pages(id PK, guid UNIQUE, notebookGuid, seq, updatedAt, contentStamp, imagePath)`

`contentStamp` = `maxOf(stroke.updatedAt) * 31 + liveStrokeCount` over the page's live
strokes (0 for a strokeless page) — cheap change detector for re-rendering. Migration adds the tables only; existing tables
untouched. Migration SQL must exactly match Room's generated schema (v4→v5 lesson).

**Tombstone rule:** because Android never emits ink, it keeps no ink tombstones. A remote
`deleted=1` notebook/page (LWW-winning) hard-deletes the local row and its PNG(s). No
resurrection is possible: the rM2's own snapshot is the sole ink source and it carries the
tombstones.

## Sync integration — the ink pass

After the existing `applyMerged` + file downloads, `WebDavSyncManager` runs an ink pass:

1. Decode `InkSnapshot` from each already-downloaded snapshot text.
2. LWW-merge notebooks and pages across snapshots by guid (reuse `SnapshotMerge.wins`);
   strokes group by `pageGuid` (live strokes only).
3. Apply: upsert notebook/page metadata; hard-delete rows+PNGs for LWW-winning tombstones.
4. For each live page whose computed `contentStamp` differs from the stored one: render a
   PNG to `filesDir/notebooks/<pageGuid>.png` (atomic: temp file then rename), update the row.
5. Failures are contained: an exception in the ink pass is caught, surfaced in the
   `WebDavSummary.error`, and never rolls back or blocks book/annotation sync (which has
   already committed).

## Rendering

- Canvas: 1404×1872 (rM2 panel), white background, black ink — matches the app's E-INK design.
- Strokes drawn as connected segments; per-segment width from pressure using the same
  mapping as the rM2's `inkStrokeWidth(pressure)` (`src/inkitem.h/.cpp`) scaled by the
  stroke's `baseWidth`. Tool differences beyond width are ignored in v1 (all render as ink).
- Structure: a pure function builds per-segment geometry (`points → List<Segment(width)>`)
  so it is JVM-testable; a thin Android layer draws segments to a Bitmap and writes the PNG.
- Malformed `points_b64` (length not divisible by 18, or undecodable base64): skip that
  stroke, log, continue — never fail the page or the sync.
- Zero-stroke page renders blank white (valid state).

## UI

- `MainScaffold` gains a NOTES tab (LIBRARY · KEPT · NOTES · STATS · APPS).
- **Notebook grid:** first-page thumbnail, title, page count; sorted by `updatedAt` desc.
  Empty state: caption in app style — notes sync from the reMarkable 2.
- **Page viewer:** full-screen `HorizontalPager` over the notebook's pages (by `seq`),
  pinch-zoom + pan on each image, `PAGE n/N` indicator. No edit affordances.
- Existing Cipher component vocabulary throughout (CipherPanel/CipherCaption etc.).

## Testing

- JVM unit tests: point-blob decoder (18-byte records, LE, malformed-input skip),
  ink LWW merge + tombstone hard-delete decisions, contentStamp change detection,
  segment-geometry builder (pressure→width).
- Sync-level behavior (render-on-change, PNG lifecycle) verified in the E2E pass against
  a real rM2 snapshot (the live endpoint already has one, with 444 strokes).

## Out of scope (v1)

- Creating/editing ink on Android; exporting pages; dark-mode ink re-styling;
  per-tool rendering nuances (v1 renders every synced stroke as pressure-width ink);
  PDF/document annotations (a separate rM2 feature, not yet in its snapshots).
