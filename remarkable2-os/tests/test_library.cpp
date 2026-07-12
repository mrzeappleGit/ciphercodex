// Host-side Library tests, plain assert(), no framework. PDFium is NOT linked here, so
// the PDF cover/title path (guarded by CCX_HAVE_PDFIUM) is compiled out — PDFs import with a
// filename-derived title, exercising the library core end to end without a real PDF renderer.
#include "library/library.h"
#include "storage/storage.h"

#include <sqlite3.h>

#include <QByteArray>
#include <QFile>
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

static const BookRow *findByTitle(const QVector<BookRow> &v, const QString &title)
{
    for (const BookRow &b : v)
        if (b.title == title)
            return &b;
    return nullptr;
}

// Raw single-column reads (bypass the deleted=0 API filter) to inspect tombstones directly.
static long long rawInt(sqlite3 *db, const char *sql, qint64 id)
{
    sqlite3_stmt *q = nullptr;
    assert(sqlite3_prepare_v2(db, sql, -1, &q, nullptr) == SQLITE_OK);
    sqlite3_bind_int64(q, 1, id);
    const long long v = sqlite3_step(q) == SQLITE_ROW ? sqlite3_column_int64(q, 0) : -1;
    sqlite3_finalize(q);
    return v;
}

static QByteArray rawText(sqlite3 *db, const char *sql, qint64 id)
{
    sqlite3_stmt *q = nullptr;
    assert(sqlite3_prepare_v2(db, sql, -1, &q, nullptr) == SQLITE_OK);
    sqlite3_bind_int64(q, 1, id);
    QByteArray s;
    if (sqlite3_step(q) == SQLITE_ROW)
        s = reinterpret_cast<const char *>(sqlite3_column_text(q, 0));
    sqlite3_finalize(q);
    return s;
}

