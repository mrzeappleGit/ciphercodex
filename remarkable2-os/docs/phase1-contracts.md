# Phase 1 contracts (frozen before implementation)

Scope: notebook vertical slice — home, notebook list, page canvas with pencil + stroke-eraser,
undo/redo, per-stroke crash-safe autosave, reopen, PDF export. Templates/layers/lasso are later
phases. These interfaces are the contract between the storage, ink, and UI modules.

## Storage (`src/storage/storage.h`, links SDK `libsqlite3.a` statically)

DB at `/home/root/ciphercodex/data.db` (override via `CCX_DATA_DIR` for tests).
`PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA foreign_keys=ON.`
synchronous=FULL is required: NORMAL can lose the last commits on hard power-cut, which
fails the Phase 1 gate. Every mutating call below is one committed transaction.

```cpp
struct StrokePoint { float x, y; quint16 pressure; qint16 tiltX, tiltY; quint32 tMs; };
struct StrokeData  { qint64 id = -1; qint64 pageId = -1; int tool = 0;  // 0 pencil, 1 eraser-…later
                     float baseWidth = 2.0f; QVector<StrokePoint> pts; };
struct PageInfo    { qint64 id; int seq; };
struct NotebookInfo{ qint64 id; QString title; int pageCount; qint64 updatedAt; };

class Storage {
public:
    static Storage *open(const QString &dbDir, QString *error); // creates dir, db, runs migrations
    ~Storage();
    QVector<NotebookInfo> notebooks();
    qint64 createNotebook(const QString &title);
    void    renameNotebook(qint64 id, const QString &title);
    void    deleteNotebook(qint64 id);            // cascades pages+strokes
    QVector<PageInfo> pages(qint64 notebookId);
    qint64  createPage(qint64 notebookId);        // appends at end, returns id
    void    deletePage(qint64 pageId);
    qint64  appendStroke(const StrokeData &s);    // returns rowid; THE journal write
    void    removeStrokes(const QVector<qint64> &ids);
    void    restoreStrokes(QVector<StrokeData> &s); // re-insert with original ids
    QVector<StrokeData> strokes(qint64 pageId);   // ordered by id
};
```

Schema v1 (`schema_version` table from day one; migrations run in `open`):

```sql
CREATE TABLE schema_version(version INTEGER NOT NULL);
CREATE TABLE notebooks(id INTEGER PRIMARY KEY, title TEXT NOT NULL,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE pages(id INTEGER PRIMARY KEY, notebook_id INTEGER NOT NULL
  REFERENCES notebooks(id) ON DELETE CASCADE, seq INTEGER NOT NULL);
CREATE TABLE strokes(id INTEGER PRIMARY KEY, page_id INTEGER NOT NULL
  REFERENCES pages(id) ON DELETE CASCADE, tool INTEGER NOT NULL DEFAULT 0,
  base_width REAL NOT NULL, points BLOB NOT NULL, created_at INTEGER NOT NULL);
CREATE INDEX strokes_page ON strokes(page_id);
```

`points` BLOB: little-endian packed records, 18 bytes/point:
`f32 x, f32 y, u16 pressure(0-4095), i16 tilt_x, i16 tilt_y, u32 t_ms` (t relative to stroke
start). Coordinates are page-normalized 0..1 (portrait page 1404×1872 aspect). This is the
documented open format → also copy this section into `docs/stroke-format.md`.

## Ink (`src/inkitem.h` — extend existing)

InkItem stays a QQuickPaintedItem raster cache but becomes vector-backed:

```cpp
// additions:
Q_PROPERTY(int tool ...)             // 0 pencil, 1 stroke-eraser
void setStrokes(const QVector<StrokeData> &strokes); // load + full re-render
signals:
  void strokeFinished(const StrokeData &s);          // pen-up, page-normalized pts
  void strokesErased(const QVector<qint64> &ids);    // stroke-eraser hits (on pen-up)
```

