# Handwriting Recognition — rM2 notes to text via ML Kit on Android

**Date:** 2026-07-12 · **Status:** owner-approved (route 1 of the options discussed) · **Scope:** recognize rM2 notebook ink into per-page text on the Android app at sync time; sync the text back so the reMarkable shows and searches it.

## Owner decisions

- **Recognition runs on the phone**, not the tablet and not the VPS: ML Kit Digital Ink
  Recognition (`com.google.mlkit:digital-ink-recognition:19.0.0`), offline after a one-time
  ~20MB `en-US` model download. On-device rM2 recognition was ruled out (no viable open
  engine for a 1.2GHz Cortex-A7; MyScript is commercial).
- **Stroke-based, not OCR**: the sync snapshots already carry point sequences
  (x, y normalized 0..1 on the 1404×1872 page, pressure, tMs relative to stroke start) —
  exactly ML Kit's input. No bitmaps are recognized.
- **Derived data, never authoritative**: recognized text is stored *beside* the ink, never
  replaces it, is recomputed whenever a page's ink changes, and its failure can never break
  book or ink sync (same containment as the NOTES render pass).
- **Opt-in**: a Settings toggle (default OFF) gates the ~20MB model download and the
  recognition pass. Language fixed to `en-US` for v1.

## Data flow

```
rM2 writes ink ──sync──▶ state/<rm2-id>.json (strokes)
                              │ Android sync ink pass (existing InkSync hook)
                              ▼
                 line-segment strokes → ML Kit per line → page text
                              │ stored in Room `page_texts` (keyed by pageGuid)
                              ▼
        Android snapshot gains a NEW top-level `pageTexts` array (Android-authored)
                              │ rM2 merges it (schema v4 `page_text` table)
                              ▼
        rM2: TEXT panel on the notebook page + notebook search over titles+text
```

- **Isolation rule upheld**: Android still never emits `notebooks`/`pages`/`strokes`. It
  emits only `pageTexts`, a new array it authors. Old clients ignore unknown keys
  (`ignoreUnknownKeys` both sides; rM2's `mergeTable` only reads named keys).
- **Wire contract** (new array in the phase3b snapshot, additive):
  `pageTexts: [{ pageGuid, text, sourceStamp, deleted, updatedAt }]` — identity `pageGuid`
  (one text per page, the `progress` pattern), LWW by `updatedAt`, tie → tombstone wins.
  `sourceStamp` is the ink `contentStamp` the text was computed from, so a second Android
  device doesn't recompute what it merges.
- **Staleness**: page ink changes → `contentStamp` changes → next Android sync recomputes →
  newer `updatedAt` wins everywhere. `sourceStamp != contentStamp` is the only trigger.
- **Deletes**: Android hard-deletes `page_texts` rows when their page hard-deletes (NOTES
  convention). rM2 tombstones `page_text` in the `deleteNotebook` cascade (its convention).
  Orphaned live text rows for merged-in page tombstones are invisible everywhere (reads
  join live pages) and get skipped by missing-parent handling on apply — accepted.

## Recognition quality plan

- ML Kit's recognizer assumes **one line of ink** per call. A pure-Kotlin segmenter groups
  strokes into lines by y-interval overlap (tolerance from the median stroke height),
  preserving writing order within a line; lines recognized top-to-bottom, joined with `\n`,
  each call given `WritingArea(pageW, lineH)` and the last 20 chars as `preContext`.
- Points feed ML Kit as `Ink.Point.create(x·1404, y·1872, createdAt + tMs)` — the decoder
  gains the currently-skipped `tMs` field (timestamps materially improve accuracy).
- Candidate 0 is taken; empty ink clears the text row.

## Surfaces (v1)

- **Android NOTES tab**: text panel in the page viewer; a search field on the notebook grid
  filtering by title or recognized text.
- **rM2**: a TEXT button on the notebook page's tool rail toggling a read-only text overlay;
  a search row on the notebook list matching titles + page text (reuses the on-screen
  keyboard). Both already react to `syncedDataChanged`.

## Sequencing constraint

Built ON TOP of branch `notes-rm2-viewer` AFTER its Task 5 ships (v7→v8 AVD migration gate,
live-ink E2E, v0.6.0 merge): Room v9 must chain after v8, and the recognition pass edits
the same files that track's final review may still touch.

## Out of scope (v1)

- Languages beyond en-US; per-notebook language.
- Editing recognized text; using it for export/kept.
- Recognizing annotation-over-document ink (future rM2 schema v5 tables — design leaves room:
  recognition keys off any page-like guid + stroke set).
- rM2-side stats/indexing beyond SQL LIKE search.
