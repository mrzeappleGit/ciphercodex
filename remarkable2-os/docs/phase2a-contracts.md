# Phase 2a contracts — Library + PDF reader (frozen)

Scope: the first reader sub-slice. Import books, browse the library, read PDFs (PDFium), plus a
host-tested kosync core. EPUB reading, highlights/selection, handwritten annotation over docs, and
the Wi-Fi/USB web upload UI are LATER slices — but the DB schema is laid down in full now so no
further migration is needed for them. Reuse the Android app's algorithms verbatim where noted.

## Dependencies discovered (device + SDK, verified 2026-07-12)

- **PDFium**: `/usr/lib/libpdfium.so` on device AND in SDK sysroot (450 exported FPDF_* funcs:
  load, render-to-bitmap, text extract, bookmarks, page sizes, matrix render). Link it; **vendor
  the BSD public headers** we use (`fpdfview.h`, `fpdf_doc.h`, `fpdf_text.h`) under
  `src/pdf/pdfium/` — no MuPDF build. Record PDFium (BSD-3) in LICENSES.md.
- Fonts on device: Noto Serif/Sans/Mono, EB Garamond (for later EPUB typography).
- libfreetype/harfbuzz/jpeg/png16/openjp2/xml2 present (later EPUB use).

## Storage v2 migration (additive; extend `src/storage/storage.cpp` migrate())

After the `v>=1` guard add `if (v < 2)` running `kSchemaV2` inside one `Tx` (same pattern as v1),
then it falls through so a fresh DB runs v1+v2 in sequence. `foreign_keys=ON` already set at open.
Tables (snake_case to match existing rM2 tables):

```sql
CREATE TABLE books(id INTEGER PRIMARY KEY, title TEXT NOT NULL, author TEXT,
  file_path TEXT NOT NULL, digest TEXT NOT NULL, cover_path TEXT, size_bytes INTEGER NOT NULL,
  format INTEGER NOT NULL DEFAULT 0,   -- 0 pdf, 1 epub
  added_at INTEGER NOT NULL, last_opened_at INTEGER);
CREATE UNIQUE INDEX books_digest ON books(digest);
CREATE TABLE progress(book_id INTEGER PRIMARY KEY REFERENCES books(id) ON DELETE CASCADE,
  spine_index INTEGER NOT NULL, char_offset INTEGER NOT NULL, percentage REAL NOT NULL,
  updated_at INTEGER NOT NULL, synced_at INTEGER);
CREATE TABLE reading_sessions(id INTEGER PRIMARY KEY, book_id INTEGER NOT NULL
  REFERENCES books(id) ON DELETE CASCADE, started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL,
  pages_turned INTEGER NOT NULL, start_percentage REAL NOT NULL, end_percentage REAL NOT NULL);
CREATE INDEX reading_sessions_book ON reading_sessions(book_id);
CREATE TABLE bookmarks(id INTEGER PRIMARY KEY, book_id INTEGER NOT NULL
  REFERENCES books(id) ON DELETE CASCADE, spine_index INTEGER NOT NULL, char_offset INTEGER NOT NULL,
  percentage REAL NOT NULL, label TEXT NOT NULL, created_at INTEGER NOT NULL);
CREATE INDEX bookmarks_book ON bookmarks(book_id);
CREATE TABLE highlights(id INTEGER PRIMARY KEY, book_id INTEGER NOT NULL
  REFERENCES books(id) ON DELETE CASCADE, spine_index INTEGER NOT NULL, start_char INTEGER NOT NULL,
  end_char INTEGER NOT NULL, text TEXT NOT NULL, note TEXT, color_id INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL);
CREATE INDEX highlights_book_spine ON highlights(book_id, spine_index);
CREATE TABLE collections(id INTEGER PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL);
CREATE TABLE book_collections(collection_id INTEGER NOT NULL
  REFERENCES collections(id) ON DELETE CASCADE, book_id INTEGER NOT NULL
  REFERENCES books(id) ON DELETE CASCADE, PRIMARY KEY(collection_id, book_id));
CREATE INDEX book_collections_book ON book_collections(book_id);
CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);
-- (schema_version bumped to 2)
```

Position model: for **PDF**, `spine_index = page index (0-based)`, `char_offset = 0`,
`percentage = (page+1)/pageCount`. (EPUB will use the Android spine/charOffset scheme later; the
columns are shared.) Deleting a book relies on FK CASCADE — one `DELETE FROM books` plus unlinking
the on-disk `file_path` and `cover_path` files.

## Digest util — `src/library/digest.{h,cpp}` (MUST match KOReader byte-for-byte)

