#include "storage.h"

#include <sqlite3.h>

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QUuid>

#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>

namespace {

// On-disk points format is little-endian (docs/stroke-format.md); both targets
// (i.MX7 armv7 device, x86_64 test host) are LE, so we pack via a mirror struct.
static_assert(Q_BYTE_ORDER == Q_LITTLE_ENDIAN, "points BLOB codec assumes little-endian host");

#pragma pack(push, 1)
struct PackedPoint {
    float x, y;
    quint16 pressure;
    qint16 tiltX, tiltY;
    quint32 tMs;
};
#pragma pack(pop)
static_assert(sizeof(PackedPoint) == 18, "18 bytes per point on disk");

QByteArray encodePoints(const QVector<StrokePoint> &pts)
{
    QByteArray blob(int(pts.size() * sizeof(PackedPoint)), Qt::Uninitialized);
    char *out = blob.data();
    for (const StrokePoint &p : pts) {
        const PackedPoint pp{p.x, p.y, p.pressure, p.tiltX, p.tiltY, p.tMs};
        std::memcpy(out, &pp, sizeof pp);
        out += sizeof pp;
    }
    return blob;
}

QVector<StrokePoint> decodePoints(const void *data, int bytes)
{
    QVector<StrokePoint> pts;
    const int n = bytes / int(sizeof(PackedPoint));
    pts.reserve(n);
    const char *in = static_cast<const char *>(data);
    for (int i = 0; i < n; ++i) {
        PackedPoint pp;  // records inside the blob are unaligned — copy out
        std::memcpy(&pp, in + i * sizeof pp, sizeof pp);
        pts.append(StrokePoint{pp.x, pp.y, pp.pressure, pp.tiltX, pp.tiltY, pp.tMs});
    }
    return pts;
}

bool exec(sqlite3 *db, const char *sql)
{
    char *err = nullptr;
    if (sqlite3_exec(db, sql, nullptr, nullptr, &err) != SQLITE_OK) {
        qWarning("storage: %s -- %s", err ? err : "error", sql);
        sqlite3_free(err);
        return false;
    }
    return true;
}

struct Stmt {
    sqlite3_stmt *s = nullptr;
    Stmt(sqlite3 *db, const char *sql)
    {
        if (sqlite3_prepare_v2(db, sql, -1, &s, nullptr) != SQLITE_OK)
            qWarning("storage: prepare failed: %s -- %s", sqlite3_errmsg(db), sql);
    }
    ~Stmt() { sqlite3_finalize(s); }
    bool row() { return s && sqlite3_step(s) == SQLITE_ROW; }
    bool done() { return s && sqlite3_step(s) == SQLITE_DONE; }
};

struct Tx {
    sqlite3 *db;
    bool active;
    explicit Tx(sqlite3 *d) : db(d), active(exec(d, "BEGIN IMMEDIATE")) {}
    bool commit()
    {
        if (!active)
            return false;
        active = !exec(db, "COMMIT");
        return !active;
    }
    ~Tx()
    {
        if (active)
            exec(db, "ROLLBACK");
    }
};

qint64 now() { return QDateTime::currentMSecsSinceEpoch(); }

// Global sync identity: UUIDv4, dashes stripped (32 hex chars). Matches the id format the
// reader already uses for kosync (readercontroller.cpp) so both stay comparable.
QByteArray newGuid()
{
    return QUuid::createUuid().toString(QUuid::WithoutBraces).remove(QLatin1Char('-')).toLatin1();
}

// Tripwire: the crash-safety gate rests on WAL + synchronous=FULL. If a future change
// (or an exotic filesystem) leaves either pragma unset, refuse to open rather than run
// with silently weakened durability.
bool durabilityPragmasHeld(sqlite3 *db)
{
    Stmt j(db, "PRAGMA journal_mode");
    if (!j.row() || QByteArray(reinterpret_cast<const char *>(sqlite3_column_text(j.s, 0)))
                        .compare("wal", Qt::CaseInsensitive) != 0)
        return false;
    Stmt s(db, "PRAGMA synchronous");
    return s.row() && sqlite3_column_int(s.s, 0) == 2; // 2 == FULL
}

const char *const kSchemaV1 =
    "CREATE TABLE schema_version(version INTEGER NOT NULL);"
    "CREATE TABLE notebooks(id INTEGER PRIMARY KEY, title TEXT NOT NULL,"
    "  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);"
    "CREATE TABLE pages(id INTEGER PRIMARY KEY, notebook_id INTEGER NOT NULL"
    "  REFERENCES notebooks(id) ON DELETE CASCADE, seq INTEGER NOT NULL);"
    "CREATE TABLE strokes(id INTEGER PRIMARY KEY, page_id INTEGER NOT NULL"
    "  REFERENCES pages(id) ON DELETE CASCADE, tool INTEGER NOT NULL DEFAULT 0,"
    "  base_width REAL NOT NULL, points BLOB NOT NULL, created_at INTEGER NOT NULL);"
    "CREATE INDEX strokes_page ON strokes(page_id);"
    "INSERT INTO schema_version(version) VALUES(1);";

// Reader schema (Phase 2a). Positions are shared PDF/EPUB: for PDF
// spine_index = 0-based page, char_offset = 0, percentage = (page+1)/pageCount.
const char *const kSchemaV2 =
    "CREATE TABLE books(id INTEGER PRIMARY KEY, title TEXT NOT NULL, author TEXT,"
    "  file_path TEXT NOT NULL, digest TEXT NOT NULL, cover_path TEXT,"
    "  size_bytes INTEGER NOT NULL, format INTEGER NOT NULL DEFAULT 0,"  // 0 pdf, 1 epub
    "  added_at INTEGER NOT NULL, last_opened_at INTEGER);"
    "CREATE UNIQUE INDEX books_digest ON books(digest);"
    "CREATE TABLE progress(book_id INTEGER PRIMARY KEY REFERENCES books(id) ON DELETE CASCADE,"
    "  spine_index INTEGER NOT NULL, char_offset INTEGER NOT NULL, percentage REAL NOT NULL,"
    "  updated_at INTEGER NOT NULL, synced_at INTEGER);"
    "CREATE TABLE reading_sessions(id INTEGER PRIMARY KEY, book_id INTEGER NOT NULL"
    "  REFERENCES books(id) ON DELETE CASCADE, started_at INTEGER NOT NULL,"
    "  ended_at INTEGER NOT NULL, pages_turned INTEGER NOT NULL,"
    "  start_percentage REAL NOT NULL, end_percentage REAL NOT NULL);"
    "CREATE INDEX reading_sessions_book ON reading_sessions(book_id);"
    "CREATE TABLE bookmarks(id INTEGER PRIMARY KEY, book_id INTEGER NOT NULL"
    "  REFERENCES books(id) ON DELETE CASCADE, spine_index INTEGER NOT NULL,"
    "  char_offset INTEGER NOT NULL, percentage REAL NOT NULL, label TEXT NOT NULL,"
    "  created_at INTEGER NOT NULL);"
    "CREATE INDEX bookmarks_book ON bookmarks(book_id);"
    "CREATE TABLE highlights(id INTEGER PRIMARY KEY, book_id INTEGER NOT NULL"
    "  REFERENCES books(id) ON DELETE CASCADE, spine_index INTEGER NOT NULL,"
    "  start_char INTEGER NOT NULL, end_char INTEGER NOT NULL, text TEXT NOT NULL,"
    "  note TEXT, color_id INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL);"
    "CREATE INDEX highlights_book_spine ON highlights(book_id, spine_index);"
    "CREATE TABLE collections(id INTEGER PRIMARY KEY, name TEXT NOT NULL,"
    "  created_at INTEGER NOT NULL);"
    "CREATE TABLE book_collections(collection_id INTEGER NOT NULL"
    "  REFERENCES collections(id) ON DELETE CASCADE, book_id INTEGER NOT NULL"
    "  REFERENCES books(id) ON DELETE CASCADE, PRIMARY KEY(collection_id, book_id));"
    "CREATE INDEX book_collections_book ON book_collections(book_id);"
    "CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);"
    "UPDATE schema_version SET version=2;";

// Every SYNCED table (settings/schema_version/sync_state are device-local, not synced).
const char *const kSyncedTables[] = {
    "books",     "progress", "bookmarks", "highlights", "collections",
    "book_collections", "notebooks", "pages", "strokes", "reading_sessions",
};

// Sync foundation (Phase 3a). guid + tombstone on every synced table; updated_at added where
// the table lacks one (progress and notebooks already carry updated_at). The per-row guid
// backfill can't run in SQL (QUuid is C++), so it happens in backfillV3() inside the same Tx.
const char *const kSchemaV3 =
    "ALTER TABLE books ADD COLUMN guid TEXT;"
    "ALTER TABLE books ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0;"
    "ALTER TABLE books ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0;"
    "ALTER TABLE progress ADD COLUMN guid TEXT;"
    "ALTER TABLE progress ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0;"
    "ALTER TABLE bookmarks ADD COLUMN guid TEXT;"
    "ALTER TABLE bookmarks ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0;"
    "ALTER TABLE bookmarks ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0;"
    "ALTER TABLE highlights ADD COLUMN guid TEXT;"
    "ALTER TABLE highlights ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0;"
    "ALTER TABLE highlights ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0;"
    "ALTER TABLE collections ADD COLUMN guid TEXT;"
    "ALTER TABLE collections ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0;"
    "ALTER TABLE collections ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0;"
    "ALTER TABLE book_collections ADD COLUMN guid TEXT;"
    "ALTER TABLE book_collections ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0;"
    "ALTER TABLE book_collections ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0;"
    "ALTER TABLE notebooks ADD COLUMN guid TEXT;"
    "ALTER TABLE notebooks ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0;"
    "ALTER TABLE pages ADD COLUMN guid TEXT;"
    "ALTER TABLE pages ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0;"
    "ALTER TABLE pages ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0;"
    "ALTER TABLE strokes ADD COLUMN guid TEXT;"
    "ALTER TABLE strokes ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0;"
    "ALTER TABLE strokes ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0;"
    "ALTER TABLE reading_sessions ADD COLUMN guid TEXT;"
    "ALTER TABLE reading_sessions ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0;"
    "ALTER TABLE reading_sessions ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0;"
    // Unique guid per table. NULL guids (existing rows, pre-backfill) are allowed to coexist —
    // SQLite treats NULLs as distinct in a UNIQUE index; backfillV3() then fills unique values.
    "CREATE UNIQUE INDEX books_guid ON books(guid);"
    "CREATE UNIQUE INDEX progress_guid ON progress(guid);"
    "CREATE UNIQUE INDEX bookmarks_guid ON bookmarks(guid);"
    "CREATE UNIQUE INDEX highlights_guid ON highlights(guid);"
    "CREATE UNIQUE INDEX collections_guid ON collections(guid);"
    "CREATE UNIQUE INDEX book_collections_guid ON book_collections(guid);"
    "CREATE UNIQUE INDEX notebooks_guid ON notebooks(guid);"
    "CREATE UNIQUE INDEX pages_guid ON pages(guid);"
    "CREATE UNIQUE INDEX strokes_guid ON strokes(guid);"
    "CREATE UNIQUE INDEX reading_sessions_guid ON reading_sessions(guid);"
    // Device-local sync bookkeeping (last_sync_at, per-table high-water) — NOT synced.
    "CREATE TABLE sync_state(key TEXT PRIMARY KEY, value TEXT NOT NULL);"
    // Seed updated_at from each row's natural creation time so a pre-v3 row keeps a stable LWW
    // key that agrees across devices (pages/book_collections have no source -> now() in C++).
    "UPDATE books SET updated_at = added_at WHERE updated_at = 0;"
    "UPDATE bookmarks SET updated_at = created_at WHERE updated_at = 0;"
    "UPDATE highlights SET updated_at = created_at WHERE updated_at = 0;"
    "UPDATE collections SET updated_at = created_at WHERE updated_at = 0;"
    "UPDATE strokes SET updated_at = created_at WHERE updated_at = 0;"
    "UPDATE reading_sessions SET updated_at = started_at WHERE updated_at = 0;";

// v3 row backfill (runs inside the v3 Tx): a unique guid for every pre-existing row, plus a now()
// fallback for any updated_at the SQL copy above couldn't seed. rowid is present on all synced
// tables here (all are rowid tables, including the composite-PK join table), so it keys uniformly.
bool backfillV3(sqlite3 *db)
{
    const qint64 ts = now();
    for (const char *t : kSyncedTables) {
        QVector<qint64> rowids;
        {
            char sql[96];
            std::snprintf(sql, sizeof sql, "SELECT rowid FROM %s WHERE guid IS NULL", t);
            Stmt sel(db, sql);
            while (sel.row())
                rowids.append(sqlite3_column_int64(sel.s, 0));
        }
        char sql[96];
        std::snprintf(sql, sizeof sql, "UPDATE %s SET guid=? WHERE rowid=?", t);
        Stmt up(db, sql);
        for (qint64 rid : rowids) {
            const QByteArray g = newGuid();
            sqlite3_bind_text(up.s, 1, g.constData(), g.size(), SQLITE_TRANSIENT);
            sqlite3_bind_int64(up.s, 2, rid);
            if (!up.done())
                return false;
            sqlite3_reset(up.s);
        }
        std::snprintf(sql, sizeof sql, "UPDATE %s SET updated_at=%lld WHERE updated_at=0", t,
                      static_cast<long long>(ts));
        if (!exec(db, sql))
            return false;
    }
    return true;
}

int schemaVersion(sqlite3 *db)
{
    Stmt t(db, "SELECT 1 FROM sqlite_master WHERE type='table' AND name='schema_version'");
    if (!t.row())
        return 0;
    Stmt v(db, "SELECT version FROM schema_version LIMIT 1");
    return v.row() ? sqlite3_column_int(v.s, 0) : 0;
}

bool migrate(sqlite3 *db)
{
    int v = schemaVersion(db);
    // Each step is one Tx: if BEGIN failed, running the multi-statement schema would
    // autocommit piecewise and a mid-way kill leaves tables without schema_version — a
    // bricked DB. A fresh DB (v==0) runs v1 then v2 in sequence.
    if (v < 1) {
        Tx tx(db);
        if (!(tx.active && exec(db, kSchemaV1) && tx.commit()))
            return false;
        v = 1;
    }
    if (v < 2) {
        Tx tx(db);
        if (!(tx.active && exec(db, kSchemaV2) && tx.commit()))
            return false;
        v = 2;
    }
    // v3 = sync foundation: guid/deleted/updated_at on synced tables + backfill, one Tx like v1/v2.
    if (v < 3) {
        Tx tx(db);
        if (!(tx.active && exec(db, kSchemaV3) && backfillV3(db)
              && exec(db, "UPDATE schema_version SET version=3") && tx.commit()))
            return false;
        v = 3;
    }
    return true;
}

// Make the directory entries themselves durable. SQLite fsyncs the WAL's directory,
// but never the chain above a freshly created data dir — a power cut after a first
// session could otherwise lose data.db entirely on remount.
void fsyncDir(const QString &path)
{
    const int fd = ::open(QFile::encodeName(path).constData(), O_RDONLY | O_DIRECTORY);
    if (fd >= 0) {
        ::fsync(fd);
        ::close(fd);
    }
}

}  // namespace