Pencil renders with the existing squared pressure curve. Stroke eraser: while active, pen
motion hit-tests stroke polylines (distance < 6 px) and marks them; hits render 50 % gray
immediately, deletion is emitted on pen-up. InkItem keeps id→stroke map to re-render after
erase/undo. Re-render = clear buffer + draw all live strokes.

## Controller (`src/notebookcontroller.h`, QML_ELEMENT)

Owns Storage, mediates InkItem↔DB, holds undo/redo stacks (in-memory, per page,
cap 100 entries; cleared on page switch).

```cpp
class NotebookController : public QObject {  // QML singleton-ish, created in Main.qml
    Q_PROPERTY(bool canUndo/canRedo ...)
    Q_INVOKABLE QVariantList notebooks();               // [{id,title,pageCount}]
    Q_INVOKABLE qint64 createNotebook(QString title);
    Q_INVOKABLE void   deleteNotebook(qint64 id);
    Q_INVOKABLE QVariantList pages(qint64 notebookId);
    Q_INVOKABLE qint64 createPage(qint64 notebookId);
    Q_INVOKABLE void openPage(qint64 pageId, InkItem *canvas); // loads strokes into canvas
    Q_INVOKABLE void undo(); Q_INVOKABLE void redo();
    Q_INVOKABLE bool exportNotebookPdf(qint64 notebookId, QString outPath); // QPdfWriter, vector
};
```

Undo model: two ops only — AddStroke{id} (undo = remove row) and EraseStrokes{full StrokeData
copies} (undo = restoreStrokes). DB always reflects the visible state; a crash at any moment
preserves exactly what's drawn (minus in-flight stroke).

## Pen routing (`src/penreader.*` — extend existing)

PenReader gains `Q_PROPERTY(QRectF canvasRect ...)` (scene coords, set from QML). Pen samples
inside canvasRect → existing penDown/Move/Up signals (ink). A pen-down *outside* canvasRect
synthesizes a left-button QMouseEvent press/release pair via
`QWindowSystemInterface::handleMouseEvent` at the pen position, so the Marker can press QML
buttons. (Verified constraint: epaper QPA never delivers stylus input itself.)

## QML (`src/Main.qml` + `src/qml/`)

- `Main.qml`: StackView — HomeScreen → NotebookListScreen → PageScreen.
- HomeScreen: black header band + five-bar mark (keep exactly the hello-screen header);
  five tiles: LIBRARY / NOTEBOOKS / KEPT / STATS / SETTINGS — only NOTEBOOKS navigates
  (others show a small "PHASE 2/3" tag). Monochrome, square geometry, full-inversion pressed
  state (black bg/white text when pressed).
- NotebookListScreen: list (title, page count), NEW NOTEBOOK button, tap to open first page,
  long-press → delete confirm. EXIT-to-stock button stays here (dev affordance).
- PageScreen: InkItem full-bleed; slim top toolbar: BACK, notebook title, page x/y,
  PREV/NEXT/+PAGE, PENCIL, ERASER, UNDO, REDO, EXPORT PDF. 90 px touch targets minimum.
- All buttons must react to both touch and pen (pen works via the synthesized-mouse routing).

## Tests (`tests/`, run on host inside the SDK container via `scripts/test.sh`)

Plain asserts, no framework. `tests/test_storage.cpp` (host build, links host sqlite3):
schema create/migrate idempotence, notebook/page/stroke CRUD roundtrip, points BLOB
encode/decode exactness, restoreStrokes preserves ids, cascade deletes.
`tests/test_powerloss.sh`: child process appends strokes in a loop, parent SIGKILLs it at a
random moment, then reopens the DB — integrity_check passes and stroke count equals the
number of completed appendStroke calls reported by the child.

## Build

CMake: `add_library(ccx-storage STATIC ...)` linked into shell; target links `sqlite3`
(SDK static lib). Host test build: `scripts/test.sh` compiles storage + tests with the
container's native g++ against the x86_64 sysroot's sqlite3.
