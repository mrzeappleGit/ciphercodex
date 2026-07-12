# Phase 3 — WebDAV books + notes sync (design)

Goal: books, reading notes (progress, bookmarks, highlights), notebooks, and ink annotations sync
across the user's devices through a WebDAV endpoint the user self-hosts on their VPS. kosync stays
for cross-APP reading-position interop (KOReader/Android); this is the CipherCodex-native full sync.
Decided: lightweight WebDAV server; manual + on-open/close trigger (no background polling); sync
EVERYTHING (books + all notes + notebooks + ink). Last-writer-wins per entity by timestamp.

## The identity problem (why a schema migration is needed)

Current rows use per-device autoincrement integer ids — not unique across devices. Sync needs a
stable global identity per row and a last-modified time and a way to propagate deletes. Schema v3
adds to every SYNCED table:

- `guid TEXT` UNIQUE — a UUIDv4 (dashes stripped), generated on insert, the cross-device identity.
- `updated_at INTEGER` — ms of last local change (LWW key). (books/progress/notebooks already have
  a time column; add/rename to a consistent `updated_at` where missing.)
- `deleted INTEGER NOT NULL DEFAULT 0` — soft-delete tombstone. Deletes become
  `SET deleted=1, updated_at=now` (recursively for children); every read filters `deleted=0`.

Synced tables: `books` (metadata only; the FILE syncs separately by digest), `progress`,
`bookmarks`, `highlights`, `collections`, `book_collections`, `notebooks`, `pages`, `strokes`,
`reading_sessions`. NOT synced: `settings` (device-local: device_id, WebDAV/kosync creds — a device
must keep its own), `schema_version`, and a new `sync_state` table.

Foreign keys travel as the PARENT's guid in the snapshot (e.g. a stroke carries its page's guid, a
page its notebook's guid, a highlight its book's guid) and are resolved to local integer ids on
import. Migration v3 backfills a guid + updated_at for every existing row in one crash-safe
transaction (existing Phase 1/2 data keeps working and becomes syncable).

