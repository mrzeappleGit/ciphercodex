#include "readercontroller.h"

#include <QDateTime>
#include <QFile>
#include <QFileInfo>
#include <QNetworkAccessManager>
#include <QUuid>

#include <algorithm>
#include <cmath>
#include <cstdio>   // ::rename
#include <fcntl.h>
#include <unistd.h>  // fsync

#include "library/library.h"

// Matches the Android FINISHED_THRESHOLD: >=0.98 reads as finished.
static constexpr double FINISHED_THRESHOLD = 0.98;

ReaderController::ReaderController(QObject *parent) : QObject(parent)
{
    m_nam = new QNetworkAccessManager(this);
    m_dataDir = qEnvironmentVariable("CCX_DATA_DIR", QStringLiteral("/home/root/ciphercodex"));
    QString err;
    m_storage = Storage::open(m_dataDir, &err);
    if (!m_storage) {
        qWarning("ReaderController: storage open failed: %s", qUtf8Printable(err));
        return;
    }
    m_library = new Library(m_storage, m_dataDir);
}

ReaderController::~ReaderController()
{
    delete m_library;
    delete m_storage;
}

QVariantMap ReaderController::importInbox()
{
    if (!m_library)
        return {};
    const Library::ImportSummary s = m_library->importInbox();
    invalidate();
    return {{QStringLiteral("imported"), s.imported},
            {QStringLiteral("duplicates"), s.duplicates},
            {QStringLiteral("failed"), s.failed}};
}

QVariantList ReaderController::allBooksWithPct()
{
    if (m_cacheValid)
        return m_cache;
    QVariantList out;
    if (m_library) {
        // one LEFT JOIN, not 1+N per-book progress queries
        for (const Library::BookWithProgress &bp : m_library->booksWithProgress()) {
            const BookRow &b = bp.book;
            const double pct = bp.hasProgress ? bp.percentage : 0.0;
            const int state = !bp.hasProgress ? 0 : (pct >= FINISHED_THRESHOLD ? 2 : 1);
            out.append(QVariantMap{{QStringLiteral("id"), b.id},
                                   {QStringLiteral("title"), b.title},
                                   {QStringLiteral("author"), b.author},
                                   {QStringLiteral("coverPath"), b.coverPath},
                                   {QStringLiteral("filePath"), b.filePath},
                                   {QStringLiteral("sizeBytes"), b.sizeBytes},
                                   {QStringLiteral("percentage"), pct},
                                   {QStringLiteral("state"), state},
                                   {QStringLiteral("format"), b.format},
                                   {QStringLiteral("lastOpenedAt"), b.lastOpenedAt},
                                   {QStringLiteral("addedAt"), b.addedAt}});
        }
    }
    m_cache = out;
    m_cacheValid = true;
    return out;
}

QVariantList ReaderController::books()
{
    return allBooksWithPct();
}

