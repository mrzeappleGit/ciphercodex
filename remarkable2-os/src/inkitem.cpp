#include "inkitem.h"

#include <QPainter>
#include <QPen>

InkItem::InkItem(QQuickItem *parent) : QQuickPaintedItem(parent)
{
    setOpaquePainting(true);
}

void InkItem::ensureBuffer()
{
    const QSize sz(qMax(1, int(width())), qMax(1, int(height())));
    if (m_buffer.size() == sz)
        return;
    QImage fresh(sz, QImage::Format_Grayscale8);
    fresh.fill(Qt::white);
    if (!m_buffer.isNull()) {
        QPainter p(&fresh);
        p.drawImage(0, 0, m_buffer);
    }
    m_buffer = fresh;
}

void InkItem::geometryChange(const QRectF &newGeometry, const QRectF &oldGeometry)
{
    QQuickPaintedItem::geometryChange(newGeometry, oldGeometry);
    ensureBuffer();
}

void InkItem::paint(QPainter *painter)
{
    if (!m_buffer.isNull())
        painter->drawImage(0, 0, m_buffer);
}

void InkItem::drawSegment(const QPointF &from, const QPointF &to, qreal pressure)
{
    ensureBuffer();
    const qreal w = 1.0 + pressure * pressure * 10.0; // squared: light touch stays fine, lean gets bold
    QPainter p(&m_buffer);
    p.setRenderHint(QPainter::Antialiasing, false); // 1-bit-ish ink, faster on e-ink
    p.setPen(QPen(Qt::black, w, Qt::SolidLine, Qt::RoundCap, Qt::RoundJoin));
    p.drawLine(from, to);
    p.end();
    const int m = int(w) + 2;
    update(QRectF(from, to).normalized().adjusted(-m, -m, m, m).toRect());
}

void InkItem::penDown(qreal x, qreal y, qreal pressure)
{
    const QPointF local = mapFromScene(QPointF(x, y));
    drawSegment(local, local, pressure);
    m_last = local;
    m_down = true;
}

void InkItem::penMove(qreal x, qreal y, qreal pressure)
{
    const QPointF local = mapFromScene(QPointF(x, y));
    if (!m_down) {
        m_last = local;
        m_down = true;
    }
    drawSegment(m_last, local, pressure);
    m_last = local;
}

void InkItem::penUp()
{
    m_down = false;
}

void InkItem::clear()
{
    m_buffer.fill(Qt::white);
    update();
}