int main()
{
    QTemporaryDir tmp;
    assert(tmp.isValid());
    QString err;
    Storage *st = Storage::open(tmp.path(), &err);
    assert(st);

    Library lib(st, tmp.path());

    // Two distinct >1KB inbox files (distinct bytes => distinct partial-MD5 digests).
    const QString inbox = tmp.path() + "/inbox";
    writeFile(inbox + "/sample-book.pdf", QByteArray(2000, '\x01') + QByteArray(200, '\xAB'));
    writeFile(inbox + "/My Novel.epub", QByteArray(2000, '\x02') + QByteArray(200, '\xCD'));

    // First import: both new.
    Library::ImportSummary s1 = lib.importInbox();
    assert(s1.imported == 2 && s1.duplicates == 0 && s1.failed == 0);

    QVector<BookRow> all = lib.books();
    assert(all.size() == 2);
    const BookRow *pdf = findByTitle(all, "sample-book");   // filename title (no PDFium)
    const BookRow *epub = findByTitle(all, "My Novel");
    assert(pdf && epub);
    assert(pdf->format == 0 && epub->format == 1);
    assert(pdf->coverPath.isEmpty());                       // no cover on host
    assert(pdf->lastOpenedAt == 0 && epub->lastOpenedAt == 0);
    assert(pdf->sizeBytes == 2200);
    // Files landed under books/<digest>.<ext>.
    assert(QFile::exists(pdf->filePath));
    assert(pdf->filePath.endsWith(pdf->digest + ".pdf"));
    assert(QFile::exists(epub->filePath));

    // Re-run: same source files still in inbox => both dedupe as duplicates, nothing modified.
    Library::ImportSummary s2 = lib.importInbox();
    assert(s2.imported == 0 && s2.duplicates == 2 && s2.failed == 0);
    assert(lib.books().size() == 2);

    const qint64 pdfId = pdf->id;
    const qint64 epubId = epub->id;

    // markOpened re-sorts: opened book comes first (last_opened_at IS NULL sorts after non-null).
    lib.markOpened(epubId);
    assert(lib.books().first().id == epubId);

    // Progress roundtrip.
    assert(!lib.progress(pdfId).exists);
    lib.saveProgress(pdfId, 3, 0, 0.3);
    Library::Progress p = lib.progress(pdfId);
    assert(p.exists && p.spineIndex == 3 && p.charOffset == 0);
    assert(p.percentage > 0.29 && p.percentage < 0.31);
    assert(p.syncedAt == 0);                               // fresh row is dirty (NULL synced_at)

    // saveProgress upsert preserves synced_at (simulate a sync marker, then update position).
    {
        sqlite3_stmt *q = nullptr;
        assert(sqlite3_prepare_v2(st->handle(),
                                  "UPDATE progress SET synced_at = 12345 WHERE book_id = ?",
                                  -1, &q, nullptr) == SQLITE_OK);
        sqlite3_bind_int64(q, 1, pdfId);
        assert(sqlite3_step(q) == SQLITE_DONE);
        sqlite3_finalize(q);
    }
    assert(lib.progress(pdfId).syncedAt == 12345);
    lib.saveProgress(pdfId, 7, 0, 0.7);
    p = lib.progress(pdfId);
    assert(p.spineIndex == 7 && p.percentage > 0.69 && p.percentage < 0.71);
    assert(p.syncedAt == 12345);                           // preserved across the upsert

    // Bookmarks CRUD.
    assert(lib.bookmarks(pdfId).isEmpty());
    const qint64 bmId = lib.addBookmark(pdfId, 7, 0, 0.7, QStringLiteral("chapter 2"));
    assert(bmId > 0);
    QVector<Bookmark> bms = lib.bookmarks(pdfId);
    assert(bms.size() == 1 && bms[0].id == bmId && bms[0].label == QStringLiteral("chapter 2"));
    assert(bms[0].spineIndex == 7);
    lib.deleteBookmark(bmId);
    assert(lib.bookmarks(pdfId).isEmpty());

    // deleteBook: soft-delete the row + child rows; the content-addressed file is KEPT for sync.
    sqlite3 *h = st->handle();
    const QString pdfFile = pdf->filePath;
    assert(QFile::exists(pdfFile));
    lib.saveProgress(pdfId, 1, 0, 0.1);                    // give the pdf a progress row to cascade
    assert(lib.progress(pdfId).exists);
    lib.deleteBook(pdfId);
    all = lib.books();
    assert(all.size() == 1 && all.first().id == epubId);   // pdf tombstoned -> hidden
    assert(!lib.progress(pdfId).exists);                   // child soft-deleted (filtered by API)
    assert(rawInt(h, "SELECT deleted FROM books WHERE id = ?", pdfId) == 1);     // row still there
    assert(rawInt(h, "SELECT deleted FROM progress WHERE book_id = ?", pdfId) == 1);  // cascaded
    assert(QFile::exists(pdfFile));                        // file KEPT (was: unlinked pre-v3)

    // Full cascade + tombstone-not-hard-delete + un-delete-on-reimport, on the surviving epub.
    lib.saveProgress(epubId, 2, 0, 0.2);
    const qint64 ebm = lib.addBookmark(epubId, 2, 0, 0.2, QStringLiteral("b"));
    const qint64 ehl = lib.addHighlight(epubId, 0, 0, 4, QStringLiteral("word"));
    assert(ebm > 0 && ehl > 0);
    assert(!rawText(h, "SELECT guid FROM highlights WHERE id = ?", ehl).isEmpty());  // guid stamped
    lib.deleteBook(epubId);
    assert(lib.books().isEmpty());                         // both books now tombstoned
    assert(!lib.progress(epubId).exists);
    assert(lib.bookmarks(epubId).isEmpty());
    assert(lib.highlights(epubId).isEmpty());
    assert(rawInt(h, "SELECT deleted FROM books WHERE id = ?", epubId) == 1);
    assert(rawInt(h, "SELECT deleted FROM bookmarks WHERE id = ?", ebm) == 1);   // cascaded
    assert(rawInt(h, "SELECT deleted FROM highlights WHERE id = ?", ehl) == 1);  // cascaded

    // Re-import the still-present inbox sources: both tombstoned books revive in place (no dup rows).
    Library::ImportSummary rev = lib.importInbox();
    assert(rev.imported == 2 && rev.duplicates == 0 && rev.failed == 0);
    assert(lib.books().size() == 2);
    assert(rawInt(h, "SELECT deleted FROM books WHERE id = ?", epubId) == 0);  // un-deleted in place
    // Count ALL book rows (live + tombstoned): still exactly 2 -> revive updated in place, no dup insert.
    assert(rawInt(h, "SELECT COUNT(*) FROM books WHERE deleted >= ?", 0) == 2);

    delete st;
    printf("ALL LIBRARY TESTS PASSED\n");
    return 0;
}