QVariantMap ReaderController::view(const QString &query, int filter, int sort)
{
    const QVariantList all = allBooksWithPct();
    int cAll = 0, cUnread = 0, cReading = 0, cFinished = 0;
    QList<QVariantMap> rows;
    for (const QVariant &v : all) {
        const QVariantMap m = v.toMap();
        const int st = m.value(QStringLiteral("state")).toInt();
        ++cAll;
        if (st == 0)
            ++cUnread;
        else if (st == 1)
            ++cReading;
        else
            ++cFinished;
        if (filter == 1 && st != 0)
            continue;
        if (filter == 2 && st != 1)
            continue;
        if (filter == 3 && st != 2)
            continue;
        if (!query.isEmpty()
            && !m.value(QStringLiteral("title")).toString().contains(query, Qt::CaseInsensitive)
            && !m.value(QStringLiteral("author")).toString().contains(query, Qt::CaseInsensitive))
            continue;
        rows.append(m);
    }

    // sort 0 (recent) keeps books() native order (last_opened desc, added desc)
    const auto sortPct = [](const QVariantMap &m) {
        return m.value(QStringLiteral("state")).toInt() == 0 // no progress row sorts as -1
                   ? -1.0
                   : m.value(QStringLiteral("percentage")).toDouble();
    };
    if (sort == 1) {
        std::stable_sort(rows.begin(), rows.end(), [](const QVariantMap &a, const QVariantMap &b) {
            return a.value(QStringLiteral("title")).toString().compare(
                       b.value(QStringLiteral("title")).toString(), Qt::CaseInsensitive) < 0;
        });
    } else if (sort == 2) {
        std::stable_sort(rows.begin(), rows.end(), [](const QVariantMap &a, const QVariantMap &b) {
            const QString aa = a.value(QStringLiteral("author")).toString();
            const QString ab = b.value(QStringLiteral("author")).toString();
            if (aa.isEmpty() != ab.isEmpty())
                return ab.isEmpty(); // empty author sinks to the bottom
            return aa.compare(ab, Qt::CaseInsensitive) < 0;
        });
    } else if (sort == 3) {
        std::stable_sort(rows.begin(), rows.end(), [](const QVariantMap &a, const QVariantMap &b) {
            return a.value(QStringLiteral("addedAt")).toLongLong()
                   > b.value(QStringLiteral("addedAt")).toLongLong(); // newest first
        });
    } else if (sort == 4) {
        std::stable_sort(rows.begin(), rows.end(), [&](const QVariantMap &a, const QVariantMap &b) {
            return sortPct(a) > sortPct(b); // most progress first, no-progress last
        });
    }

    QVariantList outBooks;
    outBooks.reserve(rows.size());
    for (const QVariantMap &m : rows)
        outBooks.append(m);
    return {{QStringLiteral("books"), outBooks},
            {QStringLiteral("counts"),
             QVariantMap{{QStringLiteral("all"), cAll},
                         {QStringLiteral("unread"), cUnread},
                         {QStringLiteral("reading"), cReading},
                         {QStringLiteral("finished"), cFinished}}}};
}

void ReaderController::deleteBook(qint64 id)
{
    if (m_library) {
        m_library->deleteBook(id);
        invalidate();
    }
}

QVariantMap ReaderController::openProgress(qint64 id)
{
    if (!m_library)
        return {};
    m_library->markOpened(id);
    invalidate();  // last_opened_at changed -> recent order changed
    const Library::Progress p = m_library->progress(id);
    const int page = p.exists ? p.spineIndex : 0;
    return {{QStringLiteral("spineIndex"), page},
            {QStringLiteral("page"), page}, // PDF: page == spine_index
            {QStringLiteral("charOffset"), p.exists ? p.charOffset : 0}, // EPUB resume
            {QStringLiteral("percentage"), p.exists ? p.percentage : 0.0},
            {QStringLiteral("exists"), p.exists}};
}

void ReaderController::saveProgress(qint64 id, int page, int pageCount)
{
    if (!m_library)
        return;
    const double pct = pageCount > 0 ? double(page + 1) / pageCount : 0.0;
    m_library->saveProgress(id, page, 0, pct); // PDF: char_offset 0
    invalidate();  // percentage/state may have changed
}

void ReaderController::saveProgress(qint64 id, int spine, int charOffset, double percentage)
{
    if (!m_library)
        return;
    m_library->saveProgress(id, spine, charOffset, percentage);  // EPUB: real charOffset + whole-book pct
    invalidate();
}

QVariantList ReaderController::bookmarks(qint64 id)
{
    QVariantList out;
    if (!m_library)
        return out;
    for (const Bookmark &b : m_library->bookmarks(id))
        out.append(QVariantMap{{QStringLiteral("id"), b.id},
                               {QStringLiteral("page"), b.spineIndex},     // PDF: page == spine_index
                               {QStringLiteral("spineIndex"), b.spineIndex}, // EPUB
                               {QStringLiteral("charOffset"), b.charOffset},
                               {QStringLiteral("percentage"), b.percentage},
                               {QStringLiteral("label"), b.label}});
    return out;
}

