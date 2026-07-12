# CipherCodex OS for reMarkable 2 — Design

Date: 2026-07-11. Status: approved by owner.
Authoritative requirements: `remarkable2-os-claude-fable-5.md` (repo root). This doc records the
architecture decisions that brief left open, plus the hardware facts they rest on.

## Hardware facts (observed 2026-07-11, device over USB at 10.11.99.1)

- reMarkable 2, OS 3.16.2.3 at audit time (being updated to latest to match an official toolchain),
  kernel 5.4.70-v1.5.1-rm11x, i.MX7 Dual armv7l, 994 900 kB RAM.
- Storage: rootfs 256 MB (95 % full — never install there); `/home` ext4 6.6 GB, ~4.3 GB free.
- Input: `event0` power key; `event1` Wacom I2C digitizer (ABS bitmask `f000003` = X, Y, pressure,
  distance, tilt X/Y); `event2` `pt_mt` capacitive multitouch.
- Display: no kernel e-paper driver (rM2 drives the panel from userspace). Stock OS ships an
  **LGPL-2.1 `epaper` Qt platform plugin** at `/usr/lib/plugins/platforms/libepaper.so`
  (license + `main.cpp` under `/usr/share/common-licenses/epaper-qpa/`). Device Qt is 6.5.2.
- Stock services: `xochitl.service` (UI), `swupdate`, `wpa_supplicant`, `systemd-networkd`.
- Official per-OS-version SDKs (x86_64 Linux, Qt 6.5 + sysroot) at
  `storage.googleapis.com/remarkable-codex-toolchain/<os-version>/rm2/`; none exists for 3.16.x,
  hence the device update. Documented run recipe: `systemctl stop xochitl`, then
  `QT_QUICK_BACKEND=epaper ./app -platform epaper` with
  `QT_QPA_EVDEV_TOUCHSCREEN_PARAMETERS="rotate=180:invertx"`. Pure Qt Quick only (no Widgets).
- Full `/home` backup taken before any write: `device-backups/rm2-home-2026-07-11.tar` (2.03 GB, verified).

## Decisions

1. **Architecture** — one C++/Qt 6.5 Qt Quick app, `ciphercodex-shell`, installed entirely under
   `/home/root/ciphercodex/`. A systemd unit stops `xochitl.service` while our shell runs.
   Watchdog: `OnFailure` + launch counter; three consecutive failures re-enable xochitl.
   `restore-stock.sh` and full uninstall script ship from day one. Footprint outside `/home` is
   exactly one systemd unit file.
2. **Display** — the stock `epaper` QPA plugin. No rm2fb, no framebuffer hacks. Phase 0 measures
   pen-to-ink latency through this path against stock before anything is built on it.
3. **Build** — the official reMarkable SDK inside a Linux Docker container on the Windows host;
   CMake; deploy over USB SSH (key auth already installed). Host-side unit tests build natively in
   the container.
4. **Ink pipeline** — C++ evdev reader on the Wacom device feeds a custom scene-graph item for the
   live stroke; stroke committed to the document model at pen-up. Palm rejection gates touch while
   pen is in proximity (distance axis). Each completed stroke is one SQLite WAL transaction — that
   is the crash-safe journal the brief's power-loss test validates.
5. **Data** — single SQLite DB `/home/root/ciphercodex/data.db` with foreign keys, WAL, and schema
   migrations from v1: books, progress, sessions, bookmarks, highlights, collections, folders,
   tags, notebooks, pages, layers, strokes, ink anchors. Source documents under `library/`,
   rebuildable thumbnails under `cache/`. Stroke schema documented in `docs/` (the required open
   format); exports to SVG/PNG/PDF.
6. **Rendering** — EPUB: port this repo's parsing/behavior where portable, laid out via
   QTextDocument into a Quick item. PDF: statically linked MuPDF (AGPL, recorded in LICENSES.md).
   kosync: port the Android app's partial-MD5 identity and API client.
7. **Phases** — the brief's Phases 0–4 verbatim, one vertical slice per commit,
   `remarkable2-os/STATUS.md` kept current, `docs/hardware-audit.md` written before app features.

## Out of scope (v1)

Handwriting OCR, cloud accounts, replacing bootloader/kernel/drivers, rM1 or Paper Pro support.
