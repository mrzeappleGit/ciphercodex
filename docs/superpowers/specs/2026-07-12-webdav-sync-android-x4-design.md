# WebDAV sync for the Android app + X4 firmware (design)

Date: 2026-07-12. Approved by owner.

Port the reMarkable 2 OS Phase 3 WebDAV sync to the other two CipherCodex devices so books and
reading state converge across all three through the self-hosted endpoint. The wire contract is
**frozen** in `remarkable2-os/docs/phase3b-contracts.md` and is already live:
`https://kosync.cph.gg/ccx/` (dufs, Basic auth, user `ccx`). This spec does not change the
contract; both new clients implement it.

Owner decisions (asked and answered):
- **X4 scope: full two-way** — snapshot push/pull AND book-file upload+download (epub only).
- **Android triggers**: SYNC NOW + app foreground + reader close (debounced) + fold into the
  existing 6h `SyncWorker`.
- **X4 deletes: ignored locally** — tombstones are respected in its snapshot (no resurrection,
  no re-upload) but SD files are never deleted by sync.
- **X4 sessions: skipped in v1** — X4 stores only capped aggregates; no session journal yet.
- **PDF books: skipped on Android** — no row, no download; they keep syncing rM2↔rM2.

## Shared contract (recap, normative source is phase3b-contracts.md)

- Endpoint layout: `books/<digest>.<ext>` (immutable, union-merged), `state/<deviceId>.json`
  (one full snapshot per device; PUT `.tmp` then MOVE).
- Snapshot JSON arrays: `books` (by `digest` — kosync partialMD5, identical on all three
  platforms), `progress` (by book digest, one per book), `bookmarks`/`highlights`/`sessions`/
  `collections` (by `guid`), `bookCollections` (by collectionGuid+bookDigest), `notebooks`/
  `pages`/`strokes` (rM2-only; other devices skip on parse and never emit).
- Merge: client-side last-writer-wins by `updatedAt`; `deleted=1` wins ties; tombstones travel
  with the winner; missing-parent records are skipped, never inserted as orphans.