qint64 ReaderController::addBookmark(qint64 id, int page, int pageCount, const QString &label)
{
    if (!m_library)
        return -1;
    const double pct = pageCount > 0 ? double(page + 1) / pageCount : 0.0;
    return m_library->addBookmark(id, page, 0, pct, label);
}

qint64 ReaderController::addBookmark(qint64 id, int spine, int charOffset, double percentage,
                                     const QString &label)
{
    if (!m_library)
        return -1;
    return m_library->addBookmark(id, spine, charOffset, percentage, label);
}

void ReaderController::deleteBookmark(qint64 id)
{
    if (m_library)
        m_library->deleteBookmark(id);
}

// ---- highlights + Kept (Phase 2c) ----

QVariantList ReaderController::highlights(qint64 bookId, int spineIndex)
{
    QVariantList out;
    if (!m_library)
        return out;
    for (const Highlight &h : m_library->highlights(bookId, spineIndex))
        out.append(QVariantMap{{QStringLiteral("id"), h.id},
                               {QStringLiteral("spineIndex"), h.spineIndex},
                               {QStringLiteral("startChar"), h.startChar},
                               {QStringLiteral("endChar"), h.endChar},
                               {QStringLiteral("text"), h.text},
                               {QStringLiteral("note"), h.note},
                               {QStringLiteral("createdAt"), h.createdAt}});
    return out;
}

qint64 ReaderController::addHighlight(qint64 bookId, int spineIndex, int startChar, int endChar,
                                      const QString &text, const QString &note)
{
    // colorId 0: color has no meaning on the mono panel (see contract).
    return m_library ? m_library->addHighlight(bookId, spineIndex, startChar, endChar, text, note)
                     : -1;
}

void ReaderController::updateHighlight(qint64 id, const QString &note)
{
    if (m_library)
        m_library->updateHighlight(id, note, 0);
}

void ReaderController::deleteHighlight(qint64 id)
{
    if (m_library)
        m_library->deleteHighlight(id);
}

QVariantList ReaderController::keptHighlights()
{
    QVariantList out;
    if (!m_library)
        return out;
    for (const KeptHighlight &kh : m_library->allHighlights())
        out.append(QVariantMap{{QStringLiteral("id"), kh.h.id},
                               {QStringLiteral("bookId"), kh.h.bookId},
                               {QStringLiteral("bookTitle"), kh.bookTitle},
                               {QStringLiteral("bookAuthor"), kh.bookAuthor},
                               {QStringLiteral("format"), kh.format},
                               {QStringLiteral("filePath"), kh.filePath},
                               {QStringLiteral("spineIndex"), kh.h.spineIndex},
                               {QStringLiteral("startChar"), kh.h.startChar},
                               {QStringLiteral("endChar"), kh.h.endChar},
                               {QStringLiteral("text"), kh.h.text},
                               {QStringLiteral("note"), kh.h.note},
                               {QStringLiteral("createdAt"), kh.h.createdAt}});
    return out;
}