Storage *Storage::open(const QString &dbDir, QString *error)
{
    if (!QDir().mkpath(dbDir)) {
        if (error)
            *error = QStringLiteral("cannot create directory %1").arg(dbDir);
        return nullptr;
    }
    const QByteArray path = QDir(dbDir).filePath(QStringLiteral("data.db")).toUtf8();
    sqlite3 *db = nullptr;
    if (sqlite3_open_v2(path.constData(), &db,
                        SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, nullptr) != SQLITE_OK) {
        if (error)
            *error = QString::fromUtf8(db ? sqlite3_errmsg(db) : "sqlite3_open_v2 failed");
        sqlite3_close(db);
        return nullptr;
    }
    sqlite3_busy_timeout(db, 5000);
    // synchronous=FULL required: NORMAL can lose the last WAL commits on hard
    // power-cut, which fails the Phase 1 crash-safety gate.
    // CCX_SYNC_NORMAL: dev-only latency A/B knob — NEVER ship enabled.
    const bool devSyncNormal = qEnvironmentVariableIsSet("CCX_SYNC_NORMAL");
    if (devSyncNormal)
        qWarning("storage: DEV MODE synchronous=NORMAL — power-cut durability is OFF");
    if (!exec(db, "PRAGMA journal_mode=WAL")
        || !exec(db, devSyncNormal ? "PRAGMA synchronous=NORMAL" : "PRAGMA synchronous=FULL")
        || !exec(db, "PRAGMA foreign_keys=ON")
        || !(devSyncNormal || durabilityPragmasHeld(db))
        || !migrate(db)) {
        if (error)
            *error = QString::fromUtf8(sqlite3_errmsg(db));
        sqlite3_close(db);
        return nullptr;
    }
    fsyncDir(dbDir);
    fsyncDir(QFileInfo(dbDir).absolutePath());
    return new Storage(db);
}

