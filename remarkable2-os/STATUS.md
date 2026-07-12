# STATUS

Updated: 2026-07-12 (Phase 1 complete)

## Phase 1 — COMPLETE (notebook vertical slice, verified on hardware)

- Home / Notebooks / page canvas in CipherCodex monochrome identity; pen drives all UI (evdev
  pen synthesizes mouse events off-canvas).
- Tools: pencil, stroke eraser, area (partial) eraser with fast-strike-through cut; undo/redo;
  multi-page; PDF export (vector).
- Crash-safe per-stroke SQLite journal (WAL + synchronous=FULL, one tx/stroke, durability pragmas
  enforced at open). **Passed both on-device gates: kill -9 mid-write and hard power-cut** —
  integrity ok, zero committed strokes lost.
- **Stock-parity pen latency** via the closed plugin's EPScreenModeItem (Pen waveform), reached by
  dlsym with graceful fallback; isolated in `src/epscreenmode.cpp`. Owner-verified "perfect".
- Pressure→width working (saturation-aware remap, owner-verified).
- Host tests green (storage CRUD + points-BLOB exactness + area-erase replace + WAL tripwire;
  kill-atomicity 5/5). Two adversarial review passes; all confirmed findings fixed.

Known deferred (non-blocking, tracked): scripted glass-to-ink latency number; Marker Plus eraser
(no hardware); input-event replay harness; periodic full-page refresh for the reader (Phase 2).

## Prior: Phase 0 — COMPLETE

## Phase 0 — COMPLETE (hardware proofs on physical rM2, OS 3.27.3.0)

- Toolchain: official SDK 3.27.0.97 in Docker (`ccx-rm2-sdk:3.27.0.97`); build/deploy/restore
  scripts working end to end from Windows host.
- Display: stock epaper QPA + qsgepaper (pure Qt Quick). Custom scenegraph geometry is dropped
  by the backend — ink renders via QQuickPaintedItem dirty-rect image updates.
- Marker: raw evdev PenReader (QPA delivers touch only). Transform calib=1 verified; pressure
  0–4095 with visible squared width curve; tilt streams; hover distance available.
- Touch: `inverty` transform verified with on-screen probe. Buttons work.
- Palm rejection baseline: pen-only ink → palm cannot draw.
- Perceived pen latency: parity with stock (owner assessment).
- Suspend/resume under our shell: passes (power-button wake, ink continues).
- Exit path: clean quit → detached launcher restores xochitl (with `reset-failed` guard against
  the 4-starts/10-min limit that otherwise triggers a recovery reboot).
- Full `/home` backup on host; restore-stock script; audit in `docs/hardware-audit.md`.

Deferred within Phase 0 (non-blocking): scripted glass-to-ink latency number, Marker Plus
eraser hardware test (no eraser hardware available), input-event replay harness.

## Next: Phase 2 — reader vertical slice

Library import (USB + Wi-Fi upload), EPUB + PDF reading, progress, TOC, bookmarks,
whole-book search, highlights, handwritten page annotation over documents, and kosync
(reuse the Android app's partial-MD5 identity + API client). Port EPUB/PDF/kosync logic
from the existing repo where portable; MuPDF for PDF (AGPL, record in LICENSES.md).
