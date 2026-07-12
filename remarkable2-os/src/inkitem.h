#pragma once
#include <QHash>
#include <QImage>
#include <QQmlEngine>
#include <QQuickPaintedItem>
#include <QSet>

#include "storage/storage.h"

class QPainter;

// Renders pen strokes into an image buffer with per-segment dirty-rect updates.
// qsgepaper (the epaper scenegraph backend) drops custom QSGGeometry/materials but
// renders image nodes, so QQuickPaintedItem is the supported path (verified on device:
// geometry nodes drew nothing, text/rects drew fine).
// Vector-backed: keeps an id->StrokeData map mirroring the DB; the raster buffer is a cache.
class InkItem : public QQuickPaintedItem
{
    Q_OBJECT
    QML_ELEMENT
    Q_PROPERTY(int tool READ tool WRITE setTool NOTIFY toolChanged) // 0 pencil, 1 stroke-eraser

public:
    explicit InkItem(QQuickItem *parent = nullptr);

    void paint(QPainter *painter) override;

    int tool() const { return m_tool; }
    void setTool(int t);

    // Controller-facing API (C++). DB is the source of truth; these keep the map+buffer in sync.
    void setStrokes(const QVector<StrokeData> &strokes);  // load + full re-render
    void commitStroke(const StrokeData &s);               // register pen-drawn stroke (already painted)
    void addStrokes(const QVector<StrokeData> &strokes);  // insert + paint (redo add / undo erase)
    void removeStrokes(const QVector<qint64> &ids);       // remove + re-render
    QVector<StrokeData> strokesById(const QVector<qint64> &ids) const;

public slots:
    // Defaults keep legacy 3-arg callers working; rawPressure<=0 back-converts from pressure.
    void penDown(qreal x, qreal y, qreal pressure,
                 int rawPressure = 0, int tiltX = 0, int tiltY = 0, quint32 tMs = 0);
    void penMove(qreal x, qreal y, qreal pressure,
                 int rawPressure = 0, int tiltX = 0, int tiltY = 0, quint32 tMs = 0);
    void penUp();
    void clear();

signals:
    void toolChanged();
    void strokeFinished(const StrokeData &s);        // pen-up; pts page-normalized 0..1, id unset
    void strokesErased(const QVector<qint64> &ids);  // stroke-eraser hits, emitted on pen-up

protected:
    void geometryChange(const QRectF &newGeometry, const QRectF &oldGeometry) override;

private:
    struct Entry {
        StrokeData s;
        QRectF bounds; // normalized, pre-check for eraser hit-testing
    };

    void ensureBuffer();
    qreal pageHeight() const;
    QRectF pixelBounds(const QRectF &normBounds) const;
    QRect drawSegment(const QPointF &from, const QPointF &to, qreal pressure,
                      const QColor &color = Qt::black);
    QRect paintStroke(QPainter &p, const StrokeData &s, const QColor &color) const;
    QRect drawStroke(const StrokeData &s, const QColor &color);
    void renderAll();
    void insertEntry(const StrokeData &s);
    void eraseHitTest(const QPointF &px);
    void recordPoint(const QPointF &local, qreal pressure, int rawPressure,
                     int tiltX, int tiltY, quint32 tMs);

    QImage m_buffer;
    QPointF m_last;
    bool m_down = false;
    int m_tool = 0;
    int m_activeTool = 0; // latched at pen-down so mid-stroke tool switches can't corrupt state
    QHash<qint64, Entry> m_strokes;
    StrokeData m_current;
    quint32 m_t0 = 0; // tMs of pen-down; stored point times are relative to stroke start
    QSet<qint64> m_pendingErase;
};
