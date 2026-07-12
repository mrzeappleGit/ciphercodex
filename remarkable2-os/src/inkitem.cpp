#include "inkitem.h"

#include <QPainter>
#include <QPen>

static constexpr qreal ERASE_RADIUS = 6.0;   // px, per contract
static constexpr int   MAX_PEN_MARGIN = 16;  // > max pen width (11px) + rounding
static constexpr qreal PAGE_ASPECT = 1872.0 / 1404.0; // contract: pts normalized to page, not item

static qreal distSqToSegment(const QPointF &p, const QPointF &a, const QPointF &b)
{
    const QPointF ab = b - a;
    const qreal len2 = QPointF::dotProduct(ab, ab);
    const qreal t = len2 > 0 ? qBound<qreal>(0, QPointF::dotProduct(p - a, ab) / len2, 1) : 0;
    const QPointF d = p - (a + t * ab);
    return QPointF::dotProduct(d, d);
}

static qreal strokeWidth(qreal pressure)
{
    return 1.0 + pressure * pressure * 10.0; // squared: light touch stays fine, lean gets bold
}

InkItem::InkItem(QQuickItem *parent) : QQuickPaintedItem(parent)
{
    setOpaquePainting(true);
}

// Normalization denominator for y: the full 1404x1872 page, not the (toolbar-clipped)
// item height — stored data must honor the documented page aspect (PDF export relies on it).
qreal InkItem::pageHeight() const
{
    return qMax<qreal>(1, width()) * PAGE_ASPECT;
}

void InkItem::setTool(int t)
{
    if (t == m_tool)
        return;
    m_tool = t;
    emit toolChanged();
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
    if (newGeometry.size() != oldGeometry.size() && !m_strokes.isEmpty()) {
        renderAll(); // normalized->pixel mapping changed
        update();
    } else {
        ensureBuffer();
    }
}

void InkItem::paint(QPainter *painter)
{
    if (!m_buffer.isNull())
        painter->drawImage(0, 0, m_buffer);
}

QRect InkItem::drawSegment(const QPointF &from, const QPointF &to, qreal pressure,
                           const QColor &color)
{
    ensureBuffer();
    const qreal w = strokeWidth(pressure);
    QPainter p(&m_buffer);
    p.setRenderHint(QPainter::Antialiasing, false); // 1-bit-ish ink, faster on e-ink
    p.setPen(QPen(color, w, Qt::SolidLine, Qt::RoundCap, Qt::RoundJoin));
    p.drawLine(from, to);
    p.end();
    const int m = int(w) + 2;
    return QRectF(from, to).normalized().adjusted(-m, -m, m, m).toRect();
}

// Draw a whole stored stroke with an already-open painter; returns pixel dirty rect.
QRect InkItem::paintStroke(QPainter &p, const StrokeData &s, const QColor &color) const
{
    QRect dirty;
    if (s.pts.isEmpty())
        return dirty;
    const qreal w = qMax<qreal>(1, width()), ph = pageHeight();
    QPen pen(color, 1, Qt::SolidLine, Qt::RoundCap, Qt::RoundJoin);
    QPointF prev(s.pts[0].x * w, s.pts[0].y * ph);
    for (int i = 0; i < s.pts.size(); ++i) {
        const QPointF cur(s.pts[i].x * w, s.pts[i].y * ph);
        const qreal sw = strokeWidth(s.pts[i].pressure / 4095.0);
        pen.setWidthF(sw);
        p.setPen(pen);
        p.drawLine(prev, cur);
        const int m = int(sw) + 2;
        dirty |= QRectF(prev, cur).normalized().adjusted(-m, -m, m, m).toRect();
        prev = cur;
    }
    return dirty;
}

QRect InkItem::drawStroke(const StrokeData &s, const QColor &color)
{
    ensureBuffer();
    QPainter p(&m_buffer);
    p.setRenderHint(QPainter::Antialiasing, false);
    return paintStroke(p, s, color);
}