```cpp
// partial-MD5 (KOReader frontend/util.lua). Ports Android Digests.kt exactly.
QString partialMd5(const QString &filePath); // lowercase 32-hex, or "" on open failure
```
Algorithm: `md=MD5(); for i in -1..10: offset = (i==-1)?0:(qint64(1024) << (2*i));
if offset>=fileLength break; seek(offset); fill up to 1024 bytes looping over short reads;
if 0 read break; md.update(actual bytes)`; return lowercase hex. **Do NOT** write
`1024<<(2*i)` for i=-1 (negative shift = UB); special-case offset 0.
Host test vectors (create files, must match): a 1 MB+ file's hash is deterministic; the canonical
KOReader vectors to reproduce if those exact sample files are present:
`tall.pdf=41cce710f34e5ec21315e19c99821415`, `leaves.epub=59d481d168cca6267322f150c5f6a2a3`.
Since we can't ship those files, the host test instead asserts: (a) a <1024-byte file hashes equal
to a plain full MD5 of its bytes; (b) a known constructed buffer at offsets 0 and 1024 produces a
fixed precomputed hash (compute it in the test with an independent MD5 over the sampled ranges).

## Library store — `src/library/library.{h,cpp}` (owns a Storage*, reuses Android logic)

```cpp
struct BookRow { qint64 id; QString title, author, filePath, digest, coverPath;
                 qint64 sizeBytes, addedAt, lastOpenedAt; int format; }; // lastOpenedAt=0 => never
enum ImportResult { Imported, Duplicate, Failed };
class Library {
public:
    explicit Library(Storage *s, const QString &dataDir);
    // Import every *.pdf/*.epub in <dataDir>/inbox/: partial-MD5 dedupe, atomic copy-in to
    // books/<digest>.<ext>, metadata+cover (PDF via PdfDocument: title meta + page0 thumbnail
    // to covers/<digest>.png; EPUB this slice: filename-derived title, no cover), insert row.
    // Returns counts. Serialized; every failure path removes the temp. Never modifies the source.
    struct ImportSummary { int imported, duplicates, failed; };
    ImportSummary importInbox();
    QVector<BookRow> books();                       // ORDER BY last_opened_at IS NULL, last_opened_at DESC, added_at DESC
    void markOpened(qint64 id);                     // last_opened_at = now
    void deleteBook(qint64 id);                     // FK cascade + unlink file_path & cover_path
    // progress
    struct Progress { int spineIndex, charOffset; double percentage; qint64 updatedAt, syncedAt; bool exists; };
    Progress progress(qint64 bookId);
    void saveProgress(qint64 bookId, int spineIndex, int charOffset, double percentage); // upsert, preserves syncedAt, updated_at=now
    // bookmarks
    QVector<...> bookmarks(qint64 bookId); qint64 addBookmark(...); void deleteBookmark(qint64 id);
};
```
Filter/sort/search happen in the controller/QML over `books()` (small lists), reusing the Android
rules: FINISHED_THRESHOLD 0.98; UNREAD=no progress row, READING=pct<0.98, FINISHED=pct>=0.98;
sorts recent/title(ci)/author(ci nulls-last)/added/progress(null=-1); query = ci substring on
title|author. Import inbox layout: `<dataDir>/inbox/` (drop files here; user scp/USB-copies them),
temps `<dataDir>/books/import-*.tmp`, finals `<dataDir>/books/<digest>.pdf|.epub`, covers
`<dataDir>/covers/<digest>.png`.

## PDF document — `src/pdf/pdfdocument.{h,cpp}` (PDFium wrapper, one FPDF_InitLibrary process-wide)

```cpp
struct PdfOutline { QString title; int pageIndex; int level; };
class PdfDocument {
public:
    static PdfDocument *open(const QString &path, QString *err); // FPDF_LoadDocument
    ~PdfDocument();                                              // FPDF_CloseDocument
    int pageCount() const;
    QSizeF pageSizePt(int page) const;                          // FPDF_GetPageSizeByIndexF
    QImage renderPage(int page, const QSize &target) const;     // fit into target, Grayscale8, white bg
    QImage renderThumbnail(int page, int maxDim) const;         // for covers
    QVector<PdfOutline> outline() const;                        // FPDFBookmark_* tree flattened
    // whole-doc search: returns page indices (and per-page match count) containing `q` (ci).
    QVector<QPair<int,int>> search(const QString &q) const;     // FPDFText_* per page
};
```
Render Grayscale8 on white; use FPDF_RenderPageBitmap with FPDF_ANNOT|FPDF_LCD_TEXT off,
FPDF_GRAYSCALE flag. Thread-note: FPDFium is not thread-safe — do all calls on the GUI thread this
slice (page render of a 226-DPI page is fast enough; measure). Cache the last N rendered pages
(small LRU, e.g. 3) keyed by (page,targetSize).

## PdfView — `src/pdf/pdfview.{h,cpp}` QQuickPaintedItem, QML_ELEMENT

