#pragma once

#include <QJsonObject>
#include <QString>
#include <QStringList>
#include <QVector>

class Storage;

// DB side of Phase 3b sync: serialize the local synced rows to a snapshot JSON and merge foreign
// device snapshots back into the DB by last-writer-wins on updated_at. Owns a Storage* (in the app
// it opens its OWN connection on the sync thread; host tests pass a shared one). All SQL runs
// against Storage::handle() with the same Stmt/Tx pattern as library.cpp — no Qt6Network here, so
// the whole merge is host-testable without a server. Identity rules (frozen contract phase3b):
// books/progress merge by digest, everything else by guid; children carry their parent's
// digest/guid and are resolved to local ids on apply.
class SyncStore {
public:
    explicit SyncStore(Storage *s) : m_storage(s) {}

    // Every synced row (incl. tombstones) as the snapshot JSON documented in phase3b-contracts.md.
    QJsonObject exportSnapshot(const QString &deviceId);

    struct MergeStats {
        int booksNeeded = 0;        // == missingDigests.size(); live books lacking a local file
        int entitiesApplied = 0;    // live rows inserted/updated this pass
        int tombstonesApplied = 0;  // deleted rows inserted/updated this pass
        QStringList missingDigests; // book files the engine must fetch + attach
    };
    // Merge remoteSnapshots (excluding our own deviceId) into the local DB in one transaction,
    // dependency order books->collections->notebooks->pages->strokes->progress/bookmarks/
    // highlights/sessions/bookCollections. LWW: apply a record only when its updatedAt beats the
    // local row (or the row is absent); on a tie the tombstone wins. A book we lack is inserted
    // with an EMPTY file_path and its digest returned in missingDigests. A child whose parent is
    // absent locally is skipped (a later sync converges) — never inserted as an orphan.
    MergeStats applyMerged(const QVector<QJsonObject> &remoteSnapshots, const QString &deviceId);

    // Point a book row (matched by digest) at its now-local file + cover. Paths are device-local,
    // NOT synced, so this does not bump updated_at.
    void attachBookFile(const QString &digest, const QString &filePath, const QString &coverPath);

    QStringList localBookDigestsWithFile();  // live books with a local file (upload candidates)
    QString filePathForDigest(const QString &digest);  // "" if unknown
    QString extForDigest(const QString &digest);        // "pdf"/"epub" (format 1 => epub)

private:
    Storage *m_storage;
};