Storage::~Storage()
{
    sqlite3_close(m_db);
}

QVector<NotebookInfo> Storage::notebooks()
{
    QVector<NotebookInfo> out;
    Stmt q(m_db,
           "SELECT n.id, n.title,"
           "  (SELECT COUNT(*) FROM pages p WHERE p.notebook_id = n.id AND p.deleted = 0),"
           "  n.updated_at FROM notebooks n WHERE n.deleted = 0"
           "  ORDER BY n.updated_at DESC, n.id DESC");
    while (q.row()) {
        out.append({sqlite3_column_int64(q.s, 0),
                    QString::fromUtf8(reinterpret_cast<const char *>(sqlite3_column_text(q.s, 1))),
                    sqlite3_column_int(q.s, 2),
                    sqlite3_column_int64(q.s, 3)});
    }
    return out;
}

qint64 Storage::createNotebook(const QString &title)
{
    Tx tx(m_db);
    if (!tx.active)  // BEGIN failed: bail before statements degrade to autocommit
        return -1;
    Stmt q(m_db, "INSERT INTO notebooks(title, created_at, updated_at, guid) VALUES(?, ?, ?, ?)");
    const QByteArray t = title.toUtf8();
    const QByteArray g = newGuid();
    const qint64 ts = now();
    sqlite3_bind_text(q.s, 1, t.constData(), t.size(), SQLITE_STATIC);
    sqlite3_bind_int64(q.s, 2, ts);
    sqlite3_bind_int64(q.s, 3, ts);
    sqlite3_bind_text(q.s, 4, g.constData(), g.size(), SQLITE_STATIC);
    if (!q.done())
        return -1;
    const qint64 id = sqlite3_last_insert_rowid(m_db);
    return tx.commit() ? id : -1;
}

