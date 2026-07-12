#include "library/library.h"

#include "library/digest.h"
#include "storage/storage.h"

#include <sqlite3.h>

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QSet>

#include <cstdio>   // ::rename
#include <fcntl.h>
#include <unistd.h>  // fsync

#ifdef CCX_HAVE_PDFIUM
#include "pdf/pdfdocument.h"
#include <QImage>
static constexpr int kCoverMaxDim = 400;  // page-0 thumbnail longest edge, px
#endif

namespace {

qint64 now() { return QDateTime::currentMSecsSinceEpoch(); }

// Same prepared-statement RAII as storage.cpp — the reader lib prepares its own
// statements against Storage's connection (foreign_keys=ON, WAL, synchronous=FULL).
struct Stmt {
    sqlite3_stmt *s = nullptr;
    Stmt(sqlite3 *db, const char *sql)
    {
        if (sqlite3_prepare_v2(db, sql, -1, &s, nullptr) != SQLITE_OK)
            qWarning("library: prepare failed: %s -- %s", sqlite3_errmsg(db), sql);
    }
    ~Stmt() { sqlite3_finalize(s); }
    bool row() { return s && sqlite3_step(s) == SQLITE_ROW; }
    bool done() { return s && sqlite3_step(s) == SQLITE_DONE; }
};

bool exec(sqlite3 *db, const char *sql)
{
    char *err = nullptr;
    if (sqlite3_exec(db, sql, nullptr, nullptr, &err) != SQLITE_OK) {
        qWarning("library: %s -- %s", err ? err : "error", sql);
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
    ~Tx()
    {
        if (active)
            exec(db, "ROLLBACK");
    }
};

QString colText(sqlite3_stmt *s, int i)
{
    if (sqlite3_column_type(s, i) == SQLITE_NULL)
        return QString();
    return QString::fromUtf8(reinterpret_cast<const char *>(sqlite3_column_text(s, i)));
}

qint64 colInt64OrZero(sqlite3_stmt *s, int i)  // NULL -> 0 (never-opened / never-synced sentinel)
{
    return sqlite3_column_type(s, i) == SQLITE_NULL ? 0 : sqlite3_column_int64(s, i);
}

void bindText(sqlite3_stmt *s, int i, const QByteArray &v)
{
    sqlite3_bind_text(s, i, v.constData(), v.size(), SQLITE_TRANSIENT);
}

// fsync the directory so a rename/create is durable across power loss (mirrors storage.cpp).
void fsyncDir(const QString &path)
{
    const int fd = ::open(QFile::encodeName(path).constData(), O_RDONLY | O_DIRECTORY);
    if (fd >= 0) {
        ::fsync(fd);
        ::close(fd);
    }
}

// Copy src -> tmp, flush + fsync the file. Returns false (and removes tmp) on any error.
bool copyDurable(const QString &src, const QString &tmp)
{
    QFile in(src);
    QFile out(tmp);
    if (!in.open(QIODevice::ReadOnly) || !out.open(QIODevice::WriteOnly | QIODevice::Truncate))
        return false;
    char buf[64 * 1024];
    for (;;) {
        const qint64 n = in.read(buf, sizeof buf);
        if (n < 0 || (n > 0 && out.write(buf, n) != n)) {
            out.close();
            QFile::remove(tmp);
            return false;
        }
        if (n == 0)
            break;
    }
    // fsync is the one barrier where deferred ext4/ENOSPC/EIO surfaces: a discarded return
    // would let a truncated file be committed as a durable book.
    if (!out.flush() || ::fsync(out.handle()) != 0) {
        out.close();
        QFile::remove(tmp);
        return false;
    }
    out.close();
    return true;
}

}  // namespace

Library::Library(Storage *s, const QString &dataDir) : m_storage(s), m_dataDir(dataDir)
{
    QDir().mkpath(inboxDir());
    QDir().mkpath(booksDir());
    QDir().mkpath(coversDir());
    // Sweep temps left by an import killed mid-copy.
    const QFileInfoList stale =
        QDir(booksDir()).entryInfoList({QStringLiteral("import-*.tmp")}, QDir::Files);
    for (const QFileInfo &fi : stale)
        QFile::remove(fi.absoluteFilePath());
    reconcileOrphans();
}

// Reclaim final book files whose DB row never landed (crash/power-loss between the
// rename and the INSERT commit) — the only way to close that window after a hard kill.
void Library::reconcileOrphans()
{
    if (!m_storage)
        return;
    QSet<QString> known;  // digests with a live books row
    Stmt q(m_storage->handle(), "SELECT digest FROM books");
    while (q.row())
        known.insert(colText(q.s, 0));
    const QFileInfoList files = QDir(booksDir()).entryInfoList(
        {QStringLiteral("*.pdf"), QStringLiteral("*.epub")}, QDir::Files);
    for (const QFileInfo &fi : files) {
        if (!known.contains(fi.completeBaseName()))  // filename is <digest>.<ext>
            QFile::remove(fi.absoluteFilePath());
    }
}

QString Library::inboxDir() const { return m_dataDir + QStringLiteral("/inbox"); }
QString Library::booksDir() const { return m_dataDir + QStringLiteral("/books"); }
QString Library::coversDir() const { return m_dataDir + QStringLiteral("/covers"); }

Library::ImportSummary Library::importInbox()
{
    QMutexLocker lock(&m_importMutex);
    ImportSummary sum{0, 0, 0};
    const QFileInfoList entries = QDir(inboxDir()).entryInfoList(QDir::Files);
    for (const QFileInfo &fi : entries) {
        const QString ext = fi.suffix().toLower();
        if (ext != QLatin1String("pdf") && ext != QLatin1String("epub"))
            continue;
        switch (importFile(fi.absoluteFilePath())) {
        case Imported:  ++sum.imported;   break;
        case Duplicate: ++sum.duplicates; break;
        case Failed:    ++sum.failed;     break;
        }
    }
    return sum;
}

ImportResult Library::importFile(const QString &srcPath)
{
    sqlite3 *db = m_storage->handle();
    const QFileInfo fi(srcPath);
    const QString ext = fi.suffix().toLower();
    const int format = ext == QLatin1String("epub") ? 1 : 0;

    const QString digest = ccx::partialMd5(srcPath);
    if (digest.isEmpty())
        return Failed;

    {  // dedupe on partial-MD5
        Stmt q(db, "SELECT 1 FROM books WHERE digest = ?");
        bindText(q.s, 1, digest.toUtf8());
        if (q.row())
            return Duplicate;
    }

    const QString finalPath = booksDir() + QStringLiteral("/") + digest + QStringLiteral(".") + ext;
    const QString tmpPath = booksDir() + QStringLiteral("/import-") + digest + QStringLiteral(".tmp");

    // Atomic copy-in: write temp (fsync'd), then rename over the final path.
    if (!copyDurable(srcPath, tmpPath))
        return Failed;
    if (::rename(QFile::encodeName(tmpPath).constData(),
                 QFile::encodeName(finalPath).constData()) != 0) {
        QFile::remove(tmpPath);
        return Failed;
    }
    fsyncDir(booksDir());

    // Metadata + cover. On a fresh import failure we must not leave orphan files.
    QString title;
    QString coverPath;
#ifdef CCX_HAVE_PDFIUM
    if (format == 0) {
        QString err;
        PdfDocument *doc = PdfDocument::open(finalPath, &err);
        if (doc) {
            title = doc->metaText(QStringLiteral("Title"));  // "" if the PDF has no Title
            const QImage thumb = doc->renderThumbnail(0, kCoverMaxDim);
            const QString cp = coversDir() + QStringLiteral("/") + digest + QStringLiteral(".png");
            if (!thumb.isNull() && thumb.save(cp, "PNG"))
                coverPath = cp;
            delete doc;
        }
        // A render/open failure is non-fatal: the book still imports with a filename title.
    }
#endif
    if (title.trimmed().isEmpty())
        title = fi.completeBaseName();  // strip extension
    if (title.trimmed().isEmpty())
        title = QStringLiteral("Untitled");

    Tx tx(db);
    if (!tx.active) {
        QFile::remove(finalPath);
        if (!coverPath.isEmpty())
            QFile::remove(coverPath);
        return Failed;
    }
    Stmt ins(db,
             "INSERT INTO books(title, author, file_path, digest, cover_path, size_bytes,"
             "  format, added_at, last_opened_at)"
             "  VALUES(?, NULL, ?, ?, ?, ?, ?, ?, NULL)");
    bindText(ins.s, 1, title.toUtf8());
    bindText(ins.s, 2, finalPath.toUtf8());
    bindText(ins.s, 3, digest.toUtf8());
    if (coverPath.isEmpty())
        sqlite3_bind_null(ins.s, 4);
    else
        bindText(ins.s, 4, coverPath.toUtf8());
    sqlite3_bind_int64(ins.s, 5, fi.size());
    sqlite3_bind_int(ins.s, 6, format);
    sqlite3_bind_int64(ins.s, 7, now());
    if (!ins.done() || !tx.commit()) {
        QFile::remove(finalPath);
        if (!coverPath.isEmpty())
            QFile::remove(coverPath);
        return Failed;
    }
    return Imported;
}

QVector<BookRow> Library::books()
{
    QVector<BookRow> out;
    Stmt q(m_storage->handle(),
           "SELECT id, title, author, file_path, digest, cover_path, size_bytes, format,"
           "  added_at, last_opened_at FROM books"
           "  ORDER BY last_opened_at IS NULL, last_opened_at DESC, added_at DESC");
    while (q.row()) {
        BookRow b;
        b.id = sqlite3_column_int64(q.s, 0);
        b.title = colText(q.s, 1);
        b.author = colText(q.s, 2);
        b.filePath = colText(q.s, 3);
        b.digest = colText(q.s, 4);
        b.coverPath = colText(q.s, 5);
        b.sizeBytes = sqlite3_column_int64(q.s, 6);
        b.format = sqlite3_column_int(q.s, 7);
        b.addedAt = sqlite3_column_int64(q.s, 8);
        b.lastOpenedAt = colInt64OrZero(q.s, 9);
        out.append(b);
    }
    return out;
}

QVector<Library::BookWithProgress> Library::booksWithProgress()
{
    QVector<BookWithProgress> out;
    Stmt q(m_storage->handle(),
           "SELECT b.id, b.title, b.author, b.file_path, b.digest, b.cover_path, b.size_bytes,"
           "  b.format, b.added_at, b.last_opened_at, p.percentage"
           "  FROM books b LEFT JOIN progress p ON p.book_id = b.id"
           "  ORDER BY b.last_opened_at IS NULL, b.last_opened_at DESC, b.added_at DESC");
    while (q.row()) {
        BookWithProgress bp;
        bp.book.id = sqlite3_column_int64(q.s, 0);
        bp.book.title = colText(q.s, 1);
        bp.book.author = colText(q.s, 2);
        bp.book.filePath = colText(q.s, 3);
        bp.book.digest = colText(q.s, 4);
        bp.book.coverPath = colText(q.s, 5);
        bp.book.sizeBytes = sqlite3_column_int64(q.s, 6);
        bp.book.format = sqlite3_column_int(q.s, 7);
        bp.book.addedAt = sqlite3_column_int64(q.s, 8);
        bp.book.lastOpenedAt = colInt64OrZero(q.s, 9);
        bp.hasProgress = sqlite3_column_type(q.s, 10) != SQLITE_NULL;
        bp.percentage = bp.hasProgress ? sqlite3_column_double(q.s, 10) : 0.0;
        out.append(bp);
    }
    return out;
}

void Library::markOpened(qint64 id)
{
    sqlite3 *db = m_storage->handle();
    Tx tx(db);
    if (!tx.active)
        return;
    Stmt q(db, "UPDATE books SET last_opened_at = ? WHERE id = ?");
    sqlite3_bind_int64(q.s, 1, now());
    sqlite3_bind_int64(q.s, 2, id);
    if (q.done())
        tx.commit();
}

void Library::deleteBook(qint64 id)
{
    sqlite3 *db = m_storage->handle();
    QString filePath, coverPath;
    {
        Stmt q(db, "SELECT file_path, cover_path FROM books WHERE id = ?");
        sqlite3_bind_int64(q.s, 1, id);
        if (!q.row())
            return;  // no such book
        filePath = colText(q.s, 0);
        coverPath = colText(q.s, 1);
    }
    Tx tx(db);
    if (!tx.active)
        return;
    Stmt del(db, "DELETE FROM books WHERE id = ?");  // FK cascade: progress/bookmarks/etc.
    sqlite3_bind_int64(del.s, 1, id);
    if (!del.done() || !tx.commit())
        return;
    // Row is gone and committed — now unlink the on-disk files.
    if (!filePath.isEmpty())
        QFile::remove(filePath);
    if (!coverPath.isEmpty())
        QFile::remove(coverPath);
}

Library::Progress Library::progress(qint64 bookId)
{
    Progress p{0, 0, 0.0, 0, 0, false};
    Stmt q(m_storage->handle(),
           "SELECT spine_index, char_offset, percentage, updated_at, synced_at"
           "  FROM progress WHERE book_id = ?");
    sqlite3_bind_int64(q.s, 1, bookId);
    if (q.row()) {
        p.spineIndex = sqlite3_column_int(q.s, 0);
        p.charOffset = sqlite3_column_int(q.s, 1);
        p.percentage = sqlite3_column_double(q.s, 2);
        p.updatedAt = sqlite3_column_int64(q.s, 3);
        p.syncedAt = colInt64OrZero(q.s, 4);
        p.exists = true;
    }
    return p;
}

void Library::saveProgress(qint64 bookId, int spineIndex, int charOffset, double percentage)
{
    sqlite3 *db = m_storage->handle();
    Tx tx(db);
    if (!tx.active)
        return;
    // Upsert. synced_at is absent from the UPDATE SET, so an existing sync marker survives;
    // a fresh row starts synced_at = NULL (dirty) per the kosync dirty rule.
    Stmt q(db,
           "INSERT INTO progress(book_id, spine_index, char_offset, percentage, updated_at,"
           "  synced_at) VALUES(?, ?, ?, ?, ?, NULL)"
           "  ON CONFLICT(book_id) DO UPDATE SET spine_index = excluded.spine_index,"
           "    char_offset = excluded.char_offset, percentage = excluded.percentage,"
           "    updated_at = excluded.updated_at");
    sqlite3_bind_int64(q.s, 1, bookId);
    sqlite3_bind_int(q.s, 2, spineIndex);
    sqlite3_bind_int(q.s, 3, charOffset);
    sqlite3_bind_double(q.s, 4, percentage);
    sqlite3_bind_int64(q.s, 5, now());
    if (q.done())
        tx.commit();
}

QVector<Bookmark> Library::bookmarks(qint64 bookId)
{
    QVector<Bookmark> out;
    Stmt q(m_storage->handle(),
           "SELECT id, book_id, spine_index, char_offset, percentage, label, created_at"
           "  FROM bookmarks WHERE book_id = ? ORDER BY created_at, id");
    sqlite3_bind_int64(q.s, 1, bookId);
    while (q.row()) {
        Bookmark b;
        b.id = sqlite3_column_int64(q.s, 0);
        b.bookId = sqlite3_column_int64(q.s, 1);
        b.spineIndex = sqlite3_column_int(q.s, 2);
        b.charOffset = sqlite3_column_int(q.s, 3);
        b.percentage = sqlite3_column_double(q.s, 4);
        b.label = colText(q.s, 5);
        b.createdAt = sqlite3_column_int64(q.s, 6);
        out.append(b);
    }
    return out;
}

qint64 Library::addBookmark(qint64 bookId, int spineIndex, int charOffset, double percentage,
                            const QString &label)
{
    sqlite3 *db = m_storage->handle();
    Tx tx(db);
    if (!tx.active)
        return -1;
    Stmt q(db,
           "INSERT INTO bookmarks(book_id, spine_index, char_offset, percentage, label,"
           "  created_at) VALUES(?, ?, ?, ?, ?, ?)");
    sqlite3_bind_int64(q.s, 1, bookId);
    sqlite3_bind_int(q.s, 2, spineIndex);
    sqlite3_bind_int(q.s, 3, charOffset);
    sqlite3_bind_double(q.s, 4, percentage);
    bindText(q.s, 5, label.toUtf8());
    sqlite3_bind_int64(q.s, 6, now());
    if (!q.done())
        return -1;
    const qint64 id = sqlite3_last_insert_rowid(db);
    return tx.commit() ? id : -1;
}

void Library::deleteBookmark(qint64 id)
{
    sqlite3 *db = m_storage->handle();
    Tx tx(db);
    if (!tx.active)
        return;
    Stmt q(db, "DELETE FROM bookmarks WHERE id = ?");
    sqlite3_bind_int64(q.s, 1, id);
    if (q.done())
        tx.commit();
}

QVector<Highlight> Library::highlights(qint64 bookId, int spineIndex)
{
    QVector<Highlight> out;
    Stmt q(m_storage->handle(),
           "SELECT id, book_id, spine_index, start_char, end_char, text, note, color_id,"
           "  created_at FROM highlights WHERE book_id = ?1 AND (?2 < 0 OR spine_index = ?2)"
           "  ORDER BY spine_index, start_char");
    sqlite3_bind_int64(q.s, 1, bookId);
    sqlite3_bind_int(q.s, 2, spineIndex);
    while (q.row()) {
        Highlight h;
        h.id = sqlite3_column_int64(q.s, 0);
        h.bookId = sqlite3_column_int64(q.s, 1);
        h.spineIndex = sqlite3_column_int(q.s, 2);
        h.startChar = sqlite3_column_int(q.s, 3);
        h.endChar = sqlite3_column_int(q.s, 4);
        h.text = colText(q.s, 5);
        h.note = colText(q.s, 6);
        h.colorId = sqlite3_column_int(q.s, 7);
        h.createdAt = sqlite3_column_int64(q.s, 8);
        out.append(h);
    }
    return out;
}

qint64 Library::addHighlight(qint64 bookId, int spineIndex, int startChar, int endChar,
                             const QString &text, const QString &note, int colorId)
{
    sqlite3 *db = m_storage->handle();
    Tx tx(db);
    if (!tx.active)
        return -1;
    Stmt q(db,
           "INSERT INTO highlights(book_id, spine_index, start_char, end_char, text, note,"
           "  color_id, created_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?)");
    sqlite3_bind_int64(q.s, 1, bookId);
    sqlite3_bind_int(q.s, 2, spineIndex);
    sqlite3_bind_int(q.s, 3, startChar);
    sqlite3_bind_int(q.s, 4, endChar);
    bindText(q.s, 5, text.toUtf8());
    if (note.isEmpty())
        sqlite3_bind_null(q.s, 6);  // note is nullable; a blank annotation is stored as NULL
    else
        bindText(q.s, 6, note.toUtf8());
    sqlite3_bind_int(q.s, 7, colorId);
    sqlite3_bind_int64(q.s, 8, now());
    if (!q.done())
        return -1;
    const qint64 id = sqlite3_last_insert_rowid(db);
    return tx.commit() ? id : -1;
}

void Library::updateHighlight(qint64 id, const QString &note, int colorId)
{
    sqlite3 *db = m_storage->handle();
    Tx tx(db);
    if (!tx.active)
        return;
    Stmt q(db, "UPDATE highlights SET note = ?, color_id = ? WHERE id = ?");
    if (note.isEmpty())
        sqlite3_bind_null(q.s, 1);
    else
        bindText(q.s, 1, note.toUtf8());
    sqlite3_bind_int(q.s, 2, colorId);
    sqlite3_bind_int64(q.s, 3, id);
    if (q.done())
        tx.commit();
}

void Library::deleteHighlight(qint64 id)
{
    sqlite3 *db = m_storage->handle();
    Tx tx(db);
    if (!tx.active)
        return;
    Stmt q(db, "DELETE FROM highlights WHERE id = ?");
    sqlite3_bind_int64(q.s, 1, id);
    if (q.done())
        tx.commit();
}

QVector<KeptHighlight> Library::allHighlights()
{
    QVector<KeptHighlight> out;
    Stmt q(m_storage->handle(),
           "SELECT h.id, h.book_id, h.spine_index, h.start_char, h.end_char, h.text, h.note,"
           "  h.color_id, h.created_at, b.title, b.author, b.format, b.file_path"
           "  FROM highlights h JOIN books b ON b.id = h.book_id"
           "  ORDER BY h.created_at DESC, h.id DESC");  // id DESC: stable order for same-ms inserts
    while (q.row()) {
        KeptHighlight kh;
        kh.h.id = sqlite3_column_int64(q.s, 0);
        kh.h.bookId = sqlite3_column_int64(q.s, 1);
        kh.h.spineIndex = sqlite3_column_int(q.s, 2);
        kh.h.startChar = sqlite3_column_int(q.s, 3);
        kh.h.endChar = sqlite3_column_int(q.s, 4);
        kh.h.text = colText(q.s, 5);
        kh.h.note = colText(q.s, 6);
        kh.h.colorId = sqlite3_column_int(q.s, 7);
        kh.h.createdAt = sqlite3_column_int64(q.s, 8);
        kh.bookTitle = colText(q.s, 9);
        kh.bookAuthor = colText(q.s, 10);
        kh.format = sqlite3_column_int(q.s, 11);
        kh.filePath = colText(q.s, 12);
        out.append(kh);
    }
    return out;
}

QString Library::keptMarkdown(const QVector<KeptHighlight> &items)
{
    // Group by (title, author) preserving first-appearance order, items in list order — matches
    // Kotlin groupBy (LinkedHashMap). ponytail: linear group lookup, fine for a personal library;
    // key by a hash if someone ever keeps highlights across thousands of distinct books.
    struct Group { QString title, author; QVector<const KeptHighlight *> items; };
    QVector<Group> groups;
    for (const KeptHighlight &kh : items) {
        int gi = -1;
        for (int i = 0; i < groups.size(); ++i)
            if (groups[i].title == kh.bookTitle && groups[i].author == kh.bookAuthor) {
                gi = i;
                break;
            }
        if (gi < 0) {
            groups.append({kh.bookTitle, kh.bookAuthor, {}});
            gi = int(groups.size()) - 1;
        }
        groups[gi].items.append(&kh);
    }

    // " — " is an em-dash with ASCII spaces, byte-identical to the Android separator.
    const QString emDash = QString::fromUtf8(" \xE2\x80\x94 ");
    QString md;
    for (const Group &g : groups) {
        md += QStringLiteral("# ") + g.title;
        if (!g.author.trimmed().isEmpty())  // Kotlin isNullOrBlank(): blank author => no separator
            md += emDash + g.author;
        md += QStringLiteral("\n\n");
        for (const KeptHighlight *kh : g.items) {
            md += QStringLiteral("> ")
                  + QString(kh->h.text).replace(QLatin1Char('\n'), QLatin1Char(' '))
                  + QLatin1Char('\n');
            if (!kh->h.note.trimmed().isEmpty())  // note on its own line only when non-blank
                md += QLatin1Char('\n')
                      + QString(kh->h.note).replace(QLatin1Char('\n'), QLatin1Char(' '))
                      + QLatin1Char('\n');
            md += QLatin1Char('\n');
        }
    }
    return md;
}

QString Library::setting(const QString &key, const QString &def)
{
    Stmt q(m_storage->handle(), "SELECT value FROM settings WHERE key = ?");
    bindText(q.s, 1, key.toUtf8());
    return q.row() ? colText(q.s, 0) : def;
}

void Library::setSetting(const QString &key, const QString &value)
{
    sqlite3 *db = m_storage->handle();
    Tx tx(db);
    if (!tx.active)
        return;
    Stmt q(db, "INSERT INTO settings(key, value) VALUES(?, ?)"
               "  ON CONFLICT(key) DO UPDATE SET value = excluded.value");
    bindText(q.s, 1, key.toUtf8());
    bindText(q.s, 2, value.toUtf8());
    if (q.done())
        tx.commit();
}

QString Library::digestOf(qint64 bookId)
{
    Stmt q(m_storage->handle(), "SELECT digest FROM books WHERE id = ?");
    sqlite3_bind_int64(q.s, 1, bookId);
    return q.row() ? colText(q.s, 0) : QString();
}

BookRow Library::bookById(qint64 id)
{
    BookRow b;
    b.id = -1;  // not-found sentinel
    Stmt q(m_storage->handle(),
           "SELECT id, title, author, file_path, digest, cover_path, size_bytes, format,"
           "  added_at, last_opened_at FROM books WHERE id = ?");
    sqlite3_bind_int64(q.s, 1, id);
    if (q.row()) {
        b.id = sqlite3_column_int64(q.s, 0);
        b.title = colText(q.s, 1);
        b.author = colText(q.s, 2);
        b.filePath = colText(q.s, 3);
        b.digest = colText(q.s, 4);
        b.coverPath = colText(q.s, 5);
        b.sizeBytes = sqlite3_column_int64(q.s, 6);
        b.format = sqlite3_column_int(q.s, 7);
        b.addedAt = sqlite3_column_int64(q.s, 8);
        b.lastOpenedAt = colInt64OrZero(q.s, 9);
    }
    return b;
}

void Library::markSynced(qint64 bookId, qint64 updatedAt)
{
    sqlite3 *db = m_storage->handle();
    Tx tx(db);
    if (!tx.active)
        return;
    // synced_at = updatedAt (>= updated_at => clean per progressDirty), gated on the row still
    // being the pushed version; a concurrent newer save (different updated_at) matches nothing.
    Stmt q(db, "UPDATE progress SET synced_at = ? WHERE book_id = ? AND updated_at = ?");
    sqlite3_bind_int64(q.s, 1, updatedAt);
    sqlite3_bind_int64(q.s, 2, bookId);
    sqlite3_bind_int64(q.s, 3, updatedAt);
    if (q.done())
        tx.commit();
}

QVector<qint64> Library::dirtyProgressBookIds()
{
    QVector<qint64> out;
    Stmt q(m_storage->handle(),
           "SELECT book_id FROM progress WHERE synced_at IS NULL OR synced_at < updated_at");
    while (q.row())
        out.append(sqlite3_column_int64(q.s, 0));
    return out;
}