Renders the current page image; supports fit-width / fit-page / zoom (pinch or +/- buttons) / pan
when zoomed. Page turn: tap-zones (left third = prev, right third = next) and horizontal swipe;
both hands usable. Exposes: `pageIndex`, `pageCount`, `fitMode`, `zoom`, invokables
`next()/prev()/goToPage(n)/setFit(mode)`, signal `pageChanged`. On page turn request a clean full
refresh (no Pen-mode fast waveform here — reading wants quality; this item does NOT attach an
EPScreenModeItem). Avoid animated turns; update the whole page region once per turn.

## Kosync core — `src/sync/kosync.{h,cpp}` (host-testable; wired to UI later slice)

Port the Android protocol exactly (see docs — reproduced here):
- `userKey = md5hex(utf8(password))` lowercase, stored; password never stored/sent.
- HTTP (Qt QNetworkAccessManager): header `accept: application/vnd.koreader.v1+json` on every
  request; `x-auth-user`/`x-auth-key` (lowercase md5) except register.
  - POST `{base}/users/create` no auth, body `{username,password:userKey}`.
  - GET `{base}/users/auth`.
  - GET `{base}/syncs/progress/{digest}` -> `{document,progress,percentage,device,device_id,timestamp}`;
    "no record" = 200 with empty object (detect by absent `percentage`).
  - PUT `{base}/syncs/progress` body `{document,progress,percentage,device,device_id}`;
    `percentage = floor(pct*10000)/10000` (truncate, never round up).
- ProgressCodec: encode `ciphercodex:s=<spine>;o=<offset>`; decode `^ciphercodex:s=(\d+);o=(\d+)$`;
  foreign fallback `^/body/DocFragment\[(\d+)\]` (1-based -> -1), position by percentage.
- device_id: per-install UUIDv4 with dashes stripped (32 hex), persisted in settings; sent
  UPPERCASE, compared case-insensitively. device_name is user-editable, not identity.
- Dirty = `synced_at IS NULL OR synced_at < updated_at`; markSynced =
  `UPDATE progress SET synced_at=? WHERE book_id=? AND updated_at=?` (conditional, race-safe).
- pull-on-open: own-echo (record.device_id==ours) -> UpToDate; remoteNewer if local==null OR
  record.timestamp*1000 > local.updated_at+2000 OR (record.pct>local.pct AND diff>0.0005).

Host tests (`tests/test_kosync.cpp`, no network): partialMd5 vectors; ProgressCodec encode/decode
+ foreign fallback; percentage truncation; dirty-tracking transitions; userKey md5. The live
server round-trip + acceptance test #7 (cross-device EPUB) run when the EPUB slice lands.

## QML screens (`src/qml/`) — CipherCodex monochrome identity, pen+touch usable

- HomeScreen LIBRARY tile now navigates (drop the "PHASE 2" tag) -> LibraryScreen.
- `LibraryScreen.qml`: black header + five-bar mark; IMPORT button (runs importInbox, shows
  summary); search field; filter chips (ALL/UNREAD/READING/FINISHED with full-library counts);
  sort menu; grid/list of covers (cover image or title-block fallback) with title/author/progress;
  tap -> BookDetail; long-press -> delete confirm.
- `BookDetailScreen.qml`: cover, title/author, size, progress ring + %, RESUME (opens at saved
  page), READ FROM START, DELETE (confirm). EPUB books: RESUME/READ shows "EPUB reader — next
  update" placeholder this slice.
- `PdfReaderScreen.qml`: full-bleed PdfView; slim toolbar BACK / title / page x/of N / TOC /
  BOOKMARK / SEARCH / FIT / ZOOM +- ; progress scrubber; TOC drawer (outline -> goToPage);
  bookmark list; search field -> result pages -> jump. Saves progress (page) on every turn and on
  leave. >=90px touch targets; buttons work with pen (canvasRect not set -> pen = synth mouse).

## Build (CMakeLists.txt)

- `ccx-storage` gains nothing (schema is inside it). New static lib `ccx-reader`:
  digest, library, pdfdocument, pdfview, kosync. Link `ccx-reader` into the shell.
- PDFium: `find_library(PDFIUM_LIB pdfium REQUIRED)` (sysroot has libpdfium.so); vendored headers
  under `src/pdf/pdfium/`; `target_link_libraries(... ${PDFIUM_LIB})`. Qt6::Network for kosync.
- Host tests build with native g++ (scripts/test.sh) — digest/kosync/progress-codec need no PDFium
  or Qt GUI (use Qt6::Core only). PDF/PdfView are device-only (need PDFium + GUI), covered by an
  on-device smoke check, not host tests.

## Acceptance for this slice (on device)

Import a PDF (scp into inbox), see it in the library with cover; open it, turn pages (tap + swipe),
zoom/pan, jump via TOC, search text and jump to a hit, set + return to a bookmark; close and
reopen -> resumes at the same page; delete it -> file + cover gone, row gone. Host tests green
(digest vectors, kosync codec/dirty). Durability: killing mid-read loses nothing (progress is a
committed upsert). Library shows imported EPUBs (filename title) with a reader placeholder.
