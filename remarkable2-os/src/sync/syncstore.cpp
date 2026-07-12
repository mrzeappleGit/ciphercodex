#include "sync/syncstore.h"

#include "storage/storage.h"

#include <sqlite3.h>

#include <QDateTime>
#include <QHash>
#include <QJsonArray>
#include <QJsonValue>
#include <QUuid>

#include <functional>

namespace {

qint64 now() { return QDateTime::currentMSecsSinceEpoch(); }

// Sync identity: UUIDv4, dashes stripped — same format as storage.cpp/library.cpp.
QByteArray newGuid()
{
    return QUuid::createUuid().toString(QUuid::WithoutBraces).remove(QLatin1Char('-')).toLatin1();
}

// Same prepared-statement / transaction RAII the rest of the reader uses against Storage's
// connection (foreign_keys=ON, WAL, synchronous=FULL). Kept per-TU like storage.cpp/library.cpp.
struct Stmt {
    sqlite3_stmt *s = nullptr;
    Stmt(sqlite3 *db, const char *sql)
    {
        if (sqlite3_prepare_v2(db, sql, -1, &s, nullptr) != SQLITE_OK)
            qWarning("syncstore: prepare failed: %s -- %s", sqlite3_errmsg(db), sql);
    }
    ~Stmt() { sqlite3_finalize(s); }
    bool row() { return s && sqlite3_step(s) == SQLITE_ROW; }
    bool done() { return s && sqlite3_step(s) == SQLITE_DONE; }
};

bool exec(sqlite3 *db, const char *sql)
{
    char *err = nullptr;
    if (sqlite3_exec(db, sql, nullptr, nullptr, &err) != SQLITE_OK) {
        qWarning("syncstore: %s -- %s", err ? err : "error", sql);
        sqlite3_free(err);
        return false;
    }
    return true;
}

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
    // Commit the current segment and open a fresh one. Lets the merge release the single WAL
    // write lock between tables so a concurrent pen-drawing write on the GUI connection can
    // interleave instead of blocking (and timing out -> dropping ink). Each table stays atomic;
    // the whole merge is idempotent, so a crash between segments just resumes on the next sync.
    bool restart()
    {
        return commit() && (active = exec(db, "BEGIN IMMEDIATE"));
    }
    ~Tx()
    {
        if (active)
            exec(db, "ROLLBACK");
    }
};

void bindText(sqlite3_stmt *s, int i, const QByteArray &v)
{
    sqlite3_bind_text(s, i, v.constData(), v.size(), SQLITE_TRANSIENT);
}

// Bind a JSON string, mapping null/absent to SQL NULL (author, highlight note). TRANSIENT so the
// temporary QByteArray can die before step().
void bindJsonText(sqlite3_stmt *s, int i, const QJsonValue &v)
{
    if (v.isNull() || v.isUndefined()) {
        sqlite3_bind_null(s, i);
    } else {
        const QByteArray b = v.toString().toUtf8();
        sqlite3_bind_text(s, i, b.constData(), b.size(), SQLITE_TRANSIENT);
    }
}

// last_opened_at uses 0 as the never-opened sentinel (NULL in the DB); keep that on the wire.
void bindI64OrNull(sqlite3_stmt *s, int i, qint64 v)
{
    if (v == 0)
        sqlite3_bind_null(s, i);
    else
        sqlite3_bind_int64(s, i, v);
}

QString colText(sqlite3_stmt *s, int i)
{
    return QString::fromUtf8(reinterpret_cast<const char *>(sqlite3_column_text(s, i)));
}

// Text column -> JSON string, NULL -> JSON null (roundtrips author/note faithfully).
QJsonValue jText(sqlite3_stmt *s, int i)
{
    if (sqlite3_column_type(s, i) == SQLITE_NULL)
        return QJsonValue(QJsonValue::Null);
    return QJsonValue(colText(s, i));
}

qint64 jI64(const QJsonObject &o, const char *k) { return qint64(o.value(QLatin1String(k)).toDouble()); }
int jInt(const QJsonObject &o, const char *k) { return o.value(QLatin1String(k)).toInt(); }
QByteArray jStr(const QJsonObject &o, const char *k) { return o.value(QLatin1String(k)).toString().toUtf8(); }

// LWW winner across every device snapshot for one entity key: greatest updatedAt, tombstone wins a
// tie. (Reducing here first makes apply order-independent.)
using Merged = QHash<QString, QJsonObject>;
Merged mergeTable(const QVector<QJsonObject> &snaps, const char *arrayKey,
                  const std::function<QString(const QJsonObject &)> &keyOf)
{
    Merged m;
    for (const QJsonObject &snap : snaps) {
        const QJsonArray arr = snap.value(QLatin1String(arrayKey)).toArray();
        for (const QJsonValue &v : arr) {
            const QJsonObject r = v.toObject();
            const QString k = keyOf(r);
            if (k.isEmpty())
                continue;
            auto it = m.find(k);
            if (it == m.end()) {
                m.insert(k, r);
                continue;
            }
            const qint64 a = jI64(r, "updatedAt");
            const qint64 b = jI64(it.value(), "updatedAt");
            if (a > b || (a == b && jInt(r, "deleted") && !jInt(it.value(), "deleted")))
                it.value() = r;
        }
    }
    return m;
}

// Local row's LWW state for a key (updated_at + deleted), or exists=false.
struct LocalMeta {
    bool exists = false;
    qint64 updatedAt = -1;
    int deleted = 0;
};

// Whether a remote record beats the local row: newer, or a tie the tombstone wins, or no local row.
bool shouldApply(const LocalMeta &m, qint64 rUpd, int rDel)
{
    return !m.exists || rUpd > m.updatedAt || (rUpd == m.updatedAt && rDel && !m.deleted);
}

