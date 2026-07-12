#pragma once

#include <QObject>
#include <QString>
#include <QVariantMap>

#include "sync/webdav.h"  // WebDavConfig, WebDavClient

// Full books + notes WebDAV sync, run on a worker QThread (see docs/phase3b-contracts.md).
// One run() = ensure remote dirs -> upload book files the remote lacks -> pull+merge every
// device snapshot (last-writer-wins) -> download+attach any book file the merged rows now
// reference -> publish this device's own snapshot. ALL network + DB work happens on the
// worker thread; the GUI only receives progress()/finished(). The engine opens its OWN
// Storage connection in run() (WAL lets a second connection coexist with the GUI's), so
// nothing is shared across the thread boundary.
class SyncEngine : public QObject
{
    Q_OBJECT
public:
    explicit SyncEngine(QObject *parent = nullptr) : QObject(parent) {}

    // Invoked on the worker thread (wired to QThread::started). Emits finished() exactly once.
    Q_INVOKABLE void run(WebDavConfig cfg, QString deviceId, QString dataDir);

signals:
    void progress(const QString &step);
    // summary: {booksUp, booksDown, entities, tombstones, error}
    void finished(bool ok, const QVariantMap &summary);
};