void Storage::renameNotebook(qint64 id, const QString &title)
{
    Tx tx(m_db);
    if (!tx.active)
        return;
    Stmt q(m_db, "UPDATE notebooks SET title = ?, updated_at = ? WHERE id = ?");
    const QByteArray t = title.toUtf8();
    sqlite3_bind_text(q.s, 1, t.constData(), t.size(), SQLITE_STATIC);
    sqlite3_bind_int64(q.s, 2, now());
    sqlite3_bind_int64(q.s, 3, id);
    if (q.done())
        tx.commit();
}

void Storage::deleteNotebook(qint64 id)
{
    Tx tx(m_db);
    if (!tx.active)
        return;
    const qint64 ts = now();
    // Soft-delete: tombstone the notebook AND every descendant so the delete propagates on sync
    // and a page/stroke can't resurrect from another device. Stroke blobs are emptied to reclaim
    // space (X'' not NULL: points is NOT NULL and the crash-safety schema keeps it so).
    Stmt ds(m_db, "UPDATE strokes SET deleted = 1, updated_at = ?, points = X'' "
                  "WHERE deleted = 0 AND page_id IN (SELECT id FROM pages WHERE notebook_id = ?)");
    sqlite3_bind_int64(ds.s, 1, ts);
    sqlite3_bind_int64(ds.s, 2, id);
    if (!ds.done())
        return;  // dtor rolls back
    Stmt dp(m_db, "UPDATE pages SET deleted = 1, updated_at = ? WHERE deleted = 0 AND notebook_id = ?");
    sqlite3_bind_int64(dp.s, 1, ts);
    sqlite3_bind_int64(dp.s, 2, id);
    if (!dp.done())
        return;
    Stmt dn(m_db, "UPDATE notebooks SET deleted = 1, updated_at = ? WHERE id = ?");
    sqlite3_bind_int64(dn.s, 1, ts);
    sqlite3_bind_int64(dn.s, 2, id);
    if (dn.done())
        tx.commit();
}

