# STATUS / RESUME — CipherCodex OS for reMarkable 2

Updated 2026-07-12. This file is the single place to resume. Read it top to bottom, run the
"verify still alive" block, then pick up at "NEXT".

## What this is

A focused reading-and-writing OS shell for the reMarkable 2 (`remarkable2-os/`), replacing
xochitl's UI while keeping the stock OS/drivers/recovery. C++/Qt 6.8 Quick, cross-compiled with the
official SDK in Docker, deployed over USB to a physical device. Full brief:
`../remarkable2-os-claude-fable-5.md`. Design + per-phase specs in `docs/`.

(Note: the same git repo also has a PARALLEL track — WebDAV sync for the Android app + X4 firmware,
commits like "Room v7" / "Spec: WebDAV sync for Android app". That is NOT this project; ignore it
here.)

## Operational quick-reference

- **Device**: reMarkable 2, OS 3.27.3.0, over USB at `root@10.11.99.1` (SSH key auth installed).
  Host key rotates on OS update → `ssh-keygen -R 10.11.99.1` if it complains.
- **Build** (from `remarkable2-os/`): `bash scripts/build.sh` — official SDK 3.27.0.97 in Docker
  image `ccx-rm2-sdk:3.27.0.97`, cross-compiles to `build-rm2/ciphercodex-shell`.
  Git Bash needs `MSYS_NO_PATHCONV=1` for raw docker runs (build.sh handles it).
- **Host tests**: `bash scripts/test.sh` — 9 suites (storage, powerloss, storage_v2, kosync,
  library, highlights, sync, epub, sync-foundation). Must stay green.
- **Deploy**: `bash scripts/deploy.sh` OR manually:
  `ssh root@10.11.99.1 "kill \$(pidof ciphercodex-shell) 2>/dev/null"; scp build-rm2/ciphercodex-shell root@10.11.99.1:/home/root/ciphercodex/; ssh root@10.11.99.1 "nohup setsid /home/root/ciphercodex/run-on-device.sh >/dev/null 2>&1 &"`
  The launcher stops xochitl, runs the shell, restarts xochitl on exit (with `reset-failed` guard).
- **Restore stock UI**: `bash scripts/restore-stock.sh`.
- **Backups**: full `/home` tar + a pre-v3-migration DB snapshot in `../device-backups/`.
  On-device DB backup: `/home/root/ciphercodex/data-v2-backup.db`.
- **WebDAV sync server**: dufs on the Hostinger VPS (`ssh hostinger`, systemd `dufs-ccx`,
  serves `/opt/ciphercodex-sync`). Endpoint `https://kosync.cph.gg/ccx/`, user `ccx`, password in
  the VPS unit file `/etc/systemd/system/dufs-ccx.service` (and the recall memory). nginx `/ccx/`
  location added to `/etc/nginx/sites-enabled/kosync.cph.gg` (backup `.bak.ccx`). The VPS also runs
  the user's kosync server at `https://kosync.cph.gg/`.

## Verify still alive (run these when resuming)

1. `ssh root@10.11.99.1 "pidof ciphercodex-shell && grep RELEASE /usr/share/remarkable/update.conf"`
2. `cd remarkable2-os && bash scripts/test.sh` → "All host tests passed."
3. `bash scripts/build.sh` → "Built: build-rm2/ciphercodex-shell".
4. WebDAV up: `curl -s -o /dev/null -w '%{http_code}' -u ccx:<pw> -X PROPFIND -H 'Depth:1' https://kosync.cph.gg/ccx/` → 207.

## DONE (all verified on the physical device unless noted)

- **Phase 0** — hardware proven: display via stock LGPL `epaper` QPA; pen read from `/dev/input/event1`
  (evdev, calib=1); touch `inverty`; battery sysfs; suspend/resume; return-to-stock. Audit in
  `docs/hardware-audit.md`. The device has NO kernel e-paper driver and NO virtual keyboard by
  default (we built one — see below).
- **Phase 1** — notebooks + ink: home/notebooks/page, pencil + stroke-eraser + area-eraser,
  undo/redo, per-stroke SQLite WAL journal (autosave). **Stock-parity pen latency** via the closed
  plugin's `EPScreenModeItem` (Pen waveform, reached by dlsym, isolated in `src/epscreenmode.cpp`).
  **Passed the forced-power-loss gate on hardware** (kill -9 + hard power cut, zero completed
  strokes lost). PDF export.
- **Phase 2a** — library + PDF reader (PDFium, `/usr/lib/libpdfium.so`): import-from-inbox with
  partial-MD5 dedup, list/search/filter/sort, covers, detail, delete; PDF render/nav/zoom/pan/TOC/
  bookmarks/search/resume. kosync core ported + host-tested vs KOReader vectors.
