#pragma once
#include <QObject>
#include <QPointer>
#include <QQmlEngine>
#include <QVariantList>
#include <QVector>

#include "inkitem.h"
#include "penreader.h"
#include "storage/storage.h"

// Mediates InkItem <-> Storage. DB always reflects the visible state: every stroke/erase
// commits before the undo stack records it, so a crash preserves exactly what's drawn.
class NotebookController : public QObject
{
    Q_OBJECT
    QML_ELEMENT
    Q_PROPERTY(bool canUndo READ canUndo NOTIFY undoChanged)
    Q_PROPERTY(bool canRedo READ canRedo NOTIFY undoChanged)

public:
    explicit NotebookController(QObject *parent = nullptr);
    ~NotebookController() override;

    bool canUndo() const { return !m_undo.isEmpty(); }
    bool canRedo() const { return !m_redo.isEmpty(); }

    Q_INVOKABLE QVariantList notebooks(); // [{id,title,pageCount}]
    Q_INVOKABLE qint64 createNotebook(const QString &title);
    Q_INVOKABLE void deleteNotebook(qint64 id);
    Q_INVOKABLE QVariantList pages(qint64 notebookId); // [{id,seq}]
    Q_INVOKABLE qint64 createPage(qint64 notebookId);
    // pen connects to canvas in C++: no per-sample QML/JS hop, no dropped signal args
    Q_INVOKABLE void openPage(qint64 pageId, InkItem *canvas, PenReader *pen);
    Q_INVOKABLE void undo();
    Q_INVOKABLE void redo();
    Q_INVOKABLE bool exportNotebookPdf(qint64 notebookId, const QString &outPath);

signals:
    void undoChanged();

private:
    // Two op kinds; both carry full stroke copies so either direction can restore with ids.
    struct Op {
        enum Type { Add, Erase };
        int type;
        QVector<StrokeData> strokes;
    };

    void onStrokeFinished(const StrokeData &s);
    void onStrokesErased(const QVector<qint64> &ids);
    void pushUndo(Op op);
    bool apply(const Op &op, bool undoing);
    void resyncCanvas(); // storage failed mid-op: re-load truth from DB
    qint64 opPoints(const Op &op) const;

    Storage *m_storage = nullptr;
    QPointer<InkItem> m_canvas;   // StackView owns/destroys pages; auto-nulls
    QPointer<PenReader> m_pen;
    qint64 m_pageId = -1;
    QVector<Op> m_undo, m_redo;   // per page, cleared on openPage
    qint64 m_undoPoints = 0;      // retained StrokePoints across m_undo, memory bound
};
