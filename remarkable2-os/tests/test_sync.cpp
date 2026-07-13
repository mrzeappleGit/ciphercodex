// Host-side SyncStore tests, plain assert(), no framework, NO network. Two independent Storage DBs
// (A and B) in temp dirs stand in for two devices; we export a snapshot from one and applyMerged it
// into the other, then assert the live rows converge. Covers the phase3b-contracts crux: LWW,
// delete-propagation, book-by-digest identity, tombstone-vs-edit, and the missing-parent guard.
// PDFium is NOT linked (library.cpp's cover path compiles out), so books import with a filename
// title — exactly as test_library does. Qt6Core + sqlite3 only.
#include "library/library.h"
#include "storage/storage.h"
#include "sync/syncstore.h"

#include <sqlite3.h>

#include <QByteArray>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonObject>
#include <QJsonValue>
#include <QTemporaryDir>

#include <cassert>
#include <cstdio>

static void writeFile(const QString &path, const QByteArray &bytes)
{
    QFile f(path);
    assert(f.open(QIODevice::WriteOnly));
    assert(f.write(bytes) == bytes.size());
    f.close();
}

static QString scalarText(sqlite3 *db, const QString &sql)
{
    sqlite3_stmt *q = nullptr;
    assert(sqlite3_prepare_v2(db, sql.toUtf8().constData(), -1, &q, nullptr) == SQLITE_OK);
    QString r;
    if (sqlite3_step(q) == SQLITE_ROW && sqlite3_column_type(q, 0) != SQLITE_NULL)
        r = QString::fromUtf8(reinterpret_cast<const char *>(sqlite3_column_text(q, 0)));
    sqlite3_finalize(q);
    return r;
}

static long long scalarInt(sqlite3 *db, const QString &sql)
{
    sqlite3_stmt *q = nullptr;
    assert(sqlite3_prepare_v2(db, sql.toUtf8().constData(), -1, &q, nullptr) == SQLITE_OK);
    long long r = -1;
    if (sqlite3_step(q) == SQLITE_ROW)
        r = sqlite3_column_int64(q, 0);
    sqlite3_finalize(q);
    return r;
}

static void runSql(sqlite3 *db, const QString &sql)
{
    assert(sqlite3_exec(db, sql.toUtf8().constData(), nullptr, nullptr, nullptr) == SQLITE_OK);
}

static bool pointsEqual(const QVector<StrokePoint> &a, const QVector<StrokePoint> &b)
{
    if (a.size() != b.size())
        return false;
    for (int i = 0; i < a.size(); ++i) {
        const StrokePoint &x = a[i];
        const StrokePoint &y = b[i];
        if (x.x != y.x || x.y != y.y || x.pressure != y.pressure || x.tiltX != y.tiltX
            || x.tiltY != y.tiltY || x.tMs != y.tMs)
            return false;
    }
    return true;
}

struct Dev {
    QTemporaryDir *dir;
    Storage *st;
    Library *lib;
    SyncStore *ss;
    QString id;
    sqlite3 *db() const { return st->handle(); }
};

static Dev makeDev(const QString &id)
{
    Dev d;
    d.dir = new QTemporaryDir();
    assert(d.dir->isValid());
    QString err;
    d.st = Storage::open(d.dir->path(), &err);
    assert(d.st);
    d.lib = new Library(d.st, d.dir->path());
    d.ss = new SyncStore(d.st);
    d.id = id;
    return d;
}

static void freeDev(Dev &d)
{
    delete d.ss;
    delete d.lib;
    delete d.st;
    delete d.dir;
}

// Import a book from raw bytes via the real import path; return its row (digest content-addressed).
static BookRow importBook(Dev &d, const QString &name, const QByteArray &content)
{
    writeFile(d.dir->path() + "/inbox/" + name, content);
    d.lib->importInbox();
    const QString base = QFileInfo(name).completeBaseName();
    for (const BookRow &b : d.lib->books())
        if (b.title == base)
            return b;
    assert(false);
    return BookRow{};
}

// snapA -> applyMerged into B (one-liner used all over).
static SyncStore::MergeStats push(Dev &from, Dev &to)
{
    return to.ss->applyMerged({from.ss->exportSnapshot(from.id)}, to.id);
}

