# STATUS / RESUME — CipherCodex OS for reMarkable 2

Updated 2026-07-12 (evening session: auto-sync triggers). This file is the single place to
resume. Read it top to bottom, run the "verify still alive" block, then pick up at "NEXT".

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
- **Sleep** (2026-07-13) — power button (KEY_POWER on event0; logind ships HandlePowerKey=ignore
  so the key is ours) → design-11 sleep face (SleepScreen.qml) → 1.4s EPD flush → `systemctl
  suspend`; e-ink holds the face at zero power. Wake = power press / tap / wall-clock-jump probe,
  and wake-at-Home queues an auto-sync with a retry window (a sync frozen mid-transfer times out
  up to 30s after resume — monotonic timers don't tick while asleep). Review-hardened: the face
  CONSUMES input (MouseArea, StackView hidden beneath — also removes the Pen-waveform region so
  the face flushes clean grays), and dismissals within 5s of issuing suspend are ignored (the
  freeze lands seconds after systemctl returns). Live-verified twice on hardware via injected
  KEY_POWER (suspend confirmed by SSH drop; shell + wake sync survived resume). Idle
  auto-sleep too (2026-07-13): IdleWatch app event filter (touch/keys/synth mouse) + per-stroke
  pen pokes (raw ink never becomes Qt events) → same sleep flow; default 10 min via settings
  key `sleep_idle_min` (0 disables, no UI row yet), dev override env `CCX_IDLE_MIN` —
  live-verified with a 1-minute override (device self-suspended untouched).
- **CipherCodex OS design implemented** (claude.ai/design project "CipherCodex OS.dc.html",
  pulled via the claude_design MCP): all 10 screens restyled to the 1-bit design system —
  embedded OFL fonts (Rajdhani display / Share Tech Mono captions / Courier Prime reading,
  `assets/fonts/`, loaded in main.cpp), `Theme.qml` singleton tokens, `DotGrid`/`Hatch`
  pattern components, 140px header bands, in-row confirm pattern, offset-outline elevation,
  left tool rail on the notebook page (InkItem now full-screen under it — exact 3:4 sheet, PDF
  export loses nothing; ink at x<130 on pre-rail pages hides under the rail but stays in DB +
  export), Home sync-status chip + live tile counts, EPUB "Typewriter" (Courier Prime) default
  reading font, keyboard restyle + number row. Two adversarial review rounds (15 confirmed
  findings fixed, incl. a pre-existing EPUB TOC always-jumps-to-chapter-1 bug, overlapping
  delete-confirm hit zones, and device-font tofu glyphs — the device covers ← → « » ▼ but NOT
  ↩ ↶ ↷ ▾ ⌫). Deployed to hardware, log clean. NOT yet eyeballed on device by the owner:
  waveform behavior of the rail inside the Pen(0) region + overall look.
- **Automatic sync triggers** — event-driven, never polled: syncNow() 1.5s after app open and
  on every return to Home (StackView depth 1); debounced Timer in `Main.qml`, only STARTS from
  Home, silent when unconfigured. **App-open trigger live-verified on hardware** (nginx showed
  the full MKCOL/PROPFIND/PUT pass 4s after launch, no interaction). Home-return trigger is the
  same code path but needs a hands-on confirm (walk into a notebook and back, watch the server).
  Hardening that came out of its two review rounds:
  - **CRITICAL regression fix**: the earlier "WebDAV truncation" fix made `finish()` report every
    4xx/5xx as a transport error (Qt maps them to reply errors) — mkcol's 405-means-exists never
    fired, so every sync after the very first died at step 1, silently. `webdav.cpp finish()` now
    treats only genuine interruptions (timeout/abort/host-closed) as transport failures. Failed
    syncs also `qWarning` into shell.log now.
  - Live-ink protection: reloadOpenPage() defers while `InkItem::penActive()` (applied at pen-up
    via a QUEUED pen.penUp connection — ordering-safe across page re-opens); a mid-stroke merge
    completion can no longer destroy the stroke being drawn.
  - `syncedDataChanged` (and cache invalidate) now gated on entities+tombstones+booksDown > 0,
    not on run-ok: no-change runs stop wiping undo history; merges that landed before a failed
    snapshot PUT still refresh views. applyMerged returns real stats even if its final segment
    commit fails (earlier segments are durable).
  - Write-implies-existence: `Storage::appendStroke` resurrects a tombstoned page+notebook
    (fresh updated_at wins LWW) — ink written while a peer's delete merges is never lost
    invisibly. Host-tested: `write-resurrects-tombstone` in test_sync.
  - Library/NotebookList/Kept reload on syncedDataChanged; SettingsScreen seeds its `syncing`
    state from `webdavConfig().syncing` so an in-flight auto-sync shows SYNCING... on open.

## KNOWN GAPS / DEFERRED (nothing blocking; pick from these)

- **Auto-sync deferred nits** (all self-healing / cosmetic, from the review rounds): list screens
  snap to top when a merge reloads them mid-scroll (save/restore contentY if it annoys);
  PageScreen's page list and open Epub/Pdf readers don't refresh on a mid-session merge (heal on
  re-entry); every Home return runs a full engine pass (~2MB snapshot PUT even idle — add a
  max(updated_at) dirty-skip in SyncEngine if bandwidth ever matters); Home-return trigger not yet
  hand-confirmed on device (same code path as the verified app-open one).
- **Handwritten annotation OVER documents** (draw on PDF/EPUB pages): Phase 2 item, deferred. The
  InkItem + document-relative anchors exist; needs an ink layer over PdfView/EpubView + anchor model.
- **PDF text highlighting**: EPUB highlights done; PDF needs a page+rect anchor model (follow-on).
- **Marker Plus eraser**: BTN_TOOL_RUBBER path implemented, untested (owner's Marker has no eraser).
- **Scripted glass-to-ink latency number**: currently owner-assessed parity only.
- **Phase 3 remaining**: full backup/restore archive; USB/web transfer UI; rest of Settings
  (Wi-Fi, storage, battery, handedness; a `sleep_idle_min` row for the now-live idle sleep).
- **Phase 4**: reproducible image/update artifact, signed checksums, rollback/watchdog, tested
  recovery, final handoff docs.
- **Cross-device sync acceptance test #7** (rM2 ↔ Android app ↔ KOReader EPUB position): needs the
  Android app + a KOReader client pointed at the kosync server; not yet run.

## NEXT (recommended executable step)

**Handwritten annotation OVER documents** (draw on PDF/EPUB pages) — rounds out the reading
experience; the InkItem + document-relative anchors exist:
1. Overlay an InkItem on PdfView/EpubView; anchor strokes document-relative (PDF: page + page-space
   rect; EPUB: spine + char-offset range like highlights survive reflow).
2. New synced table (schema v4) for document ink, following the strokes table pattern (guid,
   updated_at, deleted) so it rides the existing WebDAV merge unchanged.
3. Host-test the anchor model; verify on device over a PDF page and an EPUB chapter reflow.

Alternative next tracks: PDF text highlighting (page+rect anchor model), or the backup/packaging
track toward a shippable image (Phase 4). Quick manual check worth doing first: the Home-return
auto-sync trigger (draw in a notebook, back out to Home, watch the server snapshot rewrite).

## Pointers

- Design/specs: `docs/phase3-sync-design.md`, `docs/phase2*-contracts.md`, `docs/hardware-audit.md`.
- Recall memory (survives sessions): `remarkable2-os-facts`, `remarkable2-os-sync-plan`,
  `remarkable2-os-webdav-endpoint` (all in the Claude memory index).
- The ONE closed-ABI dependency: `EPScreenModeItem` via dlsym in `src/epscreenmode.cpp` (fast pen
  waveform). Degrades gracefully if a future OS drops the symbols.
