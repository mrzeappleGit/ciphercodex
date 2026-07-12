#pragma once
#include <QImage>
#include <QQuickPaintedItem>
#include <QQmlEngine>

// Renders pen strokes into an image buffer with per-segment dirty-rect updates.
// qsgepaper (the epaper scenegraph backend) drops custom QSGGeometry/materials but
// renders image nodes, so QQuickPaintedItem is the supported path (verified on device:
// geometry nodes drew nothing, text/rects drew fine).
class InkItem : public QQuickPaintedItem
{
    Q_OBJECT
    QML_ELEMENT

public:
    explicit InkItem(QQuickItem *parent = nullptr);

    void paint(QPainter *painter) override;

public slots:
    void penDown(qreal x, qreal y, qreal pressure);
    void penMove(qreal x, qreal y, qreal pressure);
    void penUp();
    void clear();

protected:
    void geometryChange(const QRectF &newGeometry, const QRectF &oldGeometry) override;

private:
    void ensureBuffer();
    void drawSegment(const QPointF &from, const QPointF &to, qreal pressure);

    QImage m_buffer;
    QPointF m_last;
    bool m_down = false;
};
