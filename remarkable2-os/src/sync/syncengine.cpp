#include "sync/syncengine.h"

#include "storage/storage.h"
#include "sync/syncstore.h"
#include "sync/webdav.h"

#include <QByteArray>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QImage>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QSet>
#include <QStringList>
#include <QVector>

#include <sqlite3.h>

#include <cstdio>    // ::rename
#include <fcntl.h>
#include <memory>
#include <unistd.h>  // fsync

#ifdef CCX_HAVE_PDFIUM
#include "pdf/pdfdocument.h"
#endif
#include "epub/epubdocument.h"

namespace {

constexpr int kCoverMaxDim = 400;  // page-0 thumbnail longest edge, matches library.cpp import

// Last path segment, trailing '/' stripped. PROPFIND child entries may arrive as bare names or
// as full <d:href> paths; we only ever compare/append basenames.
QString baseName(const QString &s)
{
    QString n = s;
    while (n.endsWith(QLatin1Char('/')))
        n.chop(1);
    const int slash = n.lastIndexOf(QLatin1Char('/'));
    return slash >= 0 ? n.mid(slash + 1) : n;
}

// Write bytes to `path` via temp + fsync + rename (+ dir fsync): a crash never leaves a
// half-written file under its content-addressed name. Mirrors library.cpp copyDurable.
bool writeFileAtomic(const QString &path, const QByteArray &bytes)
{
    const QString tmp = path + QStringLiteral(".tmp");
    QFile out(tmp);
    if (!out.open(QIODevice::WriteOnly | QIODevice::Truncate))
        return false;
    if (out.write(bytes) != bytes.size() || !out.flush() || ::fsync(out.handle()) != 0) {
        out.close();
        QFile::remove(tmp);
        return false;
    }
    out.close();
    if (::rename(QFile::encodeName(tmp).constData(), QFile::encodeName(path).constData()) != 0) {
        QFile::remove(tmp);
        return false;
    }
    const int fd = ::open(QFile::encodeName(QFileInfo(path).absolutePath()).constData(),
                          O_RDONLY | O_DIRECTORY);
    if (fd >= 0) {
        ::fsync(fd);
        ::close(fd);
    }
    return true;
}

// Best-effort cover PNG for a freshly downloaded book; "" if none could be produced (non-fatal —
// the book still lists with a placeholder). Mirrors library.cpp's import cover extraction.
QString renderCover(int format, const QString &filePath, const QString &digest,
                    const QString &coversDir)
{
    const QString cp = coversDir + QStringLiteral("/") + digest + QStringLiteral(".png");
    if (format == 1) {  // EPUB: pull the cover image out of the container and re-encode
        QString err;
        EpubDocument *doc = EpubDocument::open(filePath, &err);
        if (!doc)
            return QString();
        const QByteArray bytes = doc->coverImageBytes();
        delete doc;
        QImage img;
        if (bytes.isEmpty() || !img.loadFromData(bytes))
            return QString();
        return img.save(cp, "PNG") ? cp : QString();
    }
#ifdef CCX_HAVE_PDFIUM
    QString err;
    PdfDocument *doc = PdfDocument::open(filePath, &err);
    if (!doc)
        return QString();
    const QImage thumb = doc->renderThumbnail(0, kCoverMaxDim);
    delete doc;
    if (!thumb.isNull() && thumb.save(cp, "PNG"))
        return cp;
#endif
    return QString();
}

}  // namespace