void InkItem::renderAll()
{
    ensureBuffer();
    m_buffer.fill(Qt::white);
    QPainter p(&m_buffer);
    p.setRenderHint(QPainter::Antialiasing, false);
    for (auto it = m_strokes.cbegin(); it != m_strokes.cend(); ++it)
        paintStroke(p, it->s, Qt::black);
}

QRectF InkItem::pixelBounds(const QRectF &normBounds) const
{
    const qreal w = qMax<qreal>(1, width()), ph = pageHeight();
    return QRectF(normBounds.x() * w, normBounds.y() * ph,
                  normBounds.width() * w, normBounds.height() * ph);
}

void InkItem::insertEntry(const StrokeData &s)
{
    Entry e{s, QRectF()};
    if (!s.pts.isEmpty()) {
        float minX = s.pts[0].x, maxX = minX, minY = s.pts[0].y, maxY = minY;
        for (const StrokePoint &p : s.pts) {
            minX = qMin(minX, p.x); maxX = qMax(maxX, p.x);
            minY = qMin(minY, p.y); maxY = qMax(maxY, p.y);
        }
        e.bounds = QRectF(minX, minY, maxX - minX, maxY - minY);
    }
    m_strokes.insert(s.id, e);
}

void InkItem::setStrokes(const QVector<StrokeData> &strokes)
{
    m_strokes.clear();
    m_pendingErase.clear();
    // reset in-flight pen state: a stroke mid-draw during a page switch must not
    // be committed to the new page with old-page coordinates
    m_down = false;
    m_current = StrokeData();
    for (const StrokeData &s : strokes)
        insertEntry(s);
    renderAll();
    update();
}

void InkItem::commitStroke(const StrokeData &s)
{
    insertEntry(s); // already on the buffer from live drawing
}

void InkItem::addStrokes(const QVector<StrokeData> &strokes)
{
    if (strokes.isEmpty())
        return;
    ensureBuffer();
    QPainter p(&m_buffer);
    p.setRenderHint(QPainter::Antialiasing, false);
    QRect dirty;
    for (const StrokeData &s : strokes) {
        insertEntry(s);
        dirty |= paintStroke(p, s, Qt::black);
    }
    if (!dirty.isNull())
        update(dirty);
}

void InkItem::removeStrokes(const QVector<qint64> &ids)
{
    QRectF dirtyF;
    bool removed = false;
    for (qint64 id : ids) {
        const auto it = m_strokes.constFind(id);
        if (it == m_strokes.cend())
            continue;
        dirtyF |= pixelBounds(it->bounds);
        m_strokes.remove(id);
        m_pendingErase.remove(id);
        removed = true;
    }
    if (!removed)
        return;
    ensureBuffer();
    const QRect all(QPoint(0, 0), m_buffer.size());
    const QRect dirty = dirtyF.toRect()
        .adjusted(-MAX_PEN_MARGIN, -MAX_PEN_MARGIN, MAX_PEN_MARGIN, MAX_PEN_MARGIN) & all;
    // repaint only the erased region; a dense page pays for its own strokes, not all of them
    if (qint64(dirty.width()) * dirty.height() > qint64(all.width()) * all.height() * 3 / 5) {
        renderAll();
        update(dirty);
        return;
    }
    QPainter p(&m_buffer);
    p.setRenderHint(QPainter::Antialiasing, false);
    p.setClipRect(dirty);
    p.fillRect(dirty, Qt::white);
    for (auto it = m_strokes.cbegin(); it != m_strokes.cend(); ++it) {
        if (pixelBounds(it->bounds)
                .adjusted(-MAX_PEN_MARGIN, -MAX_PEN_MARGIN, MAX_PEN_MARGIN, MAX_PEN_MARGIN)
                .intersects(dirty))
            paintStroke(p, it->s, m_pendingErase.contains(it.key()) ? QColor(128, 128, 128)
                                                                    : QColor(Qt::black));
    }
    update(dirty);
}

QVector<StrokeData> InkItem::strokesById(const QVector<qint64> &ids) const
{
    QVector<StrokeData> out;
    out.reserve(ids.size());
    for (qint64 id : ids) {
        const auto it = m_strokes.constFind(id);
        if (it != m_strokes.cend())
            out.append(it->s);
    }
    return out;
}

