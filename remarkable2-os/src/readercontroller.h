#pragma once
#include <QObject>
#include <QQmlEngine>
#include <QString>
#include <QVariantList>
#include <QVariantMap>

#include "storage/storage.h"

class Library;

// QML-facing reader/library facade. Owns its own Storage (same dir as NotebookController;
// two WAL connections to one data.db is safe) and a Library over it. Filter/sort/search live
// here in C++ over the small book list — the contract's rules, applied per call.
class ReaderController : public QObject
{
    Q_OBJECT
    QML_ELEMENT

public:
    explicit ReaderController(QObject *parent = nullptr);
    ~ReaderController() override;

    Q_INVOKABLE QVariantMap importInbox();                 // {imported,duplicates,failed}
    Q_INVOKABLE QVariantList books();                      // all books (each map has percentage+state)
    // filter: 0 all,1 unread,2 reading,3 finished; sort: 0 recent,1 title,2 author,3 added,4 progress
    Q_INVOKABLE QVariantMap view(const QString &query, int filter, int sort); // {books,counts}
    Q_INVOKABLE void deleteBook(qint64 id);
    Q_INVOKABLE QVariantMap openProgress(qint64 id);       // marks opened; {spineIndex,page,percentage,exists}
    Q_INVOKABLE void saveProgress(qint64 id, int page, int pageCount); // PDF: pct=(page+1)/count
    Q_INVOKABLE QVariantList bookmarks(qint64 id);         // [{id,page,percentage,label}]
    Q_INVOKABLE qint64 addBookmark(qint64 id, int page, int pageCount, const QString &label);
    Q_INVOKABLE void deleteBookmark(qint64 id);
    // TOC and search now come from PdfView (its already-open document), not a reopen here.

private:
    QVariantList allBooksWithPct();  // cached; invalidated on any mutation
    void invalidate() { m_cacheValid = false; }

    Storage *m_storage = nullptr;
    Library *m_library = nullptr;
    QVariantList m_cache;      // last allBooksWithPct() result
    bool m_cacheValid = false; // rebuilt only after import/delete/save/open/bookmark mutations
};