LocalMeta metaByGuid(sqlite3 *db, const char *table, const QByteArray &guid)
{
    char sql[96];
    std::snprintf(sql, sizeof sql, "SELECT updated_at, deleted FROM %s WHERE guid = ?", table);
    Stmt q(db, sql);
    bindText(q.s, 1, guid);
    LocalMeta m;
    if (q.row()) {
        m.exists = true;
        m.updatedAt = sqlite3_column_int64(q.s, 0);
        m.deleted = sqlite3_column_int(q.s, 1);
    }
    return m;
}

qint64 idByGuid(sqlite3 *db, const char *table, const QByteArray &guid)
{
    char sql[64];
    std::snprintf(sql, sizeof sql, "SELECT id FROM %s WHERE guid = ?", table);
    Stmt q(db, sql);
    bindText(q.s, 1, guid);
    return q.row() ? sqlite3_column_int64(q.s, 0) : -1;
}

qint64 bookIdForDigest(sqlite3 *db, const QByteArray &digest)
{
    Stmt q(db, "SELECT id FROM books WHERE digest = ?");
    bindText(q.s, 1, digest);
    return q.row() ? sqlite3_column_int64(q.s, 0) : -1;
}

}  // namespace

QJsonObject SyncStore::exportSnapshot(const QString &deviceId)
{
    sqlite3 *db = m_storage->handle();
    // One deferred read transaction so all ~12 table reads see a SINGLE consistent WAL snapshot:
    // a GUI write committed mid-export can't otherwise make children reference a parent not yet
    // read (or vice versa). A read txn doesn't hold the write lock, so it never blocks the pen.
    exec(db, "BEGIN");
    QJsonObject snap;
    snap.insert(QStringLiteral("deviceId"), deviceId);
    snap.insert(QStringLiteral("generatedAt"), now());

    {
        QJsonArray a;
        Stmt q(db, "SELECT digest, guid, title, author, format, added_at, last_opened_at, deleted,"
                   "  updated_at FROM books");
        while (q.row()) {
            a.append(QJsonObject{
                {QStringLiteral("digest"), colText(q.s, 0)},
                {QStringLiteral("guid"), colText(q.s, 1)},
                {QStringLiteral("title"), colText(q.s, 2)},
                {QStringLiteral("author"), jText(q.s, 3)},
                {QStringLiteral("format"), sqlite3_column_int(q.s, 4)},
                {QStringLiteral("addedAt"), sqlite3_column_int64(q.s, 5)},
                {QStringLiteral("lastOpenedAt"),
                 sqlite3_column_type(q.s, 6) == SQLITE_NULL ? 0 : sqlite3_column_int64(q.s, 6)},
                {QStringLiteral("deleted"), sqlite3_column_int(q.s, 7)},
                {QStringLiteral("updatedAt"), sqlite3_column_int64(q.s, 8)}});
        }
        snap.insert(QStringLiteral("books"), a);
    }
    {  // progress -> its book's digest
        QJsonArray a;
        Stmt q(db, "SELECT b.digest, p.spine_index, p.char_offset, p.percentage, p.deleted,"
                   "  p.updated_at FROM progress p JOIN books b ON b.id = p.book_id");
        while (q.row()) {
            a.append(QJsonObject{
                {QStringLiteral("bookDigest"), colText(q.s, 0)},
                {QStringLiteral("spineIndex"), sqlite3_column_int(q.s, 1)},
                {QStringLiteral("charOffset"), sqlite3_column_int(q.s, 2)},
                {QStringLiteral("percentage"), sqlite3_column_double(q.s, 3)},
                {QStringLiteral("deleted"), sqlite3_column_int(q.s, 4)},
                {QStringLiteral("updatedAt"), sqlite3_column_int64(q.s, 5)}});
        }
        snap.insert(QStringLiteral("progress"), a);
    }
    {
        QJsonArray a;
        Stmt q(db, "SELECT bm.guid, b.digest, bm.spine_index, bm.char_offset, bm.percentage,"
                   "  bm.label, bm.created_at, bm.deleted, bm.updated_at"
                   "  FROM bookmarks bm JOIN books b ON b.id = bm.book_id");
        while (q.row()) {
            a.append(QJsonObject{
                {QStringLiteral("guid"), colText(q.s, 0)},
                {QStringLiteral("bookDigest"), colText(q.s, 1)},
                {QStringLiteral("spineIndex"), sqlite3_column_int(q.s, 2)},
                {QStringLiteral("charOffset"), sqlite3_column_int(q.s, 3)},
                {QStringLiteral("percentage"), sqlite3_column_double(q.s, 4)},
                {QStringLiteral("label"), colText(q.s, 5)},
                {QStringLiteral("createdAt"), sqlite3_column_int64(q.s, 6)},
                {QStringLiteral("deleted"), sqlite3_column_int(q.s, 7)},
                {QStringLiteral("updatedAt"), sqlite3_column_int64(q.s, 8)}});
        }
        snap.insert(QStringLiteral("bookmarks"), a);
    }
    {
        QJsonArray a;
        Stmt q(db, "SELECT h.guid, b.digest, h.spine_index, h.start_char, h.end_char, h.text,"
                   "  h.note, h.color_id, h.created_at, h.deleted, h.updated_at"
                   "  FROM highlights h JOIN books b ON b.id = h.book_id");
        while (q.row()) {
            a.append(QJsonObject{
                {QStringLiteral("guid"), colText(q.s, 0)},
                {QStringLiteral("bookDigest"), colText(q.s, 1)},
                {QStringLiteral("spineIndex"), sqlite3_column_int(q.s, 2)},
                {QStringLiteral("startChar"), sqlite3_column_int(q.s, 3)},
                {QStringLiteral("endChar"), sqlite3_column_int(q.s, 4)},
                {QStringLiteral("text"), colText(q.s, 5)},
                {QStringLiteral("note"), jText(q.s, 6)},
                {QStringLiteral("colorId"), sqlite3_column_int(q.s, 7)},
                {QStringLiteral("createdAt"), sqlite3_column_int64(q.s, 8)},
                {QStringLiteral("deleted"), sqlite3_column_int(q.s, 9)},
                {QStringLiteral("updatedAt"), sqlite3_column_int64(q.s, 10)}});
        }
        snap.insert(QStringLiteral("highlights"), a);
    }
    {
        QJsonArray a;
        Stmt q(db, "SELECT guid, name, created_at, deleted, updated_at FROM collections");
        while (q.row()) {
            a.append(QJsonObject{
                {QStringLiteral("guid"), colText(q.s, 0)},
                {QStringLiteral("name"), colText(q.s, 1)},
                {QStringLiteral("createdAt"), sqlite3_column_int64(q.s, 2)},
                {QStringLiteral("deleted"), sqlite3_column_int(q.s, 3)},
                {QStringLiteral("updatedAt"), sqlite3_column_int64(q.s, 4)}});
        }
        snap.insert(QStringLiteral("collections"), a);
    }
    {  // book_collections identity = (collection guid, book digest)
        QJsonArray a;
        Stmt q(db, "SELECT c.guid, b.digest, bc.deleted, bc.updated_at"
                   "  FROM book_collections bc JOIN collections c ON c.id = bc.collection_id"
                   "  JOIN books b ON b.id = bc.book_id");
        while (q.row()) {
            a.append(QJsonObject{
                {QStringLiteral("collectionGuid"), colText(q.s, 0)},
                {QStringLiteral("bookDigest"), colText(q.s, 1)},
                {QStringLiteral("deleted"), sqlite3_column_int(q.s, 2)},
                {QStringLiteral("updatedAt"), sqlite3_column_int64(q.s, 3)}});
        }
        snap.insert(QStringLiteral("bookCollections"), a);
    }
    {
        QJsonArray a;
        Stmt q(db, "SELECT guid, title, created_at, deleted, updated_at FROM notebooks");
        while (q.row()) {
            a.append(QJsonObject{
                {QStringLiteral("guid"), colText(q.s, 0)},
                {QStringLiteral("title"), colText(q.s, 1)},
                {QStringLiteral("createdAt"), sqlite3_column_int64(q.s, 2)},
                {QStringLiteral("deleted"), sqlite3_column_int(q.s, 3)},
                {QStringLiteral("updatedAt"), sqlite3_column_int64(q.s, 4)}});
        }
        snap.insert(QStringLiteral("notebooks"), a);
    }
    {  // pages -> notebook guid
        QJsonArray a;
        Stmt q(db, "SELECT p.guid, n.guid, p.seq, p.deleted, p.updated_at"
                   "  FROM pages p JOIN notebooks n ON n.id = p.notebook_id");
        while (q.row()) {
            a.append(QJsonObject{
                {QStringLiteral("guid"), colText(q.s, 0)},
                {QStringLiteral("notebookGuid"), colText(q.s, 1)},
                {QStringLiteral("seq"), sqlite3_column_int(q.s, 2)},
                {QStringLiteral("deleted"), sqlite3_column_int(q.s, 3)},
                {QStringLiteral("updatedAt"), sqlite3_column_int64(q.s, 4)}});
        }
        snap.insert(QStringLiteral("pages"), a);
    }
    {  // strokes -> page guid; raw packed-points blob base64'd (empty for tombstones)
        QJsonArray a;
        Stmt q(db, "SELECT s.guid, pg.guid, s.tool, s.base_width, s.points, s.created_at,"
                   "  s.deleted, s.updated_at FROM strokes s JOIN pages pg ON pg.id = s.page_id");
        while (q.row()) {
            const QByteArray blob(reinterpret_cast<const char *>(sqlite3_column_blob(q.s, 4)),
                                  sqlite3_column_bytes(q.s, 4));
            a.append(QJsonObject{
                {QStringLiteral("guid"), colText(q.s, 0)},
                {QStringLiteral("pageGuid"), colText(q.s, 1)},
                {QStringLiteral("tool"), sqlite3_column_int(q.s, 2)},
                {QStringLiteral("baseWidth"), sqlite3_column_double(q.s, 3)},
                {QStringLiteral("points_b64"), QString::fromLatin1(blob.toBase64())},
                {QStringLiteral("createdAt"), sqlite3_column_int64(q.s, 5)},
                {QStringLiteral("deleted"), sqlite3_column_int(q.s, 6)},
                {QStringLiteral("updatedAt"), sqlite3_column_int64(q.s, 7)}});
        }
        snap.insert(QStringLiteral("strokes"), a);
    }
    {
        QJsonArray a;
        Stmt q(db, "SELECT rs.guid, b.digest, rs.started_at, rs.ended_at, rs.pages_turned,"
                   "  rs.start_percentage, rs.end_percentage, rs.deleted, rs.updated_at"
                   "  FROM reading_sessions rs JOIN books b ON b.id = rs.book_id");
        while (q.row()) {
            a.append(QJsonObject{
                {QStringLiteral("guid"), colText(q.s, 0)},
                {QStringLiteral("bookDigest"), colText(q.s, 1)},
                {QStringLiteral("startedAt"), sqlite3_column_int64(q.s, 2)},
                {QStringLiteral("endedAt"), sqlite3_column_int64(q.s, 3)},
                {QStringLiteral("pagesTurned"), sqlite3_column_int(q.s, 4)},
                {QStringLiteral("startPercentage"), sqlite3_column_double(q.s, 5)},
                {QStringLiteral("endPercentage"), sqlite3_column_double(q.s, 6)},
                {QStringLiteral("deleted"), sqlite3_column_int(q.s, 7)},
                {QStringLiteral("updatedAt"), sqlite3_column_int64(q.s, 8)}});
        }
        snap.insert(QStringLiteral("sessions"), a);
    }
    exec(db, "COMMIT");  // close the consistent read snapshot
    return snap;
}

