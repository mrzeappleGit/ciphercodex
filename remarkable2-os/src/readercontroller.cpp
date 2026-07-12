#include "readercontroller.h"

#include <algorithm>

#include <QPair>

#include "library/library.h"
#include "pdf/pdfdocument.h"

// Matches the Android FINISHED_THRESHOLD: >=0.98 reads as finished.
static constexpr double FINISHED_THRESHOLD = 0.98;

ReaderController::ReaderController(QObject *parent) : QObject(parent)
{
    const QString dir = qEnvironmentVariable("CCX_DATA_DIR", QStringLiteral("/home/root/ciphercodex"));
    QString err;
    m_storage = Storage::open(dir, &err);
    if (!m_storage) {
        qWarning("ReaderController: storage open failed: %s", qUtf8Printable(err));
        return;
    }
    m_library = new Library(m_storage, dir);
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
    return {{QStringLiteral("imported"), s.imported},
            {QStringLiteral("duplicates"), s.duplicates},
            {QStringLiteral("failed"), s.failed}};
}

QVariantList ReaderController::allBooksWithPct()
{
    QVariantList out;
    if (!m_library)
        return out;
    for (const BookRow &b : m_library->books()) {
        const Library::Progress p = m_library->progress(b.id);
        const double pct = p.exists ? p.percentage : 0.0;
        const int state = !p.exists ? 0 : (pct >= FINISHED_THRESHOLD ? 2 : 1); // 0 unread,1 reading,2 finished
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
    if (m_library)
        m_library->deleteBook(id);
}

QVariantMap ReaderController::openProgress(qint64 id)
{
    if (!m_library)
        return {};
    m_library->markOpened(id);
    const Library::Progress p = m_library->progress(id);
    const int page = p.exists ? p.spineIndex : 0;
    return {{QStringLiteral("spineIndex"), page},
            {QStringLiteral("page"), page}, // PDF: page == spine_index
            {QStringLiteral("percentage"), p.exists ? p.percentage : 0.0},
            {QStringLiteral("exists"), p.exists}};
}

void ReaderController::saveProgress(qint64 id, int page, int pageCount)
{
    if (!m_library)
        return;
    const double pct = pageCount > 0 ? double(page + 1) / pageCount : 0.0;
    m_library->saveProgress(id, page, 0, pct); // PDF: char_offset 0
}

QVariantList ReaderController::bookmarks(qint64 id)
{
    QVariantList out;
    if (!m_library)
        return out;
    for (const Bookmark &b : m_library->bookmarks(id))
        out.append(QVariantMap{{QStringLiteral("id"), b.id},
                               {QStringLiteral("page"), b.spineIndex}, // PDF: page == spine_index
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

void ReaderController::deleteBookmark(qint64 id)
{
    if (m_library)
        m_library->deleteBookmark(id);
}

QVariantList ReaderController::pdfOutline(const QString &path)
{
    QVariantList out;
    QString err;
    PdfDocument *doc = PdfDocument::open(path, &err);
    if (!doc) {
        qWarning("ReaderController: pdfOutline open failed: %s", qUtf8Printable(err));
        return out;
    }
    for (const PdfOutline &o : doc->outline())
        out.append(QVariantMap{{QStringLiteral("title"), o.title},
                               {QStringLiteral("pageIndex"), o.pageIndex},
                               {QStringLiteral("level"), o.level}});
    delete doc;
    return out;
}

QVariantList ReaderController::pdfSearch(const QString &path, const QString &query)
{
    QVariantList out;
    if (query.isEmpty())
        return out;
    QString err;
    PdfDocument *doc = PdfDocument::open(path, &err);
    if (!doc) {
        qWarning("ReaderController: pdfSearch open failed: %s", qUtf8Printable(err));
        return out;
    }
    for (const QPair<int, int> &m : doc->search(query))
        out.append(QVariantMap{{QStringLiteral("pageIndex"), m.first},
                               {QStringLiteral("count"), m.second}});
    delete doc;
    return out;
}