static void testConvergence()
{
    Dev A = makeDev("device-a");
    Dev B = makeDev("device-b");

    // A: a notebook with one page and a three-point stroke.
    const qint64 nb = A.st->createNotebook("Notes");
    const qint64 pg = A.st->createPage(nb);
    StrokeData sd;
    sd.pageId = pg;
    sd.tool = 0;
    sd.baseWidth = 2.5f;
    sd.pts = {{1.0f, 2.0f, 100, 3, 4, 10},
              {5.5f, 6.5f, 200, -1, -2, 20},
              {9.0f, 9.25f, 300, 0, 0, 30}};
    assert(A.st->appendStroke(sd) > 0);

    // A: a book (fake content) + a highlight with a note.
    const BookRow bkA = importBook(A, "Doc.pdf", QByteArray(3000, '\x07'));
    assert(A.lib->addHighlight(bkA.id, 1, 10, 25, "the quoted text", "my note") > 0);

    // A -> B.
    const SyncStore::MergeStats s1 = push(A, B);
    assert(s1.entitiesApplied > 0);

    // Same notebook guid, one page, one stroke with byte-identical points.
    assert(B.st->notebooks().size() == 1);
    assert(scalarText(B.db(), "SELECT guid FROM notebooks")
           == scalarText(A.db(), "SELECT guid FROM notebooks"));
    const qint64 bnb = B.st->notebooks()[0].id;
    const QVector<PageInfo> bpages = B.st->pages(bnb);
    assert(bpages.size() == 1);
    const QVector<StrokeData> bstrokes = B.st->strokes(bpages[0].id);
    assert(bstrokes.size() == 1);
    assert(pointsEqual(bstrokes[0].pts, sd.pts));
    assert(bstrokes[0].baseWidth == 2.5f && bstrokes[0].tool == 0);

    // One book (by digest) + the highlight, text/note/spans preserved.
    assert(scalarInt(B.db(), "SELECT COUNT(*) FROM books WHERE deleted=0") == 1);
    const BookRow bkB = B.lib->books()[0];
    assert(bkB.digest == bkA.digest);
    const QVector<Highlight> bhl = B.lib->highlights(bkB.id);
    assert(bhl.size() == 1);
    assert(bhl[0].text == "the quoted text" && bhl[0].note == "my note");
    assert(bhl[0].spineIndex == 1 && bhl[0].startChar == 10 && bhl[0].endChar == 25);
    // B lacks the file -> its digest is flagged for the engine to fetch, and file_path is empty.
    assert(s1.missingDigests.contains(bkA.digest));
    assert(bkB.filePath.isEmpty());

    // B -> A is idempotent: no duplicates, A's rows (incl. its real file_path) unchanged.
    const SyncStore::MergeStats s2 = push(B, A);
    assert(s2.entitiesApplied == 0 && s2.tombstonesApplied == 0);
    assert(A.st->notebooks().size() == 1);
    assert(scalarInt(A.db(), "SELECT COUNT(*) FROM books") == 1);
    assert(scalarInt(A.db(), "SELECT COUNT(*) FROM highlights WHERE deleted=0") == 1);
    const QVector<StrokeData> astrokes = A.st->strokes(pg);
    assert(astrokes.size() == 1 && pointsEqual(astrokes[0].pts, sd.pts));
    assert(!A.lib->books()[0].filePath.isEmpty());  // empty-file remote book must not wipe local path
    assert(!s2.missingDigests.contains(bkA.digest));

    freeDev(A);
    freeDev(B);
    printf("  convergence OK\n");
}

static void testLww()
{
    Dev A = makeDev("device-a");
    Dev B = makeDev("device-b");
    const BookRow bkA = importBook(A, "Doc.pdf", QByteArray(3000, '\x07'));
    assert(A.lib->addHighlight(bkA.id, 0, 0, 5, "txt", "orig") > 0);
    push(A, B);  // B now shares the highlight's guid + the book digest

    const QString g = scalarText(A.db(), "SELECT guid FROM highlights");
    assert(!g.isEmpty());
    // Both edit the SAME highlight's note at different updated_at; B is newer.
    runSql(A.db(), QStringLiteral("UPDATE highlights SET note='A-note',updated_at=1000 WHERE guid='%1'").arg(g));
    runSql(B.db(), QStringLiteral("UPDATE highlights SET note='B-note',updated_at=2000 WHERE guid='%1'").arg(g));

    push(A, B);  // A(1000) < B(2000): B keeps B-note
    push(B, A);  // B(2000) > A(1000): A takes B-note

    assert(scalarText(A.db(), "SELECT note FROM highlights") == "B-note");
    assert(scalarText(B.db(), "SELECT note FROM highlights") == "B-note");
    assert(scalarInt(A.db(), "SELECT updated_at FROM highlights") == 2000);

    freeDev(A);
    freeDev(B);
    printf("  lww OK\n");
}