bool ReaderController::exportKeptMarkdown(const QString &outPath)
{
    if (!m_library)
        return false;
    const QString path = outPath.isEmpty() ? m_dataDir + QStringLiteral("/kept.md") : outPath;
    const QByteArray bytes = Library::keptMarkdown(m_library->allHighlights()).toUtf8();

    // Atomic write: temp beside the target, fsync, rename over, fsync the dir (mirrors
    // library.cpp copyDurable/fsyncDir) so a crash can't leave a half-written kept.md.
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

QString ReaderController::setting(const QString &key, const QString &def)
{
    return m_library ? m_library->setting(key, def) : def;
}

void ReaderController::setSetting(const QString &key, const QString &value)
{
    if (m_library)
        m_library->setSetting(key, value);
}

// ---- kosync device integration ----

QString ReaderController::deviceId()
{
    QString id = m_library->setting(QStringLiteral("device_id"));
    if (id.isEmpty()) {
        id = QUuid::createUuid().toString(QUuid::WithoutBraces).remove(QLatin1Char('-'));
        m_library->setSetting(QStringLiteral("device_id"), id);
    }
    return id;
}

ccx::kosync::Account ReaderController::account()
{
    return {m_library->setting(QStringLiteral("server_url")),
            m_library->setting(QStringLiteral("username")),
            m_library->setting(QStringLiteral("user_key"))};
}

bool ReaderController::syncUsable()
{
    return m_library
        && m_library->setting(QStringLiteral("sync_enabled")) == QLatin1String("1")
        && !m_library->setting(QStringLiteral("username")).isEmpty()
        && !m_library->setting(QStringLiteral("user_key")).isEmpty()
        && !m_library->setting(QStringLiteral("server_url")).isEmpty();
}

QVariantMap ReaderController::syncConfig()
{
    if (!m_library)
        return {};
    const QString server = m_library->setting(QStringLiteral("server_url"));
    const QString username = m_library->setting(QStringLiteral("username"));
    const QString userKey = m_library->setting(QStringLiteral("user_key"));
    return {{QStringLiteral("enabled"),
             m_library->setting(QStringLiteral("sync_enabled")) == QLatin1String("1")},
            {QStringLiteral("serverUrl"), server},  // QML reads .serverUrl
            {QStringLiteral("username"), username},
            {QStringLiteral("deviceName"), m_library->setting(QStringLiteral("device_name"))},
            {QStringLiteral("configured"),  // user_key present, never returned
             !server.isEmpty() && !username.isEmpty() && !userKey.isEmpty()}};
}

void ReaderController::setSyncConfig(const QString &server, const QString &username,
                                     const QString &password, const QString &deviceName)
{
    if (!m_library)
        return;
    m_library->setSetting(QStringLiteral("server_url"), server.trimmed());
    m_library->setSetting(QStringLiteral("username"), username);
    // Store md5hex(password), never the raw password.
    m_library->setSetting(QStringLiteral("user_key"), ccx::kosync::userKey(password));
    m_library->setSetting(QStringLiteral("device_name"), deviceName);
    deviceId();  // ensure a persisted device_id exists
    m_library->setSetting(QStringLiteral("sync_enabled"), QStringLiteral("1"));
}

void ReaderController::setSyncEnabled(bool enabled)
{
    if (m_library)
        m_library->setSetting(QStringLiteral("sync_enabled"),
                              enabled ? QStringLiteral("1") : QStringLiteral("0"));
}

QVariantMap ReaderController::testConnection()
{
    if (!m_library)
        return {{QStringLiteral("ok"), false}, {QStringLiteral("message"), QStringLiteral("No library")}};
    ccx::kosync::KosyncClient client(m_nam);
    const ccx::kosync::Result r = client.authorize(account());
    return {{QStringLiteral("ok"), r.ok},
            {QStringLiteral("message"), r.ok ? QStringLiteral("Connected") : r.message}};
}

QVariantMap ReaderController::registerUser()
{
    if (!m_library)
        return {{QStringLiteral("ok"), false}, {QStringLiteral("message"), QStringLiteral("No library")}};
    ccx::kosync::KosyncClient client(m_nam);
    const ccx::kosync::Result r = client.registerUser(account());
    return {{QStringLiteral("ok"), r.ok},
            {QStringLiteral("message"), r.ok ? QStringLiteral("Registered") : r.message}};
}

void ReaderController::pullOnOpen(qint64 bookId)
{
    if (!syncUsable()) {
        emit pullReady(bookId, {{QStringLiteral("state"), QStringLiteral("Disabled")}});
        return;
    }
    const QString digest = m_library->digestOf(bookId);
    if (digest.isEmpty()) {
        emit pullReady(bookId, {{QStringLiteral("state"), QStringLiteral("Failed")},
                                {QStringLiteral("message"), QStringLiteral("No such book")}});
        return;
    }
    // Async: the reader opens immediately; the JUMP/STAY prompt appears if/when the server answers.
    ccx::kosync::KosyncClient(m_nam).getProgressAsync(
        account(), digest, [this, bookId](const ccx::kosync::Result &r) {
            emit pullReady(bookId, resolvePull(bookId, r));
        });
}

QVariantMap ReaderController::resolvePull(qint64 bookId, const ccx::kosync::Result &r)
{
    if (!r.ok)
        return {{QStringLiteral("state"), QStringLiteral("Failed")},
                {QStringLiteral("message"), r.message}};
    if (!r.remote.exists)
        return {{QStringLiteral("state"), QStringLiteral("NoRemote")}};

    const ccx::kosync::RemoteProgress &record = r.remote;
    const Library::Progress local = m_library->progress(bookId);

    // Our own last push echoed back — only short-circuit while local progress still exists (a
    // delete + re-import wants our own remote restored). device_id is the identity; the
    // user-editable device NAME must not participate.
    if (local.exists && record.deviceId.compare(deviceId(), Qt::CaseInsensitive) == 0)
        return {{QStringLiteral("state"), QStringLiteral("UpToDate")}};

    bool remoteNewer;
    if (!local.exists)
        remoteNewer = true;
    else if (record.timestamp >= 0)
        remoteNewer = record.timestamp * 1000 > local.updatedAt + 2000;  // 2s slack: whole-second, drift
    else
        remoteNewer = record.percentage > local.percentage
            && std::abs(record.percentage - local.percentage) > 0.0005;
    if (!remoteNewer)
        return {{QStringLiteral("state"), QStringLiteral("UpToDate")}};

    // Our own encoding first; else a KOReader xpointer spine (positioned by percentage, no offset).
    int spine = -1, charOffset = -1;
    if (const std::optional<ccx::kosync::Pos> decoded =
            ccx::kosync::ProgressCodec::decode(record.progress)) {
        spine = decoded->spineIndex;
        charOffset = decoded->charOffset;
    } else if (const std::optional<int> fs =
                   ccx::kosync::ProgressCodec::foreignSpine(record.progress)) {
        spine = *fs;
    }
    return {{QStringLiteral("state"), QStringLiteral("RemoteNewer")},
            {QStringLiteral("spine"), spine},
            {QStringLiteral("charOffset"), charOffset},
            {QStringLiteral("percentage"), record.percentage},
            {QStringLiteral("device"), record.device}};
}

void ReaderController::pushProgress(qint64 bookId, int spine, int charOffset, double percentage)
{
    if (!syncUsable())
        return;
    const QString digest = m_library->digestOf(bookId);
    if (digest.isEmpty())
        return;
    const qint64 updatedAt = m_library->progress(bookId).updatedAt;

    ccx::kosync::RemoteProgress p;
    p.document = digest;
    p.progress = ccx::kosync::ProgressCodec::encode(spine, charOffset);
    p.percentage = percentage;
    p.device = m_library->setting(QStringLiteral("device_name"));
    p.deviceId = deviceId().toUpper();  // wire convention: uppercase hex

    // Fire-and-forget: never blocks the reading path. markSynced (conditional on updated_at) runs
    // when the PUT returns, so a save that landed mid-push stays dirty and is retried.
    ccx::kosync::KosyncClient(m_nam).updateProgressAsync(
        account(), p, [this, bookId, updatedAt](bool ok) {
            if (ok)
                m_library->markSynced(bookId, updatedAt);
        });
}

void ReaderController::pushProgress(qint64 bookId)
{
    if (!m_library)
        return;
    const Library::Progress p = m_library->progress(bookId);
    if (p.exists)
        pushProgress(bookId, p.spineIndex, p.charOffset, p.percentage);
}

void ReaderController::syncAllDirty()
{
    if (!syncUsable())
        return;
    for (const qint64 bookId : m_library->dirtyProgressBookIds()) {
        const Library::Progress p = m_library->progress(bookId);
        if (p.exists)
            pushProgress(bookId, p.spineIndex, p.charOffset, p.percentage);
    }
    m_library->setSetting(QStringLiteral("last_sync_at"),
                          QString::number(QDateTime::currentMSecsSinceEpoch()));
}
