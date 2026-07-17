# Boox Raw Ink — rM2-class handwriting latency + pressure on Onyx devices

**Date:** 2026-07-16
**Status:** Approved
**Base:** branch `android-ink-authoring` (v0.8.0, Tasks 1–8 + final review done; Task 9
hardware E2E pending). This feature lands on top as v0.9.0 and rides the same hardware
QA session.

## Problem

On the Boox Go 10.3 Gen 2, handwriting in the ink editor lags visibly behind the pen and
wet strokes render at uniform width. The current wet-ink layer (Jetpack Ink
`InProgressStrokesView`, `StockBrushes.pressurePenLatest`) captures pressure correctly,
but its front-buffer rendering still goes through the Android compositor, which the
Boox e-ink pipeline redraws in a slow refresh mode — nothing like the reMarkable 2's
~20 ms hardware ink.

## Solution

On Onyx devices, replace only the **wet-ink overlay** with the Onyx Pen SDK's raw
drawing path (`TouchHelper`), where the e-ink controller draws the stroke in hardware —
the same mechanism as Boox's built-in notes app: rM2-class latency and hardware
pressure-width in one move. Everything downstream (commit, Room, render, sync wire
format, rM2 side) is unchanged.

## Design

### 1. Device gate

One check: `Build.MANUFACTURER.equals("ONYX", ignoreCase = true)`. Onyx devices get the
raw-drawing wet-ink layer; all other devices keep the current Jetpack Ink path
untouched. Onyx SDK classes are referenced only behind the gate, so the dependency is
inert elsewhere.

### 2. Dependency

`com.onyx.android.sdk:onyxsdk-pen:1.4.11` from
`maven { url "http://repo.boox.com/repository/maven-public/" }` (plain HTTP —
`isAllowInsecureProtocol = true`, version pinned).

### 3. Wet ink on Boox

In `InkEditorScreen`, when `isOnyx && tool == PEN`, the wet-ink `AndroidView` hosts a
plain transparent view driven by `TouchHelper`:

- limit rect = the page box; toolbar excluded
- `STROKE_STYLE_FOUNTAIN` (pressure-sensitive hardware brush)
- stroke width = `9f * viewW / PAGE_W` (same as the current Jetpack Ink brush)
- `openRawDrawing()` + `setRawDrawingEnabled(true)` while PEN is active

### 4. Stroke commit — existing pipeline

`RawInputCallback.onRawDrawingTouchPointListReceived` delivers the finished stroke's
`TouchPointList`. A small **pure function** converts it to `List<InkPoint>`:

- x/y normalized by view size (same mapping as the Jetpack Ink path)
- pressure normalized by `EpdController.getMaxTouchPressure()`, scaled to the existing
  0–4095 wire range, clamped; one calibration constant on the normalization (tuned in
  hardware QA — hardware pressure curves are never the paper ideal)
- `t` = timestamp relative to stroke start

Result feeds the existing `vm.commitStroke(...)` → Room → render/sync path. Wire
format, Room schema, sync protocol, and the rM2 renderer change **zero**.

### 5. Screen consistency

Raw hardware ink is drawn outside the view system, and while raw drawing is enabled the
EPD **defers normal view refreshes** (`closeRawDrawing()` is documented as "unlocking"
screen refresh). So the committed-strokes Canvas stays live: per-commit recompositions
happen invisibly under the raw layer. Whenever raw mode is released — undo/redo (the VM
bumps a `repaintTick`; the overlay pauses raw drawing briefly), tool switch, page
navigation, editor close — the screen catches up in one repaint (a single e-ink refresh
flash, same behavior as the rM2 on undo). The hardware fountain stroke and our
renderer's stroke are not pixel-identical; a just-written line may "settle" slightly on
that repaint. Accepted.

**Contingency (hardware QA):** if this firmware lets view refreshes leak through raw
mode (visible per-stroke flicker while writing), fall back to freezing the committed
layer — filter the current raw session's stroke guids out of the Canvas until the next
repaint event. VM-only change.

### 6. Erase

When `tool == ERASE`, raw drawing is disabled and the existing Compose erase gesture
works unchanged. SDK erase callbacks are not used.

### 7. Lifecycle

`TouchHelper` is created per page (inside the existing `key(pageGuid)` block),
`closeRawDrawing()` on view dispose. Raw drawing pauses whenever the screen is not
resumed (raw mode locks the screen region) and while any overlay UI is shown.

### 8. Testing

- Unit tests for the `TouchPointList → InkPoint` conversion: coordinate mapping,
  pressure normalization + clamping, timestamp offsets, empty/single-point strokes.
- Everything latency/feel-related is on-device QA: rides the pending Task 9 hardware
  checklist for `android-ink-authoring` (two-way loop vs rM2), extended with: wet-ink
  latency feel vs rM2, pressure-width variation while writing, repaint settle on
  undo/tool-switch/page-nav, erase still works, non-Onyx fallback smoke test (any
  regular Android device/emulator — Jetpack Ink path unchanged).

## Risks

1. **HTTP maven repo** — `repo.boox.com` is plain HTTP; pinned version +
   `isAllowInsecureProtocol` scoped to that one repo.
2. **Pressure curve mismatch** between the hardware fountain brush and our renderer —
   the calibration constant in §4 is the knob; tuned on hardware.
3. **SDK vs Go 10.3 Gen 2** — raw drawing behavior on this exact device is unverifiable
   off-device; hardware QA is the real gate, as with every task in this project.

## Out of scope

- SDK erase callbacks / hardware eraser.
- Any change to stroke storage, sync format, recognition, or the rM2 side.
- Refresh-mode tuning for the rest of the app (reader already handled separately).