- Omission is safe: a device's snapshot only carries the entity types it holds. Merge spans ALL
  snapshots (including the emitting device's own), so Android omitting notebooks or X4 omitting
  highlights loses nothing.
- `file_path`/`cover_path` are device-local, never synced; the file travels by digest.

## Approach

Independent native implementations of the frozen contract: Kotlin/OkHttp/Room on Android,
Arduino C++/esp_http_client on the X4. Rejected: a shared C++ core (Qt/QJson/SQLite vs
Arduino/no-SQLite — sharing means rewriting both) and LAN-push to the X4's built-in WebDAV
server (needs the X4 awake on the same LAN; the VPS hub is the decided architecture).

## Android

### 1. Room v6→v7 (sync foundation, mirrors rM2 Phase 3a)

- Add `guid TEXT` (unique), `updatedAt INTEGER`, `deleted INTEGER NOT NULL DEFAULT 0` to:
  `books`, `bookmarks`, `highlights`, `collections`, `book_collections`, `reading_sessions`.
  `progress` already has `updatedAt`; it gains `deleted` only. `syncedAt` stays local
  (kosync bookkeeping, not synced).
- Migration backfills guid (UUIDv4, dashes stripped) + updatedAt (= best existing time column,
  else migration time) for every row. Must exactly match Room's generated schema (v4→v5 lesson).
- All DAO reads filter `deleted = 0`. Repository deletes become soft-deletes stamping
  `updatedAt = now`, cascading tombstones to descendants (book → its progress, bookmarks,
  highlights, book_collections; collection → its book_collections).
- Physical file cleanup (epub/cover on disk) still happens on delete; only the rows persist as
  tombstones.

### 2. WebDavClient (`sync/WebDavClient.kt`)

OkHttp with custom methods: `PROPFIND` (Depth: 1 → child names via XmlPullParser on `<d:href>`),
`GET`, `PUT`, `MKCOL` (201 or 405 = ok), `MOVE` (Overwrite: T). Basic auth header, 30s
call/read timeouts. Same OkHttp client instance pattern as KosyncClient.

### 3. Merge core (`sync/SnapshotMerge.kt`) — pure Kotlin, no Android deps

`merge(snapshots: List<Snapshot>): MergedState` — LWW per entity by its contract identity,
tombstone-wins-ties. Pure function so the convergence suite runs as plain JVM unit tests.
Kotlinx-serialization or org.json for parse — whichever is already a dependency; field names
exactly as the contract (camelCase, `points_b64` never read).

### 4. Sync engine (`sync/WebDavSyncManager.kt`)

Orchestrates on Dispatchers.IO (single-flight guard against concurrent runs):
1. MKCOL `books/`, `state/`.
2. Upload: local live epub books whose digest is absent under `books/` → PUT `books/<digest>.epub`.
3. PROPFIND `state/` → GET every `*.json` → parse (skip notebooks/pages/strokes arrays; drop
   `format != "epub"` books and their dependents).
4. Apply merged state to Room in dependency order (books → collections → book_collections →
   progress/bookmarks/highlights/sessions), resolving digests/guids to local ids; insert new
   rows keeping the remote guid; never lower a newer local `updatedAt`; one transaction.
   A merged book with no local file → GET `books/<digest>.epub`, run it through the existing
   `importEpub` path (parses title/cover), then attach filePath/coverPath to the merged row —
   import must upsert-by-digest, not insert a duplicate.
5. Export local snapshot (ALL rows incl. tombstones) → PUT `state/<deviceId>.json.tmp` → MOVE.
6. Stamp lastSyncAt in DataStore.
Returns a summary {booksUp, booksDown, entities, tombstones, error} for the UI.

### 5. Config, triggers, UI

- DataStore: `webdavUrl`, `webdavUser`, `webdavPass`, `webdavLastSyncAt`, persisted random
  `deviceId` (hex; reuse the existing one if kosync already mints one).
- Settings: WebDAV section beside kosync — URL/user/password fields, TEST (PROPFIND base),
  SYNC NOW, last-sync label + last result.
- Triggers: SYNC NOW; app foreground (ON_START, debounced ≥5 min since last); leaving the
  reader (debounced); existing 6h `SyncWorker` runs WebDAV sync after kosync when configured.

## X4 firmware (epub-only, full two-way)

Applies the x4-os skills: HAL routing, heap discipline (everything streamed or SD-backed,
bounded arrays), scope discipline (this is owner-requested reading-core sync).

### 1. WebDAV client (`lib/CcxSync/CcxWebDav.{h,cpp}`)

esp_http_client with custom methods, following `HttpDownloader`'s CA-bundle/Basic-auth
patterns: PROPFIND Depth-1 (minimal `<d:href>` scan, no XML lib), GET→SD file, PUT streaming
from SD file (and from a small buffer for snapshots), MKCOL, MOVE.

### 2. Sync state (`/.crosspoint/ccxsync.json`)

Small capped JSON: per-digest {guid, updatedAt, deleted-learned}, per-bookmark-file guid list,
digest cache {path, size, mtime → digest} so partialMD5 (KOReaderDocumentId) isn't recomputed
each sync, `lastSyncAt`. This is the X4's substitute for schema v3.

### 3. Local change detection (no reader hot-path cost)

At sync time a `progress.bin`/bookmark file newer than `lastSyncAt` = locally changed. Because
the clock can be 1970 after an offline reboot, reader-exit and bookmark-save set a dirty flag
(cheap file touch/flag in ccxsync state); the sync run — which has Wi-Fi and therefore NTP
(`NtpTime`) — assigns the real `updatedAt` then. LWW is never fed a 1970 timestamp; if the
clock cannot be set, sync aborts with a message (same rule as stats calendar bucketing).

### 4. Pull + merge

Download each `state/*.json` to an SD temp file; parse from file stream with an ArduinoJson
filter selecting only `books`, `progress`, `bookmarks` (stroke blobs never touch heap; cap
entities per snapshot, log when capped). Merge LWW against local state; apply progress via the
existing `ProgressMapper`/`CipherCodexProgress` mapping (spineIndex+percentage → xpath, the
same approximation kosync uses today; charOffset emitted as 0 from X4). Bookmarks map
computedSpineIndex/percentage/summary ↔ spineIndex/percentage/label.

### 5. Books union

Upload: bounded SD walk (AllBooksActivity pattern — skip hidden/.crosspoint, bound processed
AND queued dirs, skip empty getName()) → epubs whose digest is missing remotely → streamed PUT.
Metadata for the books row from the existing epub metadata/cache path. Download: remote epub
digests not on SD → `/Books/<sanitized title>.epub` (digest suffix on collision). Tombstoned
digests: never emitted live, never uploaded, never re-downloaded; SD file untouched.

### 6. UI

Settings → "CipherCodex Sync" activity: URL/user/password entry (existing text-entry keyboard,
as kosync creds), TEST, SYNC NOW with step progress (like OTA/OPDS screens), last-sync line.
Manual trigger only; Wi-Fi wakes for the transfer and drops after.

## Interop caveats (documented behavior, not bugs)

- X4↔Android/rM2 position and bookmark mapping is approximate (spine + percentage), exactly as
  kosync interop today. kosync stays enabled and authoritative for KOReader interop.
- Android and X4 ignore ink/notebooks; rM2 remains their only carrier.
- Creds stored plaintext in DataStore / ccxsync.json like existing kosync creds; redact from logs.

## Testing / acceptance

- **Android**: JVM unit tests on `SnapshotMerge` mirroring rM2's six scenarios (convergence,
  LWW, delete propagation + no-resurrection, book-by-digest no-dupe, tombstone-beats-older-edit,
  missing-parent skip) + a fixture parse of a real rM2 snapshot (strokes skipped). Emulator
  round-trip against a local dufs.
- **X4**: `pio run` green; on-device round-trip against the live endpoint.
- **Acceptance (three-way, live at kosync.cph.gg/ccx)**: import a book + highlight on Android →
  sync → appears on rM2; move progress on the X4 → sync → position lands on both; delete a book
  on Android → sync all → tombstoned on rM2, X4 stops re-uploading it but keeps its SD file;
  no duplicates anywhere after a second full round.

## Slices

1. **A1 — Android foundation**: v7 migration + soft-deletes + `deleted=0` reads. No visible change.
2. **A2 — Android engine**: WebDavClient + SnapshotMerge + WebDavSyncManager + merge tests green.
3. **A3 — Android UI/triggers**: Settings section, SYNC NOW, foreground/reader-close/worker
   triggers, emulator round-trip. Ship (version bump → auto-release).
4. **X1 — X4 client + state**: CcxWebDav + ccxsync.json + digest cache + dirty flags. Compiles.
5. **X2 — X4 engine**: snapshot pull/merge/apply + books union + snapshot push.
6. **X3 — X4 UI + device QA**: settings activity, SYNC NOW, on-device three-way acceptance.
   Tag firmware release.
