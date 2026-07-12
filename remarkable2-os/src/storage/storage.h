#pragma once

#include <QString>
#include <QVector>

struct sqlite3;

struct StrokePoint { float x, y; quint16 pressure; qint16 tiltX, tiltY; quint32 tMs; };
struct StrokeData {
    qint64 id = -1;
    qint64 pageId = -1;
    int tool = 0;  // 0 pencil, 1 eraser-… later
    float baseWidth = 2.0f;
    QVector<StrokePoint> pts;
};
struct PageInfo { qint64 id; int seq; };
struct NotebookInfo { qint64 id; QString title; int pageCount; qint64 updatedAt; };

// SQLite-backed store: WAL + synchronous=FULL + foreign_keys=ON.
// Every mutating method is one committed transaction — a crash at any moment
// preserves exactly the strokes whose appendStroke() already returned.
class Storage {
public:
    static Storage *open(const QString &dbDir, QString *error);  // creates dir, db, runs migrations
    ~Storage();
    Storage(const Storage &) = delete;
    Storage &operator=(const Storage &) = delete;

    QVector<NotebookInfo> notebooks();
    qint64 createNotebook(const QString &title);
    void renameNotebook(qint64 id, const QString &title);
    void deleteNotebook(qint64 id);               // cascades pages+strokes
    QVector<PageInfo> pages(qint64 notebookId);
    qint64 createPage(qint64 notebookId);         // appends at end, returns id
    void deletePage(qint64 pageId);
    qint64 appendStroke(const StrokeData &s);     // returns rowid; THE journal write
    bool removeStrokes(const QVector<qint64> &ids);
    bool restoreStrokes(QVector<StrokeData> &s);  // re-insert with original ids
    // Area-erase commit: delete removeIds and insert fragments, one transaction.
    // keepAddIds=false assigns fresh ids into `add`; true re-inserts with their ids (undo/redo).
    bool replaceStrokes(const QVector<qint64> &removeIds, QVector<StrokeData> &add,
                        bool keepAddIds);
    QVector<StrokeData> strokes(qint64 pageId);   // ordered by id

private:
    explicit Storage(sqlite3 *db) : m_db(db) {}
    sqlite3 *m_db;
};
