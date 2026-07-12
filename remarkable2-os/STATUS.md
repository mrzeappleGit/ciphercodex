# STATUS

Updated: 2026-07-11 (late)

## Done — Phase 0 nearly complete

- Device audited, updated to OS 3.27.3.0, SSH key auth, full `/home` backup (2.03 GB, verified).
- Toolchain: official SDK 3.27.0.97 in Docker image `ccx-rm2-sdk:3.27.0.97`; `scripts/build.sh`
  cross-compiles; `scripts/deploy.sh` deploys + launches detached (xochitl always returns).
- Hello screen v3 proven on hardware:
  - Display via stock epaper QPA + qsgepaper backend (pure Qt Quick).
  - Marker via raw evdev `PenReader` (QPA doesn't deliver stylus); calib=1 transform verified.
  - Ink via `InkItem` (QQuickPaintedItem + dirty rects) — custom scenegraph geometry is NOT
    rendered by qsgepaper (CLOSED lib; we only run on top of it).
  - Perceived pen latency: parity with stock (owner test).
  - Pressure→width works (squared curve); tilt values stream; palm makes no marks.
  - Touch calibrated: `inverty` (docs' `rotate=180:invertx` is wrong for this device).
  - CLEAR/EXIT buttons work; EXIT returns to stock UI via detached run script.
- Hardware audit: `docs/hardware-audit.md` — measured pen/touch ranges, battery sysfs, licenses.

## Open Phase 0 items

- Suspend/resume test (power button behavior under our shell; wake without xochitl).
- Scripted glass-to-ink latency measurement (currently perceived-parity only).
- Marker Plus eraser untested (owner's Marker lacks eraser end); code path implemented.
- Input-event replay harness (recording exists via input-probe).

## Next executable step

Phase 1 notebook vertical slice: SQLite stroke journal (per-stroke WAL transactions),
notebook/page model, reopen-after-restart, then the forced-power-loss durability test
(kill -9 mid-writing + hard power cycle; all completed strokes must survive).
