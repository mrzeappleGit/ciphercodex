// Host-side storage tests, plain assert(), no framework. Three modes:
//   (none)                          run the assert suite
//   --powerloss-child <dbdir>       append strokes forever, print committed ids
//   --powerloss-verify <dbdir> <idsfile>  integrity_check + every reported id exists
#include "storage.h"

#include <sqlite3.h>

#include <QTemporaryDir>

#include <cassert>
#include <cstdio>
#include <cstring>

static StrokeData makeStroke(qint64 pageId)
{
    StrokeData s;
    s.pageId = pageId;
    s.tool = 0;
    s.baseWidth = 2.25f;
    s.pts = {
        {0.0f, 0.0f, 0, -9000, 9000, 0},
        {0.123456789f, 0.987654321f, 4095, 123, -456, 16},
        {1.0f, 1.0f, 2048, 0, 0, 4294967295u},
    };
    return s;
}

static void assertStrokeEqual(const StrokeData &a, const StrokeData &b)
{
    assert(a.id == b.id);
    assert(a.pageId == b.pageId);
    assert(a.tool == b.tool);
    assert(std::memcmp(&a.baseWidth, &b.baseWidth, sizeof(float)) == 0);
    assert(a.pts.size() == b.pts.size());
    for (int i = 0; i < a.pts.size(); ++i) {
        // floats compared bitwise: BLOB encode/decode must be exact
        assert(std::memcmp(&a.pts[i].x, &b.pts[i].x, sizeof(float)) == 0);
        assert(std::memcmp(&a.pts[i].y, &b.pts[i].y, sizeof(float)) == 0);
        assert(a.pts[i].pressure == b.pts[i].pressure);
        assert(a.pts[i].tiltX == b.pts[i].tiltX);
        assert(a.pts[i].tiltY == b.pts[i].tiltY);
        assert(a.pts[i].tMs == b.pts[i].tMs);
    }
}

static NotebookInfo findNotebook(Storage *st, qint64 id)
{
    for (const NotebookInfo &n : st->notebooks())
        if (n.id == id)
            return n;
    assert(!"notebook not found");
    return {};
}

static int runAsserts()
{
    QTemporaryDir tmp;
    assert(tmp.isValid());
    QString err;

    // schema create + reopen (migrations must be idempotent)
    qint64 nb1;
    {
        Storage *st = Storage::open(tmp.path(), &err);
        assert(st);
        nb1 = st->createNotebook(QStringLiteral("First"));
        assert(nb1 > 0);
        delete st;
    }
    Storage *st = Storage::open(tmp.path(), &err);
    assert(st);
    assert(st->notebooks().size() == 1);
    assert(findNotebook(st, nb1).title == QStringLiteral("First"));
    assert(findNotebook(st, nb1).pageCount == 0);

    // notebook CRUD
    st->renameNotebook(nb1, QStringLiteral("Renamed"));
    assert(findNotebook(st, nb1).title == QStringLiteral("Renamed"));
    const qint64 nb2 = st->createNotebook(QStringLiteral("Second"));
    assert(nb2 > 0 && st->notebooks().size() == 2);

    // pages: seq appends at end
    const qint64 p1 = st->createPage(nb1);
    const qint64 p2 = st->createPage(nb1);
    QVector<PageInfo> pgs = st->pages(nb1);
    assert(pgs.size() == 2);
    assert(pgs[0].id == p1 && pgs[0].seq == 1);
    assert(pgs[1].id == p2 && pgs[1].seq == 2);
    assert(findNotebook(st, nb1).pageCount == 2);
    assert(st->pages(nb2).isEmpty());

    // stroke roundtrip + points BLOB exactness (extreme values in makeStroke)
    StrokeData s1 = makeStroke(p1);
    s1.id = st->appendStroke(s1);
    assert(s1.id > 0);
    StrokeData s2 = makeStroke(p1);
    s2.baseWidth = 3.5f;
    s2.id = st->appendStroke(s2);
    assert(s2.id > s1.id);
    QVector<StrokeData> back = st->strokes(p1);
    assert(back.size() == 2);
    assertStrokeEqual(back[0], s1);
    assertStrokeEqual(back[1], s2);
    assert(st->strokes(p2).isEmpty());

    // remove + restore preserves original ids (both report success)
    assert(st->removeStrokes({s1.id, s2.id}));
    assert(st->strokes(p1).isEmpty());
    QVector<StrokeData> toRestore{s1, s2};
    assert(st->restoreStrokes(toRestore));
    back = st->strokes(p1);
    assert(back.size() == 2);
    assert(back[0].id == s1.id && back[1].id == s2.id);
    assertStrokeEqual(back[0], s1);
    assertStrokeEqual(back[1], s2);

    // deletePage cascades its strokes
    st->deletePage(p2);
    assert(st->pages(nb1).size() == 1);

    // deleteNotebook cascades pages + strokes
    st->deleteNotebook(nb1);
    assert(st->pages(nb1).isEmpty());
    assert(st->strokes(p1).isEmpty());
    assert(st->notebooks().size() == 1);
    delete st;

    // cascade verified against the raw DB, not just the API
    const QByteArray path = (tmp.path() + QStringLiteral("/data.db")).toUtf8();
    sqlite3 *db = nullptr;
    assert(sqlite3_open_v2(path.constData(), &db, SQLITE_OPEN_READONLY, nullptr) == SQLITE_OK);
    sqlite3_stmt *q = nullptr;
    assert(sqlite3_prepare_v2(db, "SELECT (SELECT COUNT(*) FROM strokes),"
                                  " (SELECT COUNT(*) FROM pages),"
                                  " (SELECT version FROM schema_version)",
                              -1, &q, nullptr) == SQLITE_OK);
    assert(sqlite3_step(q) == SQLITE_ROW);
    assert(sqlite3_column_int(q, 0) == 0);
    assert(sqlite3_column_int(q, 1) == 0);
    assert(sqlite3_column_int(q, 2) == 1);
    sqlite3_finalize(q);
    sqlite3_close(db);

    printf("ALL STORAGE TESTS PASSED\n");
    return 0;
}