QVector<PageInfo> Storage::pages(qint64 notebookId)
{
    QVector<PageInfo> out;
    Stmt q(m_db, "SELECT id, seq FROM pages WHERE notebook_id = ? AND deleted = 0 ORDER BY seq");
    sqlite3_bind_int64(q.s, 1, notebookId);
    while (q.row())
        out.append({sqlite3_column_int64(q.s, 0), sqlite3_column_int(q.s, 1)});
    return out;
}

qint64 Storage::createPage(qint64 notebookId)
{
    Tx tx(m_db);
    if (!tx.active)
        return -1;
    Stmt q(m_db,
           "INSERT INTO pages(notebook_id, seq, guid, updated_at) VALUES(?1,"
           "  COALESCE((SELECT MAX(seq) FROM pages WHERE notebook_id = ?1), 0) + 1, ?2, ?3)");
    const QByteArray g = newGuid();
    sqlite3_bind_int64(q.s, 1, notebookId);
    sqlite3_bind_text(q.s, 2, g.constData(), g.size(), SQLITE_STATIC);
    sqlite3_bind_int64(q.s, 3, now());
    if (!q.done())
        return -1;
    const qint64 id = sqlite3_last_insert_rowid(m_db);
    return tx.commit() ? id : -1;
}

void Storage::deletePage(qint64 pageId)
{
    Tx tx(m_db);
    if (!tx.active)
        return;
    const qint64 ts = now();
    Stmt ds(m_db, "UPDATE strokes SET deleted = 1, updated_at = ?, points = X'' "
                  "WHERE deleted = 0 AND page_id = ?");
    sqlite3_bind_int64(ds.s, 1, ts);
    sqlite3_bind_int64(ds.s, 2, pageId);
    if (!ds.done())
        return;  // dtor rolls back
    Stmt dp(m_db, "UPDATE pages SET deleted = 1, updated_at = ? WHERE id = ?");
    sqlite3_bind_int64(dp.s, 1, ts);
    sqlite3_bind_int64(dp.s, 2, pageId);
    if (dp.done())
        tx.commit();
}

