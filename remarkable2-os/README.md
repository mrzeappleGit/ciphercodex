# CipherCodex OS — reMarkable 2

A focused reading-and-writing system shell for the reMarkable 2, replacing xochitl's UI while
keeping the stock OS, drivers, and recovery path intact. See `../remarkable2-os-claude-fable-5.md`
for the full brief and `../docs/superpowers/specs/2026-07-11-remarkable2-os-design.md` for the
approved design.

## Layout

- `src/` — Qt Quick shell (C++/QML) and small C tools
- `tests/` — host-runnable unit tests
- `scripts/` — build (Docker + official SDK), deploy, restore-stock, backup
- `packaging/` — systemd unit, install/uninstall
- `docs/` — hardware audit, stroke format, operations

## Build

Requires Docker. `scripts/build.sh` fetches the official reMarkable SDK (version pinned in
`scripts/sdk-version.env`) into a container and cross-compiles for armv7.

## Deploy / restore

- `scripts/deploy.sh` — scp to `/home/root/ciphercodex/` on the USB-connected device, stop
  xochitl, launch the shell.
- `scripts/restore-stock.sh` — stop the shell, restart xochitl. Never flashes anything.

Everything installs under `/home/root/ciphercodex/` plus one systemd unit; full uninstall in
`packaging/uninstall.sh`.

## Status

See `STATUS.md`.