static void testDeletePropagation()
{
    Dev A = makeDev("device-a");
    Dev B = makeDev("device-b");
    const qint64 nb = A.st->createNotebook("N");
    const qint64 pg = A.st->createPage(nb);
    StrokeData sd;
    sd.pageId = pg;
    sd.pts = {{1.0f, 1.0f, 10, 0, 0, 1}};
    assert(A.st->appendStroke(sd) > 0);

    push(A, B);  // B has the notebook/page/stroke live
    assert(B.st->notebooks().size() == 1);
    const qint64 bnb = B.st->notebooks()[0].id;
    assert(B.st->pages(bnb).size() == 1);
    assert(B.st->strokes(B.st->pages(bnb)[0].id).size() == 1);

    // A soft-deletes the notebook (cascades tombstones to pages + strokes, emptying blobs).
    A.st->deleteNotebook(nb);
    assert(A.st->notebooks().isEmpty());

    push(A, B);  // delete propagates
    assert(B.st->notebooks().isEmpty());
    assert(B.st->pages(bnb).isEmpty());
    assert(scalarInt(B.db(), "SELECT deleted FROM notebooks") == 1);
    assert(scalarInt(B.db(), "SELECT deleted FROM pages") == 1);
    assert(scalarInt(B.db(), "SELECT deleted FROM strokes") == 1);
    assert(scalarInt(B.db(), "SELECT LENGTH(points) FROM strokes") == 0);  // blob travelled empty

    // A later B -> A sync must NOT resurrect (equal-or-older tombstone stays deleted).
    push(B, A);
    assert(A.st->notebooks().isEmpty());
    assert(scalarInt(A.db(), "SELECT deleted FROM notebooks") == 1);

    freeDev(A);
    freeDev(B);
    printf("  delete-propagation OK\n");
}

static void testWriteResurrectsTombstonedPage()
{
    // A peer's delete can land mid-session while the page is open locally (auto-sync):
    // the next stroke written must resurrect the page+notebook — never leave new ink
    // invisible on a dead page — and the un-delete must win LWW on the peer too.
    Dev A = makeDev("device-a");
    Dev B = makeDev("device-b");
    const qint64 nb = A.st->createNotebook("N");
    const qint64 pg = A.st->createPage(nb);
    push(A, B);
    const qint64 bnb = B.st->notebooks()[0].id;
    B.st->deleteNotebook(bnb);
    push(B, A);  // the remote delete lands while the page is "open" on A
    assert(A.st->notebooks().isEmpty());

    // Backdate both devices' tombstones so the resurrect's now() strictly wins LWW even
    // when this test runs inside one millisecond.
    for (sqlite3 *db : {A.db(), B.db()}) {
        runSql(db, QStringLiteral("UPDATE notebooks SET updated_at = updated_at - 10"));
        runSql(db, QStringLiteral("UPDATE pages SET updated_at = updated_at - 10"));
    }

    StrokeData sd;
    sd.pageId = pg;
    sd.pts = {{1.0f, 1.0f, 10, 0, 0, 1}};
    assert(A.st->appendStroke(sd) > 0);
    assert(A.st->notebooks().size() == 1);  // notebook back
    assert(A.st->pages(nb).size() == 1);    // page back
    assert(A.st->strokes(pg).size() == 1);  // the new ink is alive

    push(A, B);  // resurrection propagates
    assert(B.st->notebooks().size() == 1);
    assert(B.st->strokes(B.st->pages(bnb)[0].id).size() == 1);

    freeDev(A);
    freeDev(B);
    printf("  write-resurrects-tombstone OK\n");
}

static void testBookByDigest()
{
    Dev A = makeDev("device-a");
    Dev B = makeDev("device-b");
    // Identical bytes -> identical content digest, but each device mints its own book guid.
    const QByteArray content = QByteArray(4000, '\x11') + QByteArray(50, '\x22');
    const BookRow bkA = importBook(A, "Same.pdf", content);
    const BookRow bkB = importBook(B, "Same.pdf", content);
    assert(bkA.digest == bkB.digest);
    const QString gA = scalarText(A.db(), "SELECT guid FROM books");
    const QString gB = scalarText(B.db(), "SELECT guid FROM books");
    assert(!gA.isEmpty() && gA != gB);

    assert(A.lib->addHighlight(bkA.id, 0, 0, 4, "word", "") > 0);

    const SyncStore::MergeStats s = push(A, B);
    // Merged by digest, not guid: exactly one book row, B keeps its own guid + local file.
    assert(scalarInt(B.db(), "SELECT COUNT(*) FROM books") == 1);
    assert(scalarText(B.db(), "SELECT guid FROM books") == gB);
    assert(!s.missingDigests.contains(bkA.digest));  // B has the file already
    // A's highlight (on the same digest) surfaces on B.
    const QVector<Highlight> bhl = B.lib->highlights(bkB.id);
    assert(bhl.size() == 1 && bhl[0].text == "word");

    freeDev(A);
    freeDev(B);
    printf("  book-by-digest OK\n");
}