qint64 Storage::appendStroke(const StrokeData &s)
{
    const QByteArray blob = encodePoints(s.pts);
    Tx tx(m_db);
    if (!tx.active)
        return -1;
    Stmt q(m_db,
           "INSERT INTO strokes(page_id, tool, base_width, points, created_at, guid, updated_at)"
           "  VALUES(?, ?, ?, ?, ?, ?, ?)");
    const QByteArray g = newGuid();
    const qint64 ts = now();
    sqlite3_bind_int64(q.s, 1, s.pageId);
    sqlite3_bind_int(q.s, 2, s.tool);
    sqlite3_bind_double(q.s, 3, s.baseWidth);
    sqlite3_bind_blob(q.s, 4, blob.constData(), blob.size(), SQLITE_STATIC);
    sqlite3_bind_int64(q.s, 5, ts);
    sqlite3_bind_text(q.s, 6, g.constData(), g.size(), SQLITE_STATIC);
    sqlite3_bind_int64(q.s, 7, ts);
    if (!q.done())
        return -1;
    const qint64 id = sqlite3_last_insert_rowid(m_db);
    return tx.commit() ? id : -1;
}

bool Storage::removeStrokes(const QVector<qint64> &ids)
{
    if (ids.isEmpty())
        return true;
    Tx tx(m_db);
    if (!tx.active)
        return false;
    // Soft-delete: keep the tombstone row (so the erase can sync) but empty the points blob.
    Stmt q(m_db, "UPDATE strokes SET deleted = 1, updated_at = ?, points = X'' WHERE id = ?");
    const qint64 ts = now();
    for (qint64 id : ids) {
        sqlite3_bind_int64(q.s, 1, ts);
        sqlite3_bind_int64(q.s, 2, id);
        if (!q.done())
            return false;  // dtor rolls back — all-or-nothing
        sqlite3_reset(q.s);
    }
    return tx.commit();
}

bool Storage::restoreStrokes(QVector<StrokeData> &s)
{
    if (s.isEmpty())
        return true;
    Tx tx(m_db);
    if (!tx.active)
        return false;
    // Undo of a soft-delete: the tombstoned row still exists, so revive it in place (deleted=0,
    // points restored) via UPSERT — the ON CONFLICT keeps the row's original guid, so an
    // erased-then-undone stroke returns with its sync identity unchanged. The fresh guid bound
    // below is only used on the (shouldn't-happen) branch where the row is truly gone.
    Stmt q(m_db,
           "INSERT INTO strokes(id, page_id, tool, base_width, points, created_at, guid, updated_at)"
           "  VALUES(?, ?, ?, ?, ?, ?, ?, ?)"
           "  ON CONFLICT(id) DO UPDATE SET page_id = excluded.page_id, tool = excluded.tool,"
           "    base_width = excluded.base_width, points = excluded.points,"
           "    updated_at = excluded.updated_at, deleted = 0");
    const qint64 ts = now();
    for (const StrokeData &sd : s) {
        const QByteArray blob = encodePoints(sd.pts);
        const QByteArray g = newGuid();
        sqlite3_bind_int64(q.s, 1, sd.id);
        sqlite3_bind_int64(q.s, 2, sd.pageId);
        sqlite3_bind_int(q.s, 3, sd.tool);
        sqlite3_bind_double(q.s, 4, sd.baseWidth);
        // TRANSIENT: blob/guid die each loop iteration, sqlite must copy before reset
        sqlite3_bind_blob(q.s, 5, blob.constData(), blob.size(), SQLITE_TRANSIENT);
        sqlite3_bind_int64(q.s, 6, ts);
        sqlite3_bind_text(q.s, 7, g.constData(), g.size(), SQLITE_TRANSIENT);
        sqlite3_bind_int64(q.s, 8, ts);
        if (!q.done())
            return false;  // dtor rolls back — all-or-nothing
        sqlite3_reset(q.s);
    }
    return tx.commit();
}