Soft-delete conversion: `deleteNotebook/deletePage/deleteBook/removeStrokes/deleteHighlight/…`
become soft-deletes that also soft-delete descendants (so a deleted notebook's pages+strokes carry
tombstones and can't resurrect from another device). Stroke soft-delete NULLs its points blob to
reclaim space, keeping just the tombstone. All existing queries add `WHERE deleted=0`.

## Sync model — per-device snapshot + client-side LWW merge

WebDAV is a file store, not a sync API, so each device OWNS one snapshot file (no server-side write
conflicts on a file). Layout on the endpoint (all under a configurable base path, default
`/ciphercodex/`):

```
/ciphercodex/
  books/<digest>.<ext>          # book files, immutable, keyed by content digest (union merge)
  state/<deviceId>.json         # this device's snapshot of all synced rows (guid-keyed graph)
  state/<deviceId>.json.tmp     # atomic write: PUT tmp then MOVE over the real name
```

**Pull+merge:** PROPFIND `state/` → download every `<deviceId>.json` (including our own last one).
Build a merged view keyed by guid: for each entity, keep the record with the greatest `updated_at`;
a record with `deleted=1` wins ties/newer. Apply the merged view to the local DB by guid: update
the matching local row, or insert a new local row (new local int id) and record its guid; resolve
parent guids → local ids (topological order: books→collections→book_collections;
notebooks→pages→strokes; books→progress/bookmarks/highlights/sessions). Apply tombstones
(soft-delete locally). **Book files:** PROPFIND `books/`; GET any `<digest>` the local library
lacks (then import it), PUT any local book file the remote lacks.

**Push:** after merging, serialize the local synced rows (now the merged truth) to
`state/<deviceId>.json` and PUT it atomically. Record `last_sync_at` + a per-table high-water
`updated_at` in `sync_state` (lets the next push send only changed rows — v2 optimization; v1 may
send the full snapshot each time for simplicity, capped by size).

**Strokes** are the bulk. v1: include them in the snapshot with the packed points blob base64'd,
grouped per page. If a full snapshot grows too large (measure a dense 100-page notebook), split
strokes into `state/<deviceId>/strokes/<page-guid>.bin` sidecar files pulled on demand — a v2
refinement noted here, not built unless measurement forces it.

**Conflict = last-writer-wins by `updated_at`.** Safe for a single user reading one device at a
time. Genuine concurrent edits to the SAME entity on two offline devices lose the older side's
change to that entity (documented limitation; CRDT is out of scope). Different entities never
conflict; new items and deletes always merge.

## WebDAV client — `src/sync/webdav.{h,cpp}` (Qt6::Network)

HTTP with Basic auth over TLS: `PROPFIND` (Depth:1 list), `GET`, `PUT`, `MKCOL` (create dirs),
`MOVE` (atomic rename), `DELETE`. Bounded transfer timeout (like KosyncClient). All calls are
user/trigger-initiated (open/close/Sync-now) — never a background poll. Creds in `settings`
(webdav_url, webdav_user, webdav_pass — pass stored as-is; it's an app password the user controls;
document that it's stored plaintext in the local DB like other app creds, redacted from logs).

## Sync engine — `src/sync/syncengine.{h,cpp}`

Orchestrates: ensure remote dirs (MKCOL) → book-file union → pull snapshots → merge (LWW, guid→id,
tombstones) → write local → push own snapshot → stamp sync_state. Returns a summary
{booksPulled, booksPushed, entitiesMerged, deletions, error}. Runs on the GUI thread but the
WebDAV I/O is the only blocking part — run the whole engine on a worker QThread and report progress
via signals so the UI never freezes (mirrors the "no GUI block" rule we enforced for kosync).
Heavy: pull/merge/serialize of many rows — do it off the GUI thread.

## Controller + UI

`SyncController` (or extend ReaderController): `webdavConfig()/setWebdavConfig(url,user,pass)`,
`testWebdav()→{ok,message}` (PROPFIND base), `syncNow()` (kick the engine on its thread),
`syncStatus` property (idle/running/last-result/last-time). Triggers: `syncNow()` on app
foreground/open and on leaving a reader/notebook (debounced), plus a SYNC NOW button. SettingsScreen
gains a WebDAV section (URL / user / app-password / TEST / SYNC NOW + last-sync status). No
background timer (scheduled sync was declined).

## Slices

- **3a — foundation**: schema v3 (guids/updated_at/deleted on synced tables), crash-safe migration
  + backfill, soft-delete conversion (+ descendant tombstones), `WHERE deleted=0` on reads,
  `sync_state` table. Host tests: migration/backfill, soft-delete + descendant tombstones, guid
  uniqueness, existing suites still green. NO user-visible change.
- **3b — engine + WebDAV**: webdav client, sync engine (snapshot serialize/merge/LWW/guid-map/
  tombstones + book union), worker thread. Host tests: the MERGE logic is where bugs hide — simulate
  two devices (two DBs + a fake local "WebDAV" dir) doing interleaved edits/deletes and assert
  convergence; book union; tombstone propagation; parent-guid resolution.
- **3c — UI + triggers + device test**: SettingsScreen WebDAV section, syncNow, open/close triggers,
  status. Live test against the user's VPS WebDAV: push from the reMarkable, verify files on the
  endpoint, pull into a second (host-simulated) client, confirm convergence; then delete/edit round-trips.

## Acceptance

Configure the VPS WebDAV in Settings, SYNC NOW → books + notes + notebooks + ink upload. On a second
client pointed at the same endpoint, SYNC NOW → the library, highlights, and notebooks appear and
match. Edit a note / draw a stroke / delete a notebook on one, sync both → the change (incl. the
delete) propagates by last-writer-wins. No GUI freeze during sync; Wi-Fi only wakes for the transfer.
Existing on-device Phase 1/2 data migrates to v3 and stays intact.