static void testTombstoneVsEdit()
{
    Dev A = makeDev("device-a");
    Dev B = makeDev("device-b");
    const BookRow bkA = importBook(A, "Doc.pdf", QByteArray(3000, '\x07'));
    assert(A.lib->addHighlight(bkA.id, 0, 0, 5, "txt", "note") > 0);
    push(A, B);

    const QString g = scalarText(A.db(), "SELECT guid FROM highlights");
    // Older edit on A, newer delete on B: the delete must win on both sides.
    runSql(A.db(), QStringLiteral("UPDATE highlights SET note='edited',updated_at=1000 WHERE guid='%1'").arg(g));
    runSql(B.db(), QStringLiteral("UPDATE highlights SET deleted=1,updated_at=2000 WHERE guid='%1'").arg(g));

    push(A, B);  // A edit(1000) < B delete(2000): stays deleted on B
    push(B, A);  // B delete(2000) > A edit(1000): A becomes deleted

    const qint64 bBook = B.lib->books()[0].id;
    assert(B.lib->highlights(bBook).isEmpty());
    assert(A.lib->highlights(bkA.id).isEmpty());
    assert(scalarInt(A.db(), "SELECT deleted FROM highlights") == 1);
    assert(scalarInt(A.db(), "SELECT updated_at FROM highlights") == 2000);

    freeDev(A);
    freeDev(B);
    printf("  tombstone-vs-edit OK\n");
}

static void testMissingParent()
{
    Dev C = makeDev("device-c");
    // A snapshot carrying a stroke whose page is absent locally AND absent from the snapshot.
    QJsonObject snap;
    snap.insert("deviceId", "device-x");
    QJsonArray strokes;
    strokes.append(QJsonObject{
        {"guid", "00000000000000000000000000000001"},
        {"pageGuid", "deadbeefdeadbeefdeadbeefdeadbeef"},  // no such page
        {"tool", 0},
        {"baseWidth", 2.0},
        {"points_b64", ""},
        {"createdAt", 5},
        {"deleted", 0},
        {"updatedAt", 5}});
    snap.insert("strokes", strokes);

    const SyncStore::MergeStats s = C.ss->applyMerged({snap}, C.id);
    assert(scalarInt(C.db(), "SELECT COUNT(*) FROM strokes") == 0);  // orphan skipped, not inserted
    assert(s.entitiesApplied == 0 && s.tombstonesApplied == 0);

    freeDev(C);
    printf("  missing-parent OK\n");
}

static void testPageTextSync()
{
    Dev A = makeDev("device-a");   // stands in for the Android author
    Dev B = makeDev("device-b");
    const qint64 nb = A.st->createNotebook("N");
    const qint64 pg = A.st->createPage(nb);
    push(A, B);
    const QString pgGuid = scalarText(A.db(), "SELECT guid FROM pages");
    runSql(A.db(), QStringLiteral(
        "INSERT INTO page_text(page_id, text, source_stamp, guid, deleted, updated_at)"
        " VALUES(%1, 'hello world', 7, 'ptguid0000000000000000000000000a', 0, 1000)").arg(pg));

    push(A, B);   // text follows the page
    assert(scalarText(B.db(), "SELECT text FROM page_text") == "hello world");
    assert(scalarInt(B.db(), "SELECT source_stamp FROM page_text") == 7);

    // LWW: newer text on A replaces B's copy
    runSql(A.db(), QStringLiteral("UPDATE page_text SET text='newer', updated_at=2000"));
    push(A, B);
    assert(scalarText(B.db(), "SELECT text FROM page_text") == "newer");

    // missing parent: a text row for an unknown page is skipped, not inserted
    QJsonObject snap;
    snap.insert("deviceId", "ghost");
    QJsonArray arr;
    QJsonObject r;
    r.insert("pageGuid", "nosuchpage0000000000000000000000");
    r.insert("text", "orphan"); r.insert("sourceStamp", 1);
    r.insert("deleted", 0); r.insert("updatedAt", 9999);
    arr.append(r); snap.insert("pageTexts", arr);
    B.ss->applyMerged({snap}, B.id);
    assert(scalarInt(B.db(), "SELECT COUNT(*) FROM page_text") == 1);

    // carried finding: deletePage tombstones page_text too (Task 9 review, Task 10 fix)
    A.st->deletePage(pg);
    push(A, B);
    assert(scalarInt(B.db(), "SELECT deleted FROM page_text") == 1);

    freeDev(A); freeDev(B);
    printf("  page-text OK\n");
}

int main()
{
    testConvergence();
    testLww();
    testDeletePropagation();
    testWriteResurrectsTombstonedPage();
    testBookByDigest();
    testTombstoneVsEdit();
    testMissingParent();
    testPageTextSync();
    printf("ALL SYNC TESTS PASSED\n");
    return 0;
}
