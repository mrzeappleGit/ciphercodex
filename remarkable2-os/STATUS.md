# STATUS

Updated: 2026-07-11

## Done

- Device audited over USB SSH (see `docs/hardware-audit.md`); SSH key auth installed.
- Full `/home` backup taken and verified (`device-backups/rm2-home-2026-07-11.tar`, 2.03 GB).
- Design approved and committed (`docs/superpowers/specs/2026-07-11-remarkable2-os-design.md`).
- Architecture: Qt 6.5 Qt Quick shell on the stock LGPL `epaper` QPA plugin. Confirmed present
  on device.
- Phase 0 scaffold: hello-screen app (`src/main.cpp`, `src/Main.qml`), evdev audit tool
  (`src/tools/input_probe.c`), Docker SDK build script, deploy + restore-stock scripts.

## In progress

- Device OS update to latest (was 3.16.2.3 — no matching SDK exists for it). Watcher armed.

## Blockers

- SDK download URL pends the post-update OS version (`scripts/sdk-version.env` empty until then).

## Next executable step

1. When device reports new version: re-audit, fill `sdk-version.env` from
   https://developer.remarkable.com/links, run `scripts/build.sh`, then `scripts/deploy.sh`.
2. On-device: run `input-probe /dev/input/event1 10` while using the Marker (pressure/tilt/eraser)
   and record ranges in the audit doc.
3. Verify hello screen: display refresh, touch draw, Marker draw (does the QPA deliver stylus
   pressure to Qt?), EXIT returns to xochitl.
