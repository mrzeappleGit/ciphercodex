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

    // deleteBook: row + file gone, and FK cascade removed the progress row.
    const QString pdfFile = pdf->filePath;
    assert(QFile::exists(pdfFile));
    lib.deleteBook(pdfId);
    all = lib.books();
    assert(all.size() == 1 && all.first().id == epubId);
    assert(!lib.progress(pdfId).exists);                   // ON DELETE CASCADE
    assert(!QFile::exists(pdfFile));                       // on-disk file unlinked

    delete st;
    printf("ALL LIBRARY TESTS PASSED\n");
    return 0;
}
