# STATUS

Updated: 2026-07-12

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

## Next: Phase 1 — notebook vertical slice

Home screen, notebook list, one notebook/page, pencil + eraser tools, undo/redo,
per-stroke SQLite WAL journal (autosave), reopen, suspend/resume, PDF export.
Gate: forced-power-loss durability test — all completed strokes survive kill -9 +
hard power cycle.