void InkItem::recordPoint(const QPointF &local, qreal pressure, int rawPressure,
                          int tiltX, int tiltY, quint32 tMs)
{
    StrokePoint pt;
    pt.x = float(local.x() / qMax<qreal>(1, width()));
    pt.y = float(local.y() / pageHeight());
    pt.pressure = quint16(rawPressure > 0 ? rawPressure : int(pressure * 4095.0 + 0.5));
    pt.tiltX = qint16(tiltX);
    pt.tiltY = qint16(tiltY);
    pt.tMs = tMs - m_t0;
    m_current.pts.append(pt);
}

void InkItem::eraseHitTest(const QPointF &px)
{
    const qreal w = qMax<qreal>(1, width()), ph = pageHeight();
    const qreal r2 = ERASE_RADIUS * ERASE_RADIUS;
    QRect dirty;
    for (auto it = m_strokes.cbegin(); it != m_strokes.cend(); ++it) {
        if (m_pendingErase.contains(it.key()))
            continue;
        // bounds pre-check keeps the per-sample cost O(strokes), not O(points)
        if (!pixelBounds(it->bounds)
                 .adjusted(-MAX_PEN_MARGIN, -MAX_PEN_MARGIN, MAX_PEN_MARGIN, MAX_PEN_MARGIN)
                 .contains(px))
            continue;
        const auto &pts = it->s.pts;
        bool hit = false;
        // ponytail: point-vs-polyline test per sample (~200 Hz); segment-vs-segment if fast
        // sweeps ever skip strokes in practice
        for (int i = 0; i < pts.size() && !hit; ++i) {
            const int j = qMax(0, i - 1);
            hit = distSqToSegment(px, QPointF(pts[j].x * w, pts[j].y * ph),
                                  QPointF(pts[i].x * w, pts[i].y * ph)) < r2;
        }
        if (hit) {
            m_pendingErase.insert(it.key());
            dirty |= drawStroke(it->s, QColor(128, 128, 128)); // 50% gray until pen-up commits
        }
    }
    if (!dirty.isNull())
        update(dirty);
}

void InkItem::penDown(qreal x, qreal y, qreal pressure,
                      int rawPressure, int tiltX, int tiltY, quint32 tMs)
{
    const QPointF local = mapFromScene(QPointF(x, y));
    m_down = true;
    m_last = local;
    m_activeTool = m_tool;
    if (m_activeTool == 1) {
        eraseHitTest(local);
        return;
    }
    m_current = StrokeData();
    m_t0 = tMs;
    recordPoint(local, pressure, rawPressure, tiltX, tiltY, tMs);
    update(drawSegment(local, local, pressure));
}

void InkItem::penMove(qreal x, qreal y, qreal pressure,
                      int rawPressure, int tiltX, int tiltY, quint32 tMs)
{
    if (!m_down) {
        penDown(x, y, pressure, rawPressure, tiltX, tiltY, tMs);
        return;
    }
    const QPointF local = mapFromScene(QPointF(x, y));
    if (m_activeTool == 1) {
        eraseHitTest(local);
    } else {
        recordPoint(local, pressure, rawPressure, tiltX, tiltY, tMs);
        update(drawSegment(m_last, local, pressure));
    }
    m_last = local;
}

void InkItem::penUp()
{
    if (!m_down)
        return;
    m_down = false;
    if (m_activeTool == 1) {
        if (!m_pendingErase.isEmpty()) {
            const QVector<qint64> ids(m_pendingErase.cbegin(), m_pendingErase.cend());
            m_pendingErase.clear();
            emit strokesErased(ids); // controller removes from DB then calls removeStrokes()
        }
        return;
    }
    if (!m_current.pts.isEmpty())
        emit strokeFinished(m_current); // controller persists, then calls commitStroke() with the id
    m_current.pts.clear();
}

void InkItem::clear()
{
    m_strokes.clear();
    m_pendingErase.clear();
    m_current.pts.clear();
    m_down = false;
    ensureBuffer();
    m_buffer.fill(Qt::white);
    update();
}