void SyncEngine::run(WebDavConfig cfg, QString deviceId, QString dataDir)
{
    QVariantMap summary{{QStringLiteral("booksUp"), 0},
                        {QStringLiteral("booksDown"), 0},
                        {QStringLiteral("entities"), 0},
                        {QStringLiteral("tombstones"), 0},
                        {QStringLiteral("error"), QString()}};

    const auto fail = [&](const QString &msg) {
        summary[QStringLiteral("error")] = msg;
        emit finished(false, summary);
    };

    emit progress(QStringLiteral("Opening store"));
    QString err;
    // The engine owns this worker-thread Storage connection; SyncStore only borrows it (its ctor
    // just stashes the pointer). unique_ptr closes it on every return path.
    std::unique_ptr<Storage> storage(Storage::open(dataDir, &err));
    if (!storage)
        return fail(QStringLiteral("storage: ") + err);
    SyncStore store(storage.get());

    QNetworkAccessManager nam;  // affined to this (worker) thread; WebDavClient uses a local loop
    WebDavClient dav(cfg, &nam);

    // 1. Ensure remote dirs.
    emit progress(QStringLiteral("Preparing remote"));
    if (!dav.mkcol(QStringLiteral("books/"), &err) || !dav.mkcol(QStringLiteral("state/"), &err))
        return fail(QStringLiteral("mkcol: ") + err);

    // 2. Upload local book files the remote lacks (union by <digest>.<ext> filename).
    emit progress(QStringLiteral("Uploading books"));
    QStringList remoteBooks;
    if (!dav.list(QStringLiteral("books/"), &remoteBooks, &err))
        return fail(QStringLiteral("list books: ") + err);
    QSet<QString> haveRemote;
    for (const QString &n : remoteBooks)
        haveRemote.insert(baseName(n));
    int booksUp = 0;
    for (const QString &digest : store.localBookDigestsWithFile()) {
        const QString ext = store.extForDigest(digest);
        if (ext.isEmpty())
            continue;
        const QString name = digest + QStringLiteral(".") + ext;
        if (haveRemote.contains(name))
            continue;
        QFile f(store.filePathForDigest(digest));
        if (!f.open(QIODevice::ReadOnly))
            continue;  // best-effort: a missing local file just isn't uploaded this pass
        const QByteArray data = f.readAll();
        f.close();
        if (dav.put(QStringLiteral("books/") + name, data, &err))
            ++booksUp;  // else best-effort skip; the next sync retries
    }
    summary[QStringLiteral("booksUp")] = booksUp;

    // 3. Pull every device snapshot.
    emit progress(QStringLiteral("Fetching state"));
    QStringList stateFiles;
    if (!dav.list(QStringLiteral("state/"), &stateFiles, &err))
        return fail(QStringLiteral("list state: ") + err);
    QVector<QJsonObject> snapshots;
    for (const QString &raw : stateFiles) {
        const QString name = baseName(raw);
        if (!name.endsWith(QStringLiteral(".json")))
            continue;
        QByteArray body;
        if (!dav.get(QStringLiteral("state/") + name, &body, &err))
            continue;  // skip an unreadable peer snapshot; the others still merge
        const QJsonObject obj = QJsonDocument::fromJson(body).object();
        if (!obj.isEmpty())
            snapshots.append(obj);
    }

    // 4. Merge (LWW) into the local DB, then fetch+attach any book file the merged rows now need.
    emit progress(QStringLiteral("Merging"));
    const SyncStore::MergeStats stats = store.applyMerged(snapshots, deviceId);
    summary[QStringLiteral("entities")] = stats.entitiesApplied;
    summary[QStringLiteral("tombstones")] = stats.tombstonesApplied;

    const QString booksDir = dataDir + QStringLiteral("/books");
    const QString coversDir = dataDir + QStringLiteral("/covers");
    QDir().mkpath(booksDir);
    QDir().mkpath(coversDir);
    int booksDown = 0;
    for (const QString &digest : stats.missingDigests) {
        const QString ext = store.extForDigest(digest);
        if (ext.isEmpty())
            continue;
        emit progress(QStringLiteral("Downloading ") + digest.left(8));
        QByteArray body;
        if (!dav.get(QStringLiteral("books/") + digest + QStringLiteral(".") + ext, &body, &err))
            continue;  // best-effort: the row keeps an empty file_path; a later sync retries
        const QString filePath =
            booksDir + QStringLiteral("/") + digest + QStringLiteral(".") + ext;
        if (!writeFileAtomic(filePath, body))
            continue;
        const int format = ext == QStringLiteral("epub") ? 1 : 0;
        // PDFium is not thread-safe (see pdfdocument.h). ponytail: cover render runs on the sync
        // worker because sync fires only when no reader is open; move to the GUI thread if that
        // invariant ever breaks.
        const QString coverPath = renderCover(format, filePath, digest, coversDir);
        store.attachBookFile(digest, filePath, coverPath);
        ++booksDown;
    }
    summary[QStringLiteral("booksDown")] = booksDown;

    // 5. Publish this device's full snapshot. WebDavClient exposes no MOVE, so PUT the final name
    //    directly (the contract's documented fallback for MOVE-less servers).
    emit progress(QStringLiteral("Publishing"));
    const QJsonObject snap = store.exportSnapshot(deviceId);
    const QByteArray body = QJsonDocument(snap).toJson(QJsonDocument::Compact);
    if (!dav.put(QStringLiteral("state/") + deviceId + QStringLiteral(".json"), body, &err))
        return fail(QStringLiteral("put snapshot: ") + err);

    // 6. Stamp last-sync time. sync_state's schema isn't part of the headers this engine codes
    //    against, so the timestamp lands in the settings k/v store (same place kosync keeps its
    //    last_sync_at); an integer value, no injection surface.
    char sql[192];
    std::snprintf(sql, sizeof sql,
                  "INSERT INTO settings(key, value) VALUES('webdav_last_sync_at', '%lld')"
                  "  ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                  static_cast<long long>(QDateTime::currentMSecsSinceEpoch()));
    sqlite3_exec(storage->handle(), sql, nullptr, nullptr, nullptr);

    emit progress(QStringLiteral("Done"));
    emit finished(true, summary);
}
