#pragma once
#include <QObject>
#include <QQmlEngine>
#include <QString>
#include <QVariantList>
#include <QVariantMap>

#include "storage/storage.h"
#include "sync/kosync.h"  // also forward-declares QNetworkAccessManager

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
    Q_INVOKABLE QVariantMap openProgress(qint64 id);       // marks opened; {spineIndex,charOffset,page,percentage,exists}
    Q_INVOKABLE void saveProgress(qint64 id, int page, int pageCount); // PDF: pct=(page+1)/count
    // EPUB: position is (spine,charOffset) with a whole-book percentage the reader computes.
    Q_INVOKABLE void saveProgress(qint64 id, int spine, int charOffset, double percentage);
    Q_INVOKABLE QVariantList bookmarks(qint64 id);         // [{id,page,spineIndex,charOffset,percentage,label}]
    Q_INVOKABLE qint64 addBookmark(qint64 id, int page, int pageCount, const QString &label);
    Q_INVOKABLE qint64 addBookmark(qint64 id, int spine, int charOffset, double percentage,
                                   const QString &label);
    Q_INVOKABLE void deleteBookmark(qint64 id);
    // TOC and search now come from the view items (their already-open documents), not a reopen here.

    // ---- highlights + Kept (Phase 2c) ----
    Q_INVOKABLE QVariantList highlights(qint64 bookId, int spineIndex);  // chapter highlights for rendering
    Q_INVOKABLE qint64 addHighlight(qint64 bookId, int spineIndex, int startChar, int endChar,
                                    const QString &text, const QString &note);
    Q_INVOKABLE void updateHighlight(qint64 id, const QString &note);  // colorId stays 0 (mono panel)
    Q_INVOKABLE void deleteHighlight(qint64 id);
    Q_INVOKABLE QVariantList keptHighlights();  // every highlight + its book, newest first
    Q_INVOKABLE bool exportKeptMarkdown(const QString &outPath);  // grouped Markdown; "" => <dataDir>/kept.md

    // Reader preferences (typography, etc.) persisted in the settings key/value table.
    Q_INVOKABLE QString setting(const QString &key, const QString &def = QString());
    Q_INVOKABLE void setSetting(const QString &key, const QString &value);

    // ---- kosync device integration ----
    // Every network call blocks (KosyncClient's 15s timeout) and fires ONLY on an explicit user
    // action (open/close/test/manual). No background polling — see the Phase 2b brief.
    Q_INVOKABLE QVariantMap syncConfig();  // {enabled,server,username,deviceName,configured}; no secrets
    Q_INVOKABLE void setSyncConfig(const QString &server, const QString &username,
                                   const QString &password, const QString &deviceName);
    Q_INVOKABLE void setSyncEnabled(bool enabled);
    Q_INVOKABLE QVariantMap testConnection();  // {ok,message} — GET /users/auth
    Q_INVOKABLE QVariantMap registerUser();    // {ok,message} — POST /users/create
    // Async: kicks off GET /syncs/progress and emits pullReady(bookId, result) when it returns —
    // never blocks the GUI thread. result = {state: Disabled|Failed|NoRemote|UpToDate|RemoteNewer,
    // spine, charOffset, percentage, device}; spine/charOffset are -1 when absent (foreign xpointer
    // has no offset -> jump by percentage). Disabled fires synchronously (no network).
    Q_INVOKABLE void pullOnOpen(qint64 bookId);
    // Fire-and-forget async PUT; marks the local row synced on success. Never blocks.
    Q_INVOKABLE void pushProgress(qint64 bookId, int spine, int charOffset, double percentage);
    Q_INVOKABLE void pushProgress(qint64 bookId);  // push the current saved local row
    Q_INVOKABLE void syncAllDirty();  // push every dirty progress row (dirty-retry)

    // ---- WebDAV full sync (Phase 3b) ----
    // Config lives in settings (webdav_url/webdav_user/webdav_pass; the app-password is stored
    // plaintext, device-local, like the kosync creds). syncNow() runs the whole engine on a
    // worker QThread and reports back via the sync* signals; the GUI never blocks on it.
    Q_INVOKABLE QVariantMap webdavConfig();  // {url,user,configured}; the password is never returned
    Q_INVOKABLE void setWebdavConfig(const QString &url, const QString &user, const QString &pass);
    Q_INVOKABLE QVariantMap testWebdav();    // {ok,message} — blocking PROPFIND, explicit user tap
    Q_INVOKABLE void syncNow();              // no-op if a run is already in flight

signals:
    void pullReady(qint64 bookId, QVariantMap result);
    void syncStarted();
    void syncProgress(const QString &step);
    void syncFinished(bool ok, const QVariantMap &summary);

private:
    QVariantList allBooksWithPct();  // cached; invalidated on any mutation
    void invalidate() { m_cacheValid = false; }

    bool syncUsable();                 // enabled + server + username + user_key all present
    ccx::kosync::Account account();    // from the settings table
    QString deviceId();                // persisted device_id (UUIDv4, dashes stripped); created once
    QVariantMap resolvePull(qint64 bookId, const ccx::kosync::Result &r);  // remoteNewer logic

    QString m_dataDir;  // app data dir; default target for exportKeptMarkdown
    Storage *m_storage = nullptr;
    Library *m_library = nullptr;
    QNetworkAccessManager *m_nam = nullptr;  // parented to this
    QVariantList m_cache;      // last allBooksWithPct() result
    bool m_cacheValid = false; // rebuilt only after import/delete/save/open/bookmark mutations
    bool m_syncing = false;    // guards syncNow() against concurrent WebDAV runs
};