- **Phase 2b** — EPUB reader: byte-identical port of the Android `XhtmlMapper`/`buildChapterText`
  (char offsets match for cross-device sync; golden host tests), QTextDocument reflow, pagination,
  TOC, search, bookmarks, footnote links, typography; kosync wired into open/close (async).
- **Phase 2c** — highlights + Kept: long-press text selection → highlight (survives reflow), notes,
  mono render, cross-library Kept view + Android-format Markdown export.
- **Phase 3a** — sync foundation: schema **v3** adds guid + updated_at + soft-delete tombstone to
  every synced table; deletes are soft (cascade tombstones); reads filter `deleted=0`. Crash-safe
  migration + guid backfill **verified on the real device v2 DB** (433 strokes etc. migrated intact).
- **Phase 3b** — sync engine: `SyncStore` (snapshot + LWW merge, books-by-digest, others-by-guid),
  `WebDavClient` (Qt Network), `SyncEngine` (off GUI thread). Convergence/LWW/delete/dedup host
  tests pass.
- **Phase 3c (partial)** — WebDAV Settings UI + **live round-trip verified end-to-end**: device
  pushed 4 books + notebook + 434 strokes to the VPS; a blank second device pulled it ALL back
  (files + ink + progress) — full multi-device convergence on real hardware + real server.
- **On-screen keyboard** (`src/qml/Keyboard.qml`) — device has no hardware keys; pure-QML board
  driving the focused TextInput/TextEdit. Unblocks self-serve credential entry.
- Two adversarial-review passes on the sync/keyboard code; all confirmed findings fixed
  (per-table merge txns, PDFium mutex, password-clobber, WebDAV truncation, etc.).

## KNOWN GAPS / DEFERRED (nothing blocking; pick from these)

- **Automatic sync triggers**: sync is currently manual (Settings → SYNC NOW). The decided design
  is manual + on app-open + on leaving a book/notebook (no background polling). NOT yet wired.
- **Handwritten annotation OVER documents** (draw on PDF/EPUB pages): Phase 2 item, deferred. The
  InkItem + document-relative anchors exist; needs an ink layer over PdfView/EpubView + anchor model.
- **PDF text highlighting**: EPUB highlights done; PDF needs a page+rect anchor model (follow-on).
- **Marker Plus eraser**: BTN_TOOL_RUBBER path implemented, untested (owner's Marker has no eraser).
- **Scripted glass-to-ink latency number**: currently owner-assessed parity only.
- **Phase 3 remaining**: full backup/restore archive; USB/web transfer UI; sleep screens; rest of
  Settings (Wi-Fi, storage, battery, handedness).
- **Phase 4**: reproducible image/update artifact, signed checksums, rollback/watchdog, tested
  recovery, final handoff docs.
- **Cross-device sync acceptance test #7** (rM2 ↔ Android app ↔ KOReader EPUB position): needs the
  Android app + a KOReader client pointed at the kosync server; not yet run.

## NEXT (recommended executable step)

**Wire automatic sync triggers** — small, high-value, makes the sync already built feel seamless:
1. `ReaderController::syncNow()` already exists + guards concurrent runs. Add a call on app
   foreground/open (Main.qml Component.onCompleted or a Window active signal) and on leaving a
   reader/notebook (Component.onDestruction of the reader/page screens), debounced, only when
   WebDAV is configured. Respect "no background polling" — these are event-driven, not timed.
2. Guard: don't START a sync while a notebook/reader page is open (the merge writes the DB the open
   page reads); the review's per-table-txn fix + `reloadOpenPage()` make an in-flight race safe, but
   gating open-during-sync (or sync-during-open) is cleaner. Consider a simple "sync only from Home"
   rule.
3. Test on device: draw on device A, sync; pull on the host-simulated device B (see the round-trip
   recipe in the git history around commit c9280c8 / the deviceB seed dance in this session).

Alternative next tracks if preferred: handwritten annotation over documents (rounds out reading), or
the backup/packaging track toward a shippable image (Phase 4).

## Pointers

- Design/specs: `docs/phase3-sync-design.md`, `docs/phase2*-contracts.md`, `docs/hardware-audit.md`.
- Recall memory (survives sessions): `remarkable2-os-facts`, `remarkable2-os-sync-plan`,
  `remarkable2-os-webdav-endpoint` (all in the Claude memory index).
- The ONE closed-ABI dependency: `EPScreenModeItem` via dlsym in `src/epscreenmode.cpp` (fast pen
  waveform). Degrades gracefully if a future OS drops the symbols.