SyncStore::MergeStats SyncStore::applyMerged(const QVector<QJsonObject> &remoteSnapshots,
                                             const QString &deviceId)
{
    sqlite3 *db = m_storage->handle();
    MergeStats stats;

    // Skip our own snapshot: the local DB is already >= what we last exported, so it can only
    // ever lose the LWW comparison — pure waste to feed it back in.
    QVector<QJsonObject> snaps;
    for (const QJsonObject &s : remoteSnapshots)
        if (s.value(QStringLiteral("deviceId")).toString() != deviceId)
            snaps.append(s);

    const Merged books = mergeTable(snaps, "books",
        [](const QJsonObject &r) { return r.value(QStringLiteral("digest")).toString(); });
    const Merged progress = mergeTable(snaps, "progress",
        [](const QJsonObject &r) { return r.value(QStringLiteral("bookDigest")).toString(); });
    const Merged bookmarks = mergeTable(snaps, "bookmarks",
        [](const QJsonObject &r) { return r.value(QStringLiteral("guid")).toString(); });
    const Merged highlights = mergeTable(snaps, "highlights",
        [](const QJsonObject &r) { return r.value(QStringLiteral("guid")).toString(); });
    const Merged collections = mergeTable(snaps, "collections",
        [](const QJsonObject &r) { return r.value(QStringLiteral("guid")).toString(); });
    const Merged bookCollections = mergeTable(snaps, "bookCollections", [](const QJsonObject &r) {
        return r.value(QStringLiteral("collectionGuid")).toString() + QLatin1Char('|')
               + r.value(QStringLiteral("bookDigest")).toString();
    });
    const Merged notebooks = mergeTable(snaps, "notebooks",
        [](const QJsonObject &r) { return r.value(QStringLiteral("guid")).toString(); });
    const Merged pages = mergeTable(snaps, "pages",
        [](const QJsonObject &r) { return r.value(QStringLiteral("guid")).toString(); });
    const Merged strokes = mergeTable(snaps, "strokes",
        [](const QJsonObject &r) { return r.value(QStringLiteral("guid")).toString(); });
    const Merged sessions = mergeTable(snaps, "sessions",
        [](const QJsonObject &r) { return r.value(QStringLiteral("guid")).toString(); });

    Tx tx(db);
    if (!tx.active)
        return stats;

    auto tally = [&stats](int rDel) {
        if (rDel)
            ++stats.tombstonesApplied;
        else
            ++stats.entitiesApplied;
    };

    // books by digest — insert new with EMPTY file_path (engine fetches the file later); update
    // keeps local file_path/cover_path/guid (those are device-local, not synced).
    for (const QJsonObject &r : books) {
        const QByteArray digest = jStr(r, "digest");
        if (digest.isEmpty())
            continue;
        const qint64 rUpd = jI64(r, "updatedAt");
        const int rDel = jInt(r, "deleted");
        LocalMeta m;
        {
            Stmt q(db, "SELECT updated_at, deleted FROM books WHERE digest = ?");
            bindText(q.s, 1, digest);
            if (q.row()) {
                m.exists = true;
                m.updatedAt = sqlite3_column_int64(q.s, 0);
                m.deleted = sqlite3_column_int(q.s, 1);
            }
        }
        if (!shouldApply(m, rUpd, rDel))
            continue;
        if (!m.exists) {
            Stmt ins(db, "INSERT INTO books(title, author, file_path, digest, cover_path,"
                         "  size_bytes, format, added_at, last_opened_at, guid, deleted, updated_at)"
                         "  VALUES(?, ?, '', ?, NULL, 0, ?, ?, ?, ?, ?, ?)");
            bindText(ins.s, 1, jStr(r, "title"));
            bindJsonText(ins.s, 2, r.value(QStringLiteral("author")));
            bindText(ins.s, 3, digest);
            sqlite3_bind_int(ins.s, 4, jInt(r, "format"));
            sqlite3_bind_int64(ins.s, 5, jI64(r, "addedAt"));
            bindI64OrNull(ins.s, 6, jI64(r, "lastOpenedAt"));
            bindText(ins.s, 7, jStr(r, "guid"));
            sqlite3_bind_int(ins.s, 8, rDel);
            sqlite3_bind_int64(ins.s, 9, rUpd);
            if (!ins.done())
                return stats;  // dtor rolls back
        } else {
            Stmt up(db, "UPDATE books SET title = ?, author = ?, format = ?, added_at = ?,"
                        "  last_opened_at = ?, deleted = ?, updated_at = ? WHERE digest = ?");
            bindText(up.s, 1, jStr(r, "title"));
            bindJsonText(up.s, 2, r.value(QStringLiteral("author")));
            sqlite3_bind_int(up.s, 3, jInt(r, "format"));
            sqlite3_bind_int64(up.s, 4, jI64(r, "addedAt"));
            bindI64OrNull(up.s, 5, jI64(r, "lastOpenedAt"));
            sqlite3_bind_int(up.s, 6, rDel);
            sqlite3_bind_int64(up.s, 7, rUpd);
            bindText(up.s, 8, digest);
            if (!up.done())
                return stats;
        }
        tally(rDel);
    }

    if (!tx.restart())
        return stats;
    // collections by guid
    for (const QJsonObject &r : collections) {
        const QByteArray guid = jStr(r, "guid");
        if (guid.isEmpty())
            continue;
        const qint64 rUpd = jI64(r, "updatedAt");
        const int rDel = jInt(r, "deleted");
        const LocalMeta m = metaByGuid(db, "collections", guid);
        if (!shouldApply(m, rUpd, rDel))
            continue;
        if (!m.exists) {
            Stmt ins(db, "INSERT INTO collections(name, created_at, guid, deleted, updated_at)"
                         "  VALUES(?, ?, ?, ?, ?)");
            bindText(ins.s, 1, jStr(r, "name"));
            sqlite3_bind_int64(ins.s, 2, jI64(r, "createdAt"));
            bindText(ins.s, 3, guid);
            sqlite3_bind_int(ins.s, 4, rDel);
            sqlite3_bind_int64(ins.s, 5, rUpd);
            if (!ins.done())
                return stats;
        } else {
            Stmt up(db, "UPDATE collections SET name = ?, created_at = ?, deleted = ?,"
                        "  updated_at = ? WHERE guid = ?");
            bindText(up.s, 1, jStr(r, "name"));
            sqlite3_bind_int64(up.s, 2, jI64(r, "createdAt"));
            sqlite3_bind_int(up.s, 3, rDel);
            sqlite3_bind_int64(up.s, 4, rUpd);
            bindText(up.s, 5, guid);
            if (!up.done())
                return stats;
        }
        tally(rDel);
    }

    if (!tx.restart())
        return stats;
    // notebooks by guid
    for (const QJsonObject &r : notebooks) {
        const QByteArray guid = jStr(r, "guid");
        if (guid.isEmpty())
            continue;
        const qint64 rUpd = jI64(r, "updatedAt");
        const int rDel = jInt(r, "deleted");
        const LocalMeta m = metaByGuid(db, "notebooks", guid);
        if (!shouldApply(m, rUpd, rDel))
            continue;
        if (!m.exists) {
            Stmt ins(db, "INSERT INTO notebooks(title, created_at, updated_at, guid, deleted)"
                         "  VALUES(?, ?, ?, ?, ?)");
            bindText(ins.s, 1, jStr(r, "title"));
            sqlite3_bind_int64(ins.s, 2, jI64(r, "createdAt"));
            sqlite3_bind_int64(ins.s, 3, rUpd);
            bindText(ins.s, 4, guid);
            sqlite3_bind_int(ins.s, 5, rDel);
            if (!ins.done())
                return stats;
        } else {
            Stmt up(db, "UPDATE notebooks SET title = ?, created_at = ?, updated_at = ?,"
                        "  deleted = ? WHERE guid = ?");
            bindText(up.s, 1, jStr(r, "title"));
            sqlite3_bind_int64(up.s, 2, jI64(r, "createdAt"));
            sqlite3_bind_int64(up.s, 3, rUpd);
            sqlite3_bind_int(up.s, 4, rDel);
            bindText(up.s, 5, guid);
            if (!up.done())
                return stats;
        }
        tally(rDel);
    }

    if (!tx.restart())
        return stats;
    // pages by guid -> notebook guid; skip if the parent notebook is absent locally (orphan guard)
    for (const QJsonObject &r : pages) {
        const QByteArray guid = jStr(r, "guid");
        if (guid.isEmpty())
            continue;
        const qint64 notebookId = idByGuid(db, "notebooks", jStr(r, "notebookGuid"));
        if (notebookId < 0)
            continue;
        const qint64 rUpd = jI64(r, "updatedAt");
        const int rDel = jInt(r, "deleted");
        const LocalMeta m = metaByGuid(db, "pages", guid);
        if (!shouldApply(m, rUpd, rDel))
            continue;
        if (!m.exists) {
            Stmt ins(db, "INSERT INTO pages(notebook_id, seq, guid, updated_at, deleted)"
                         "  VALUES(?, ?, ?, ?, ?)");
            sqlite3_bind_int64(ins.s, 1, notebookId);
            sqlite3_bind_int(ins.s, 2, jInt(r, "seq"));
            bindText(ins.s, 3, guid);
            sqlite3_bind_int64(ins.s, 4, rUpd);
            sqlite3_bind_int(ins.s, 5, rDel);
            if (!ins.done())
                return stats;
        } else {
            Stmt up(db, "UPDATE pages SET notebook_id = ?, seq = ?, updated_at = ?, deleted = ?"
                        "  WHERE guid = ?");
            sqlite3_bind_int64(up.s, 1, notebookId);
            sqlite3_bind_int(up.s, 2, jInt(r, "seq"));
            sqlite3_bind_int64(up.s, 3, rUpd);
            sqlite3_bind_int(up.s, 4, rDel);
            bindText(up.s, 5, guid);
            if (!up.done())
                return stats;
        }
        tally(rDel);
    }

    if (!tx.restart())
        return stats;
    // strokes by guid -> page guid; base64 -> packed-points blob (empty for tombstones)
    for (const QJsonObject &r : strokes) {
        const QByteArray guid = jStr(r, "guid");
        if (guid.isEmpty())
            continue;
        const qint64 pageId = idByGuid(db, "pages", jStr(r, "pageGuid"));
        if (pageId < 0)
            continue;
        const qint64 rUpd = jI64(r, "updatedAt");
        const int rDel = jInt(r, "deleted");
        const LocalMeta m = metaByGuid(db, "strokes", guid);
        if (!shouldApply(m, rUpd, rDel))
            continue;
        const QByteArray pts =
            QByteArray::fromBase64(r.value(QStringLiteral("points_b64")).toString().toLatin1());
        if (!m.exists) {
            Stmt ins(db, "INSERT INTO strokes(page_id, tool, base_width, points, created_at, guid,"
                         "  updated_at, deleted) VALUES(?, ?, ?, ?, ?, ?, ?, ?)");
            sqlite3_bind_int64(ins.s, 1, pageId);
            sqlite3_bind_int(ins.s, 2, jInt(r, "tool"));
            sqlite3_bind_double(ins.s, 3, r.value(QStringLiteral("baseWidth")).toDouble());
            sqlite3_bind_blob(ins.s, 4, pts.constData(), pts.size(), SQLITE_TRANSIENT);
            sqlite3_bind_int64(ins.s, 5, jI64(r, "createdAt"));
            bindText(ins.s, 6, guid);
            sqlite3_bind_int64(ins.s, 7, rUpd);
            sqlite3_bind_int(ins.s, 8, rDel);
            if (!ins.done())
                return stats;
        } else {
            Stmt up(db, "UPDATE strokes SET page_id = ?, tool = ?, base_width = ?, points = ?,"
                        "  created_at = ?, updated_at = ?, deleted = ? WHERE guid = ?");
            sqlite3_bind_int64(up.s, 1, pageId);
            sqlite3_bind_int(up.s, 2, jInt(r, "tool"));
            sqlite3_bind_double(up.s, 3, r.value(QStringLiteral("baseWidth")).toDouble());
            sqlite3_bind_blob(up.s, 4, pts.constData(), pts.size(), SQLITE_TRANSIENT);
            sqlite3_bind_int64(up.s, 5, jI64(r, "createdAt"));
            sqlite3_bind_int64(up.s, 6, rUpd);
            sqlite3_bind_int(up.s, 7, rDel);
            bindText(up.s, 8, guid);
            if (!up.done())
                return stats;
        }
        tally(rDel);
    }

    if (!tx.restart())
        return stats;
    // progress by book digest (one row per book); mint a guid on insert (snapshot carries none)
    for (const QJsonObject &r : progress) {
        const qint64 bookId = bookIdForDigest(db, jStr(r, "bookDigest"));
        if (bookId < 0)
            continue;
        const qint64 rUpd = jI64(r, "updatedAt");
        const int rDel = jInt(r, "deleted");
        LocalMeta m;
        {
            Stmt q(db, "SELECT updated_at, deleted FROM progress WHERE book_id = ?");
            sqlite3_bind_int64(q.s, 1, bookId);
            if (q.row()) {
                m.exists = true;
                m.updatedAt = sqlite3_column_int64(q.s, 0);
                m.deleted = sqlite3_column_int(q.s, 1);
            }
        }
        if (!shouldApply(m, rUpd, rDel))
            continue;
        if (!m.exists) {
            Stmt ins(db, "INSERT INTO progress(book_id, spine_index, char_offset, percentage,"
                         "  updated_at, synced_at, guid, deleted) VALUES(?, ?, ?, ?, ?, NULL, ?, ?)");
            const QByteArray g = newGuid();
            sqlite3_bind_int64(ins.s, 1, bookId);
            sqlite3_bind_int(ins.s, 2, jInt(r, "spineIndex"));
            sqlite3_bind_int(ins.s, 3, jInt(r, "charOffset"));
            sqlite3_bind_double(ins.s, 4, r.value(QStringLiteral("percentage")).toDouble());
            sqlite3_bind_int64(ins.s, 5, rUpd);
            bindText(ins.s, 6, g);
            sqlite3_bind_int(ins.s, 7, rDel);
            if (!ins.done())
                return stats;
        } else {
            Stmt up(db, "UPDATE progress SET spine_index = ?, char_offset = ?, percentage = ?,"
                        "  updated_at = ?, deleted = ? WHERE book_id = ?");
            sqlite3_bind_int(up.s, 1, jInt(r, "spineIndex"));
            sqlite3_bind_int(up.s, 2, jInt(r, "charOffset"));
            sqlite3_bind_double(up.s, 3, r.value(QStringLiteral("percentage")).toDouble());
            sqlite3_bind_int64(up.s, 4, rUpd);
            sqlite3_bind_int(up.s, 5, rDel);
            sqlite3_bind_int64(up.s, 6, bookId);
            if (!up.done())
                return stats;
        }
        tally(rDel);
    }

    if (!tx.restart())
        return stats;
    // bookmarks by guid -> book digest
    for (const QJsonObject &r : bookmarks) {
        const QByteArray guid = jStr(r, "guid");
        if (guid.isEmpty())
            continue;
        const qint64 bookId = bookIdForDigest(db, jStr(r, "bookDigest"));
        if (bookId < 0)
            continue;
        const qint64 rUpd = jI64(r, "updatedAt");
        const int rDel = jInt(r, "deleted");
        const LocalMeta m = metaByGuid(db, "bookmarks", guid);
        if (!shouldApply(m, rUpd, rDel))
            continue;
        if (!m.exists) {
            Stmt ins(db, "INSERT INTO bookmarks(book_id, spine_index, char_offset, percentage,"
                         "  label, created_at, guid, deleted, updated_at)"
                         "  VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)");
            sqlite3_bind_int64(ins.s, 1, bookId);
            sqlite3_bind_int(ins.s, 2, jInt(r, "spineIndex"));
            sqlite3_bind_int(ins.s, 3, jInt(r, "charOffset"));
            sqlite3_bind_double(ins.s, 4, r.value(QStringLiteral("percentage")).toDouble());
            bindText(ins.s, 5, jStr(r, "label"));
            sqlite3_bind_int64(ins.s, 6, jI64(r, "createdAt"));
            bindText(ins.s, 7, guid);
            sqlite3_bind_int(ins.s, 8, rDel);
            sqlite3_bind_int64(ins.s, 9, rUpd);
            if (!ins.done())
                return stats;
        } else {
            Stmt up(db, "UPDATE bookmarks SET book_id = ?, spine_index = ?, char_offset = ?,"
                        "  percentage = ?, label = ?, created_at = ?, deleted = ?, updated_at = ?"
                        "  WHERE guid = ?");
            sqlite3_bind_int64(up.s, 1, bookId);
            sqlite3_bind_int(up.s, 2, jInt(r, "spineIndex"));
            sqlite3_bind_int(up.s, 3, jInt(r, "charOffset"));
            sqlite3_bind_double(up.s, 4, r.value(QStringLiteral("percentage")).toDouble());
            bindText(up.s, 5, jStr(r, "label"));
            sqlite3_bind_int64(up.s, 6, jI64(r, "createdAt"));
            sqlite3_bind_int(up.s, 7, rDel);
            sqlite3_bind_int64(up.s, 8, rUpd);
            bindText(up.s, 9, guid);
            if (!up.done())
                return stats;
        }
        tally(rDel);
    }

    if (!tx.restart())
        return stats;
    // highlights by guid -> book digest (note is nullable)
    for (const QJsonObject &r : highlights) {
        const QByteArray guid = jStr(r, "guid");
        if (guid.isEmpty())
            continue;
        const qint64 bookId = bookIdForDigest(db, jStr(r, "bookDigest"));
        if (bookId < 0)
            continue;
        const qint64 rUpd = jI64(r, "updatedAt");
        const int rDel = jInt(r, "deleted");
        const LocalMeta m = metaByGuid(db, "highlights", guid);
        if (!shouldApply(m, rUpd, rDel))
            continue;
        if (!m.exists) {
            Stmt ins(db, "INSERT INTO highlights(book_id, spine_index, start_char, end_char, text,"
                         "  note, color_id, created_at, guid, deleted, updated_at)"
                         "  VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            sqlite3_bind_int64(ins.s, 1, bookId);
            sqlite3_bind_int(ins.s, 2, jInt(r, "spineIndex"));
            sqlite3_bind_int(ins.s, 3, jInt(r, "startChar"));
            sqlite3_bind_int(ins.s, 4, jInt(r, "endChar"));
            bindText(ins.s, 5, jStr(r, "text"));
            bindJsonText(ins.s, 6, r.value(QStringLiteral("note")));
            sqlite3_bind_int(ins.s, 7, jInt(r, "colorId"));
            sqlite3_bind_int64(ins.s, 8, jI64(r, "createdAt"));
            bindText(ins.s, 9, guid);
            sqlite3_bind_int(ins.s, 10, rDel);
            sqlite3_bind_int64(ins.s, 11, rUpd);
            if (!ins.done())
                return stats;
        } else {
            Stmt up(db, "UPDATE highlights SET book_id = ?, spine_index = ?, start_char = ?,"
                        "  end_char = ?, text = ?, note = ?, color_id = ?, created_at = ?,"
                        "  deleted = ?, updated_at = ? WHERE guid = ?");
            sqlite3_bind_int64(up.s, 1, bookId);
            sqlite3_bind_int(up.s, 2, jInt(r, "spineIndex"));
            sqlite3_bind_int(up.s, 3, jInt(r, "startChar"));
            sqlite3_bind_int(up.s, 4, jInt(r, "endChar"));
            bindText(up.s, 5, jStr(r, "text"));
            bindJsonText(up.s, 6, r.value(QStringLiteral("note")));
            sqlite3_bind_int(up.s, 7, jInt(r, "colorId"));
            sqlite3_bind_int64(up.s, 8, jI64(r, "createdAt"));
            sqlite3_bind_int(up.s, 9, rDel);
            sqlite3_bind_int64(up.s, 10, rUpd);
            bindText(up.s, 11, guid);
            if (!up.done())
                return stats;
        }
        tally(rDel);
    }

    if (!tx.restart())
        return stats;
    // reading_sessions by guid -> book digest
    for (const QJsonObject &r : sessions) {
        const QByteArray guid = jStr(r, "guid");
        if (guid.isEmpty())
            continue;
        const qint64 bookId = bookIdForDigest(db, jStr(r, "bookDigest"));
        if (bookId < 0)
            continue;
        const qint64 rUpd = jI64(r, "updatedAt");
        const int rDel = jInt(r, "deleted");
        const LocalMeta m = metaByGuid(db, "reading_sessions", guid);
        if (!shouldApply(m, rUpd, rDel))
            continue;
        if (!m.exists) {
            Stmt ins(db, "INSERT INTO reading_sessions(book_id, started_at, ended_at, pages_turned,"
                         "  start_percentage, end_percentage, guid, deleted, updated_at)"
                         "  VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)");
            sqlite3_bind_int64(ins.s, 1, bookId);
            sqlite3_bind_int64(ins.s, 2, jI64(r, "startedAt"));
            sqlite3_bind_int64(ins.s, 3, jI64(r, "endedAt"));
            sqlite3_bind_int(ins.s, 4, jInt(r, "pagesTurned"));
            sqlite3_bind_double(ins.s, 5, r.value(QStringLiteral("startPercentage")).toDouble());
            sqlite3_bind_double(ins.s, 6, r.value(QStringLiteral("endPercentage")).toDouble());
            bindText(ins.s, 7, guid);
            sqlite3_bind_int(ins.s, 8, rDel);
            sqlite3_bind_int64(ins.s, 9, rUpd);
            if (!ins.done())
                return stats;
        } else {
            Stmt up(db, "UPDATE reading_sessions SET book_id = ?, started_at = ?, ended_at = ?,"
                        "  pages_turned = ?, start_percentage = ?, end_percentage = ?, deleted = ?,"
                        "  updated_at = ? WHERE guid = ?");
            sqlite3_bind_int64(up.s, 1, bookId);
            sqlite3_bind_int64(up.s, 2, jI64(r, "startedAt"));
            sqlite3_bind_int64(up.s, 3, jI64(r, "endedAt"));
            sqlite3_bind_int(up.s, 4, jInt(r, "pagesTurned"));
            sqlite3_bind_double(up.s, 5, r.value(QStringLiteral("startPercentage")).toDouble());
            sqlite3_bind_double(up.s, 6, r.value(QStringLiteral("endPercentage")).toDouble());
            sqlite3_bind_int(up.s, 7, rDel);
            sqlite3_bind_int64(up.s, 8, rUpd);
            bindText(up.s, 9, guid);
            if (!up.done())
                return stats;
        }
        tally(rDel);
    }

    // book_collections identity = (collection_id, book_id); mint a guid on insert (none on wire)
    if (!tx.restart())
        return stats;
    for (const QJsonObject &r : bookCollections) {
        const qint64 collectionId = idByGuid(db, "collections", jStr(r, "collectionGuid"));
        const qint64 bookId = bookIdForDigest(db, jStr(r, "bookDigest"));
        if (collectionId < 0 || bookId < 0)
            continue;
        const qint64 rUpd = jI64(r, "updatedAt");
        const int rDel = jInt(r, "deleted");
        LocalMeta m;
        {
            Stmt q(db, "SELECT updated_at, deleted FROM book_collections"
                       "  WHERE collection_id = ? AND book_id = ?");
            sqlite3_bind_int64(q.s, 1, collectionId);
            sqlite3_bind_int64(q.s, 2, bookId);
            if (q.row()) {
                m.exists = true;
                m.updatedAt = sqlite3_column_int64(q.s, 0);
                m.deleted = sqlite3_column_int(q.s, 1);
            }
        }
        if (!shouldApply(m, rUpd, rDel))
            continue;
        if (!m.exists) {
            Stmt ins(db, "INSERT INTO book_collections(collection_id, book_id, guid, deleted,"
                         "  updated_at) VALUES(?, ?, ?, ?, ?)");
            const QByteArray g = newGuid();
            sqlite3_bind_int64(ins.s, 1, collectionId);
            sqlite3_bind_int64(ins.s, 2, bookId);
            bindText(ins.s, 3, g);
            sqlite3_bind_int(ins.s, 4, rDel);
            sqlite3_bind_int64(ins.s, 5, rUpd);
            if (!ins.done())
                return stats;
        } else {
            Stmt up(db, "UPDATE book_collections SET deleted = ?, updated_at = ?"
                        "  WHERE collection_id = ? AND book_id = ?");
            sqlite3_bind_int(up.s, 1, rDel);
            sqlite3_bind_int64(up.s, 2, rUpd);
            sqlite3_bind_int64(up.s, 3, collectionId);
            sqlite3_bind_int64(up.s, 4, bookId);
            if (!up.done())
                return stats;
        }
        tally(rDel);
    }

    // A live book with no local file still needs its content fetched (fresh insert OR a revived
    // tombstone whose file we never had). Scan once — captures both without per-row bookkeeping.
    {
        Stmt q(db, "SELECT digest FROM books WHERE deleted = 0"
                   "  AND (file_path IS NULL OR file_path = '')");
        while (q.row())
            stats.missingDigests.append(colText(q.s, 0));
    }
    stats.booksNeeded = int(stats.missingDigests.size());

    // Earlier per-table segments are already committed (Tx::restart), so a failed FINAL
    // commit rolls back only the last segment. Still return the tallied stats: an overcount
    // merely triggers a spurious view reload, while zeros would hide durably-merged rows from
    // open views forever (their local copies now win LWW, so no later run re-reports them).
    tx.commit();
    return stats;
}

void SyncStore::attachBookFile(const QString &digest, const QString &filePath,
                               const QString &coverPath, qint64 sizeBytes)
{
    sqlite3 *db = m_storage->handle();
    Tx tx(db);
    if (!tx.active)
        return;
    // Local, device-specific columns only (file_path/cover_path/size_bytes) — NOT synced, so no
    // updated_at bump (that would spuriously win the next LWW round).
    Stmt q(db, "UPDATE books SET file_path = ?, cover_path = ?, size_bytes = ? WHERE digest = ?");
    bindText(q.s, 1, filePath.toUtf8());
    if (coverPath.isEmpty())
        sqlite3_bind_null(q.s, 2);
    else
        bindText(q.s, 2, coverPath.toUtf8());
    sqlite3_bind_int64(q.s, 3, sizeBytes);
    bindText(q.s, 4, digest.toUtf8());
    if (q.done())
        tx.commit();
}

QStringList SyncStore::localBookDigestsWithFile()
{
    QStringList out;
    Stmt q(m_storage->handle(), "SELECT digest FROM books WHERE deleted = 0"
                                "  AND file_path IS NOT NULL AND file_path <> ''");
    while (q.row())
        out.append(colText(q.s, 0));
    return out;
}

QString SyncStore::filePathForDigest(const QString &digest)
{
    Stmt q(m_storage->handle(), "SELECT file_path FROM books WHERE digest = ?");
    bindText(q.s, 1, digest.toUtf8());
    return q.row() ? colText(q.s, 0) : QString();
}

QString SyncStore::extForDigest(const QString &digest)
{
    Stmt q(m_storage->handle(), "SELECT format FROM books WHERE digest = ?");
    bindText(q.s, 1, digest.toUtf8());
    if (!q.row())
        return QString();
    return sqlite3_column_int(q.s, 0) == 1 ? QStringLiteral("epub") : QStringLiteral("pdf");
}