bool Storage::replaceStrokes(const QVector<qint64> &removeIds, QVector<StrokeData> &add,
                             bool keepAddIds)
{
    if (removeIds.isEmpty() && add.isEmpty())
        return true;
    Tx tx(m_db);
    if (!tx.active)
        return false;
    const qint64 ts = now();
    {  // soft-delete the removed originals (empty their blobs, keep the tombstone)
        Stmt del(m_db, "UPDATE strokes SET deleted = 1, updated_at = ?, points = X'' WHERE id = ?");
        for (qint64 id : removeIds) {
            sqlite3_bind_int64(del.s, 1, ts);
            sqlite3_bind_int64(del.s, 2, id);
            if (!del.done())
                return false;  // dtor rolls back — all-or-nothing
            sqlite3_reset(del.s);
        }
    }
    // keepAddIds=false: genuinely new fragments (fresh guid). keepAddIds=true: undo/redo reviving
    // rows that were soft-deleted above/earlier — UPSERT keeps their original guid (identity).
    Stmt ins(m_db, keepAddIds
        ? "INSERT INTO strokes(id, page_id, tool, base_width, points, created_at, guid, updated_at)"
          "  VALUES(?, ?, ?, ?, ?, ?, ?, ?)"
          "  ON CONFLICT(id) DO UPDATE SET page_id = excluded.page_id, tool = excluded.tool,"
          "    base_width = excluded.base_width, points = excluded.points,"
          "    updated_at = excluded.updated_at, deleted = 0"
        : "INSERT INTO strokes(page_id, tool, base_width, points, created_at, guid, updated_at)"
          "  VALUES(?, ?, ?, ?, ?, ?, ?)");
    for (StrokeData &sd : add) {
        const QByteArray blob = encodePoints(sd.pts);
        const QByteArray g = newGuid();
        int col = 1;
        if (keepAddIds)
            sqlite3_bind_int64(ins.s, col++, sd.id);
        sqlite3_bind_int64(ins.s, col++, sd.pageId);
        sqlite3_bind_int(ins.s, col++, sd.tool);
        sqlite3_bind_double(ins.s, col++, sd.baseWidth);
        sqlite3_bind_blob(ins.s, col++, blob.constData(), blob.size(), SQLITE_TRANSIENT);
        sqlite3_bind_int64(ins.s, col++, ts);   // created_at
        sqlite3_bind_text(ins.s, col++, g.constData(), g.size(), SQLITE_TRANSIENT);  // guid
        sqlite3_bind_int64(ins.s, col++, ts);   // updated_at
        if (!ins.done())
            return false;
        if (!keepAddIds)
            sd.id = sqlite3_last_insert_rowid(m_db);
        sqlite3_reset(ins.s);
    }
    return tx.commit();
}

QVector<StrokeData> Storage::strokes(qint64 pageId)
{
    QVector<StrokeData> out;
    Stmt q(m_db,
           "SELECT id, tool, base_width, points FROM strokes"
           "  WHERE page_id = ? AND deleted = 0 ORDER BY id");
    sqlite3_bind_int64(q.s, 1, pageId);
    while (q.row()) {
        StrokeData s;
        s.id = sqlite3_column_int64(q.s, 0);
        s.pageId = pageId;
        s.tool = sqlite3_column_int(q.s, 1);
        s.baseWidth = float(sqlite3_column_double(q.s, 2));
        s.pts = decodePoints(sqlite3_column_blob(q.s, 3), sqlite3_column_bytes(q.s, 3));
        out.append(s);
    }
    return out;
}
