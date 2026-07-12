// Host-side highlight/Kept tests, plain assert(), no framework. Books are created via the import
// path (author is NULL on import); highlights exercise CRUD + spine filter + allHighlights JOIN,
// and keptMarkdown is asserted byte-for-byte against the Android export. Qt6Core + sqlite3, no GUI.
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

static void execSql(sqlite3 *db, const char *sql)
{
    assert(sqlite3_exec(db, sql, nullptr, nullptr, nullptr) == SQLITE_OK);
}

int main()
{
    QTemporaryDir tmp;
    assert(tmp.isValid());
    QString err;
    Storage *st = Storage::open(tmp.path(), &err);
    assert(st);
    Library lib(st, tmp.path());

    // Two books via the import path (Alpha.pdf -> format 0, Beta.epub -> format 1).
    const QString inbox = tmp.path() + "/inbox";
    writeFile(inbox + "/Alpha.pdf", QByteArray(2000, '\x01') + QByteArray(200, '\xAB'));
    writeFile(inbox + "/Beta.epub", QByteArray(2000, '\x02') + QByteArray(200, '\xCD'));
    assert(lib.importInbox().imported == 2);

    qint64 idA = -1, idB = -1;
    for (const BookRow &b : lib.books()) {
        if (b.title == "Alpha")
            idA = b.id;
        if (b.title == "Beta")
            idB = b.id;
    }
    assert(idA > 0 && idB > 0);
    // Import leaves author NULL; give A an author, keep B blank (tests the no-separator header path).
    {
        char sql[128];
        std::snprintf(sql, sizeof sql, "UPDATE books SET author='Ann Author' WHERE id=%lld",
                      (long long)idA);
        execSql(st->handle(), sql);
    }

    // ---- known Kept set: 2 books, 3 highlights, 1 note ----
    const qint64 hA1 = lib.addHighlight(idA, 0, 0, 10, "First line one\nline two");
    const qint64 hA2 = lib.addHighlight(idA, 0, 20, 30, "Second highlight");
    const qint64 hB1 = lib.addHighlight(idB, 0, 0, 5, "Third\nquote", "a note\nwrapped");
    assert(hA1 > 0 && hA2 > 0 && hB1 > 0);
    // Pin created_at so the newest-first order is deterministic regardless of insert timing.
    {
        char sql[192];
        std::snprintf(sql, sizeof sql,
                      "UPDATE highlights SET created_at=CASE id WHEN %lld THEN 300 WHEN %lld THEN 200"
                      " WHEN %lld THEN 100 END",
                      (long long)hA1, (long long)hA2, (long long)hB1);
        execSql(st->handle(), sql);
    }

    // allHighlights JOIN: newest first, book fields populated.
    QVector<KeptHighlight> kept = lib.allHighlights();
    assert(kept.size() == 3);
    assert(kept[0].h.id == hA1 && kept[1].h.id == hA2 && kept[2].h.id == hB1);
    assert(kept[0].bookTitle == "Alpha" && kept[0].bookAuthor == "Ann Author");
    assert(kept[0].format == 0 && kept[0].filePath.endsWith(".pdf"));
    assert(kept[2].bookTitle == "Beta" && kept[2].bookAuthor.isEmpty());
    assert(kept[2].format == 1);
    assert(kept[2].h.note == "a note\nwrapped");  // stored verbatim; newline collapsed only on export

    // Exact grouped-Markdown bytes (byte-identical to Android KeptScreen.shareHighlightsMarkdown:
    // "# <title>[ — <author>]", per highlight "> <text>" with \n->space, note on its own line).
    const QByteArray expected =
        "# Alpha \xE2\x80\x94 Ann Author\n\n"
        "> First line one line two\n\n"
        "> Second highlight\n\n"
        "# Beta\n\n"
        "> Third quote\n\na note wrapped\n\n";
    const QByteArray got = Library::keptMarkdown(kept).toUtf8();
    if (got != expected) {
        printf("MARKDOWN MISMATCH:\n--- got ---\n%s\n--- want ---\n%s\n",
               got.constData(), expected.constData());
        assert(false);
    }

    // ---- spine filter + CRUD roundtrip ----
    const qint64 hA3 = lib.addHighlight(idA, 1, 5, 9, "chapter two");
    assert(hA3 > 0);
    QVector<Highlight> allA = lib.highlights(idA);  // spineIndex default -1 => every chapter
    assert(allA.size() == 3);
    // ORDER BY spine_index, start_char: (0,0)=hA1, (0,20)=hA2, (1,5)=hA3.
    assert(allA[0].id == hA1 && allA[1].id == hA2 && allA[2].id == hA3);
    assert(lib.highlights(idA, 0).size() == 2);
    QVector<Highlight> spine1 = lib.highlights(idA, 1);
    assert(spine1.size() == 1 && spine1[0].id == hA3 && spine1[0].text == "chapter two");
    assert(spine1[0].note.isEmpty());  // no note => NULL => empty

    // update note + color, then read back.
    lib.updateHighlight(hA1, QStringLiteral("edited note"), 2);
    Highlight after = lib.highlights(idA, 0).at(0);  // start_char 0 => hA1
    assert(after.id == hA1 && after.note == "edited note" && after.colorId == 2);

    // delete removes just that row.
    lib.deleteHighlight(hA3);
    assert(lib.highlights(idA, 1).isEmpty());
    assert(lib.highlights(idA).size() == 2);

    // deleteBook cascades highlights (FK ON DELETE CASCADE).
    lib.deleteBook(idB);
    for (const KeptHighlight &kh : lib.allHighlights())
        assert(kh.h.bookId != idB);

    delete st;
    printf("ALL HIGHLIGHT TESTS PASSED\n");
    return 0;
}
