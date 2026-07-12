# Phase 3b contracts — sync engine + WebDAV client (frozen)

Builds on 3a (guid/updated_at/deleted on synced tables). Implements the WebDAV transport, the
snapshot serialize/merge/apply, and an off-GUI-thread engine. Endpoint is a plain WebDAV server
(dufs): `https://kosync.cph.gg/ccx/`, Basic auth. Merge = last-writer-wins by `updated_at`.

## Identity & references (CRITICAL — get this exact or devices duplicate/diverge)

- **books merge by `digest`** (content hash — the SAME file on any device is the SAME book, even
  though each device minted its own row guid). NOT by guid. `file_path`/`cover_path` are LOCAL
  (device paths) and are NOT synced — the FILE syncs separately by digest and each device sets its
  own paths. Synced book fields: title, author, format, added_at, last_opened_at, deleted,
  updated_at (LWW), guid (informational).
- **progress merges by its book's `digest`** (one progress per book). Fields: spine_index,
  char_offset, percentage, updated_at, deleted. (synced_at stays local — it's kosync bookkeeping.)
- **bookmarks, highlights, reading_sessions**: merge by `guid`; reference their book by `digest`.
- **collections**: merge by `guid`. **book_collections**: identity = (collection_guid, book_digest);
  fields deleted/updated_at.
- **notebooks**: merge by `guid`. **pages**: merge by `guid`, reference notebook by `guid`.
  **strokes**: merge by `guid`, reference page by `guid`; points as base64 of the packed blob;
  tool, base_width, created_at, deleted, updated_at.

## Snapshot JSON (one file per device at `/ccx/state/<deviceId>.json`)

```json
{ "deviceId": "<hex>", "generatedAt": 0,
  "books":       [{ "digest","guid","title","author","format" (int, 1=epub 0=pdf, per shipped rM2 syncstore),"addedAt","lastOpenedAt","deleted","updatedAt" }],
  "progress":    [{ "bookDigest","spineIndex","charOffset","percentage","deleted","updatedAt" }],
  "bookmarks":   [{ "guid","bookDigest","spineIndex","charOffset","percentage","label","createdAt","deleted","updatedAt" }],
  "highlights":  [{ "guid","bookDigest","spineIndex","startChar","endChar","text","note","colorId","createdAt","deleted","updatedAt" }],
  "collections": [{ "guid","name","createdAt","deleted","updatedAt" }],
  "bookCollections":[{ "collectionGuid","bookDigest","deleted","updatedAt" }],
  "notebooks":   [{ "guid","title","createdAt","deleted","updatedAt" }],
  "pages":       [{ "guid","notebookGuid","seq","deleted","updatedAt" }],
  "strokes":     [{ "guid","pageGuid","tool","baseWidth","points_b64","createdAt","deleted","updatedAt" }],
  "sessions":    [{ "guid","bookDigest","startedAt","endedAt","pagesTurned","startPercentage","endPercentage","deleted","updatedAt" }] }
```
Include ALL rows incl. tombstones (deleted=1). points_b64 omitted/empty for deleted strokes
(their blob is already emptied). A snapshot is the device's FULL synced state (v1 — no deltas).

## SyncStore — `src/sync/syncstore.{h,cpp}` (DB side; host-testable without network)

Owns a `Storage*` (opens its OWN connection on the sync thread — WAL allows this; busy_timeout
covers brief write contention with the GUI). Uses the storage handle for sync-specific SQL.

```cpp
class SyncStore {
public:
    explicit SyncStore(Storage *s);
    QJsonObject exportSnapshot(const QString &deviceId);  // local synced rows -> snapshot JSON
    struct MergeStats { int booksNeeded=0, entitiesApplied=0, tombstonesApplied=0; QStringList missingDigests; };
    // Merge remoteSnapshots (JSON) + local by LWW, apply to the local DB in dependency order
    // (books->collections->notebooks->pages->strokes->progress/bookmarks/highlights/sessions/
    // bookCollections), resolving digest/guid refs to local ids (insert new rows keeping the
    // remote guid; a book we lack locally is inserted with an EMPTY file_path and its digest is
    // returned in missingDigests for the engine to fetch+attach). LWW: apply a remote record only
    // if its updatedAt > the local row's updatedAt (or the row is absent); deleted travels with the
    // winner. Never lowers a local newer value. One transaction (crash-safe).
    MergeStats applyMerged(const QVector<QJsonObject> &remoteSnapshots, const QString &deviceId);
    // After the engine fetches a missing book file + imports it, or for local books, ensure the
    // book row's file_path/cover_path are set. (Import does this; this is for merged rows.)
    void attachBookFile(const QString &digest, const QString &filePath, const QString &coverPath);
    QStringList localBookDigestsWithFile();   // live books that have a local file (for upload)
    QString filePathForDigest(const QString &digest);
    QString extForDigest(const QString &digest); // "pdf"/"epub" from format
};
```
Books/progress upsert by digest; others by guid. Inserting a merged child whose parent is missing
locally: skip it this pass (a later sync converges) — never insert an orphan with a dangling ref.

## WebDavClient — `src/sync/webdav.{h,cpp}` (Qt6::Network; runs on the sync thread, may block)

```cpp
struct WebDavConfig { QString baseUrl, user, pass; };  // baseUrl ends with '/', e.g. https://.../ccx/
class WebDavClient {
public:
    explicit WebDavClient(const WebDavConfig &cfg, QNetworkAccessManager *nam);
    bool mkcol(const QString &relPath, QString *err);          // create dir (idempotent: 201 or 405 ok)
    bool list(const QString &relPath, QStringList *names, QString *err);  // PROPFIND Depth:1 -> child names
    bool get(const QString &relPath, QByteArray *out, QString *err);
    bool put(const QString &relPath, const QByteArray &data, QString *err);
    bool del(const QString &relPath, QString *err);
    bool testConnection(QString *err);                          // PROPFIND base
};
```
Basic auth header; `req.setTransferTimeout(30000)`; TLS default. Blocking via a local QEventLoop is
OK HERE because it runs on the worker thread, never the GUI thread. Parse PROPFIND XML for
`<d:href>` child names (QXmlStreamReader).

## SyncEngine — `src/sync/syncengine.{h,cpp}` (QObject, moved to a worker QThread)

`Q_INVOKABLE void run(WebDavConfig cfg, QString deviceId, QString dataDir)` (invoked on the thread):
1. mkcol `books/`, `state/`.
2. Upload: for each `localBookDigestsWithFile()` not present under `books/` (PROPFIND), PUT the file
   as `books/<digest>.<ext>`.
3. PROPFIND `state/`; GET every `<id>.json` (all devices). Parse to QVector<QJsonObject>.
4. `applyMerged(snapshots, deviceId)` -> MergeStats. For each `missingDigests`: GET
   `books/<digest>.<ext>` (try both exts or read the merged book's format), write it into the local
   library dir atomically, extract cover (PDF via PdfDocument; EPUB cover), `attachBookFile(...)`.
5. `exportSnapshot(deviceId)` -> PUT `state/<deviceId>.tmp` then MOVE to `state/<deviceId>.json`
   (atomic). (If MOVE unsupported, PUT directly.)
6. Stamp `sync_state` last_sync_at.
Emit `progress(QString step)` and `finished(bool ok, QVariantMap summary)` (summary:
booksUp, booksDown, entities, tombstones, error). All network/DB on the thread; the GUI only
receives the signals. The controller re-loads library/notebook state on `finished(ok)`.

## Controller/UI (kept minimal here; full UI is 3c)

Add to ReaderController (or a SyncController): `webdavConfig()/setWebdavConfig(url,user,pass)`
(stored in settings: webdav_url, webdav_user, webdav_pass), `testWebdav()->{ok,message}`,
`syncNow()` (spins up the engine on a QThread; guards against concurrent runs), and signals
`syncStarted()`, `syncProgress(step)`, `syncFinished(ok, summary)`. deviceId reuses the existing
persisted `device_id`. 3c wires Settings + triggers + status; 3b just needs syncNow callable so we
can device-test.

## Tests (host — the MERGE is the crux)

`tests/test_sync.cpp` (Qt6Core + sqlite3; NO network): open two Storage DBs A and B in temp dirs.
- **Convergence**: A creates notebook+pages+strokes + imports a book (fake digest row) + a
  highlight; export A -> apply to B; export B -> apply to A; assert A and B have identical live rows
  (same guids/digests, counts, stroke points, highlight text).
- **LWW**: both edit the SAME highlight's note at different updatedAt; sync both ways; the newer
  note wins on both.
- **Delete propagation**: A soft-deletes a notebook (cascade tombstones); sync to B; B's notebook +
  pages + strokes become deleted=1 and vanish from live reads; a later B->A sync keeps them deleted
  (no resurrection).
- **Book-by-digest**: A and B independently "import" the same digest (different guids); after sync
  neither has a duplicate book (one row per digest), and a highlight A made on it appears on B.
- **Tombstone vs edit**: delete on one side (newer) beats an older edit on the other.
- **Missing parent**: a stroke whose page isn't present yet is skipped, not inserted as an orphan.
WebDavClient + SyncEngine are device/integration-verified (need a real endpoint); no host net test.

## Build

Add to `ccx-reader`: syncstore, webdav, syncengine (Qt6::Network already linked; JSON via
Qt6Core QJson; QThread from Qt6Core). New test_sync.cpp block in scripts/test.sh.