static int powerlossChild(const char *dir)
{
    QString err;
    Storage *st = Storage::open(QString::fromUtf8(dir), &err);
    if (!st) {
        fprintf(stderr, "child: open failed: %s\n", qPrintable(err));
        return 1;
    }
    const qint64 nb = st->createNotebook(QStringLiteral("powerloss"));
    const qint64 pg = st->createPage(nb);
    const StrokeData s = makeStroke(pg);
    for (int i = 0; i < 1000000; ++i) {  // parent SIGKILLs us long before this ends
        const qint64 id = st->appendStroke(s);
        if (id < 0)
            return 1;
        // printed only AFTER the commit returned — this line is the durability claim
        printf("%lld\n", static_cast<long long>(id));
        fflush(stdout);
    }
    return 0;
}

static int powerlossVerify(const char *dir, const char *idsFile)
{
    char path[4096];
    snprintf(path, sizeof path, "%s/data.db", dir);
    sqlite3 *db = nullptr;
    assert(sqlite3_open_v2(path, &db, SQLITE_OPEN_READWRITE, nullptr) == SQLITE_OK);

    sqlite3_stmt *q = nullptr;
    assert(sqlite3_prepare_v2(db, "PRAGMA integrity_check", -1, &q, nullptr) == SQLITE_OK);
    assert(sqlite3_step(q) == SQLITE_ROW);
    assert(std::strcmp(reinterpret_cast<const char *>(sqlite3_column_text(q, 0)), "ok") == 0);
    sqlite3_finalize(q);

    // tripwire: WAL is persistent in the DB file; a silent regression to a non-WAL
    // journal (or MEMORY) would make kill-based durability checks meaningless
    assert(sqlite3_prepare_v2(db, "PRAGMA journal_mode", -1, &q, nullptr) == SQLITE_OK);
    assert(sqlite3_step(q) == SQLITE_ROW);
    assert(std::strcmp(reinterpret_cast<const char *>(sqlite3_column_text(q, 0)), "wal") == 0);
    sqlite3_finalize(q);

    FILE *f = fopen(idsFile, "r");
    assert(f);
    assert(sqlite3_prepare_v2(db, "SELECT 1 FROM strokes WHERE id = ?", -1, &q, nullptr)
           == SQLITE_OK);
    long long id = 0, reported = 0;
    while (fscanf(f, "%lld", &id) == 1) {
        ++reported;
        sqlite3_bind_int64(q, 1, id);
        if (sqlite3_step(q) != SQLITE_ROW) {
            fprintf(stderr, "FAIL: committed stroke id %lld missing after crash\n", id);
            return 1;
        }
        sqlite3_reset(q);
    }
    fclose(f);
    sqlite3_finalize(q);

    assert(sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM strokes", -1, &q, nullptr) == SQLITE_OK);
    assert(sqlite3_step(q) == SQLITE_ROW);
    const long long inDb = sqlite3_column_int64(q, 0);
    sqlite3_finalize(q);
    sqlite3_close(db);

    // Child prints after COMMIT, so SIGKILL can land between commit and print:
    // the DB may hold at most one stroke more than was reported, never fewer.
    if (inDb < reported || inDb > reported + 1) {
        fprintf(stderr, "FAIL: reported=%lld in_db=%lld\n", reported, inDb);
        return 1;
    }
    printf("POWERLOSS VERIFY OK: reported=%lld in_db=%lld\n", reported, inDb);
    return 0;
}

int main(int argc, char **argv)
{
    if (argc >= 3 && std::strcmp(argv[1], "--powerloss-child") == 0)
        return powerlossChild(argv[2]);
    if (argc >= 4 && std::strcmp(argv[1], "--powerloss-verify") == 0)
        return powerlossVerify(argv[2], argv[3]);
    return runAsserts();
}
