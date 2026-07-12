#pragma once

#include <QMutex>
#include <QString>
#include <QVector>

class Storage;

// lastOpenedAt == 0 => never opened (stored as NULL in the DB).
struct BookRow {
    qint64 id;
    QString title, author, filePath, digest, coverPath;
    qint64 sizeBytes, addedAt, lastOpenedAt;
    int format;  // 0 pdf, 1 epub
};

struct Bookmark {
    qint64 id, bookId;
    int spineIndex, charOffset;
    double percentage;
    QString label;
    qint64 createdAt;
};

enum ImportResult { Imported, Duplicate, Failed };

// Owns a Storage* (uses handle() for its own statements) and the on-disk book files.
// Layout: <dataDir>/inbox (drop zone), <dataDir>/books/<digest>.pdf|.epub (finals) and
// import-*.tmp (in-flight), <dataDir>/covers/<digest>.png.
class Library {
public:
    explicit Library(Storage *s, const QString &dataDir);

    struct ImportSummary { int imported, duplicates, failed; };
    // Import every *.pdf/*.epub in inbox: partial-MD5 dedupe, atomic copy-in, metadata+cover,
    // insert row. Serialized; every failure path removes the temp; never modifies the source.
    ImportSummary importInbox();

    QVector<BookRow> books();
    // BookRow plus its progress in one LEFT JOIN (avoids 1+N queries for the library view).
    struct BookWithProgress {
        BookRow book;
        bool hasProgress;
        double percentage;  // 0 if no progress row
    };
    QVector<BookWithProgress> booksWithProgress();
    void markOpened(qint64 id);
    void deleteBook(qint64 id);  // FK cascade + unlink file_path & cover_path

    struct Progress {
        int spineIndex, charOffset;
        double percentage;
        qint64 updatedAt, syncedAt;  // syncedAt == 0 => never synced (NULL)
        bool exists;
    };
    Progress progress(qint64 bookId);
    // Upsert; preserves synced_at (never touched here), updated_at = now.
    void saveProgress(qint64 bookId, int spineIndex, int charOffset, double percentage);

    QVector<Bookmark> bookmarks(qint64 bookId);
    qint64 addBookmark(qint64 bookId, int spineIndex, int charOffset, double percentage,
                       const QString &label);
    void deleteBookmark(qint64 id);

    // settings key/value store (used for kosync config, typography, device_id).
    QString setting(const QString &key, const QString &def = QString());  // def if key absent
    void setSetting(const QString &key, const QString &value);            // upsert

    QString digestOf(qint64 bookId);  // "" if no such book
    BookRow bookById(qint64 id);      // .id == -1 if no such book

    // Conditional sync marker: stamp synced_at = updatedAt only while updated_at is unchanged,
    // so a save that landed during an in-flight push stays dirty (mirrors Android markSynced).
    void markSynced(qint64 bookId, qint64 updatedAt);
    // Dirty rows to push: synced_at IS NULL OR synced_at < updated_at.
    QVector<qint64> dirtyProgressBookIds();

private:
    ImportResult importFile(const QString &srcPath);
    void reconcileOrphans();  // delete book files with no DB row (crash between rename + commit)
    QString inboxDir() const;
    QString booksDir() const;
    QString coversDir() const;

    Storage *m_storage;
    QString m_dataDir;
    QMutex m_importMutex;  // ponytail: global import lock, fine — imports are user-triggered, rare
};
