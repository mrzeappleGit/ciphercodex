#include "pdfview.h"

#include "pdfdocument.h"

#include <QImage>
#include <QMouseEvent>
#include <QPainter>
#include <QSizeF>

static constexpr double kTapSlop = 12.0;    // px: drag under this is a tap, not a swipe
static constexpr double kSwipeMin = 80.0;   // px: horizontal travel that turns a page
static constexpr double kZoomMin = 0.25;
static constexpr double kZoomMax = 8.0;

static constexpr int kSearchPagesPerTick = 4;  // keep the event loop responsive during search

PdfView::PdfView(QQuickItem *parent) : QQuickPaintedItem(parent)
{
    setAcceptedMouseButtons(Qt::LeftButton);  // pen/touch arrive as synthesized mouse
    setOpaquePainting(true);
    setFillColor(Qt::white);
    m_searchTimer.setInterval(0);  // fire between event-loop turns
    connect(&m_searchTimer, &QTimer::timeout, this, &PdfView::searchStep);
}

PdfView::~PdfView()
{
    delete m_doc;
}

void PdfView::setSource(const QString &path)
{
    if (path == m_source)
        return;
    m_source = path;
    emit sourceChanged();
    openDocument(path);
}

bool PdfView::openDocument(const QString &path)
{
    m_searchTimer.stop();  // a scan on the old doc must not outlive it
    m_searchQuery.clear();
    delete m_doc;
    QString err;
    m_doc = PdfDocument::open(path, &err);

    m_pageIndex = 0;
    m_pan = QPointF();
    if (m_zoom != 1.0) {
        m_zoom = 1.0;
        emit zoomChanged();
    }
    const int newCount = m_doc ? m_doc->pageCount() : 0;
    if (newCount != m_pageCount) {
        m_pageCount = newCount;
        emit pageCountChanged();
    }
    if (m_source != path) {  // openDocument() called directly, not via setSource()
        m_source = path;
        emit sourceChanged();
    }
    emit pageChanged();
    update();
    return m_doc != nullptr;
}

void PdfView::goToPage(int page)
{
    if (!m_doc)
        return;
    const int clamped = qBound(0, page, qMax(0, m_pageCount - 1));
    if (clamped == m_pageIndex)
        return;
    m_pageIndex = clamped;
    m_pan = QPointF();  // start each page centered
    emit pageChanged();
    update();  // whole-item repaint: one clean refresh per turn
}

void PdfView::setFitMode(FitMode m)
{
    if (m == m_fitMode)
        return;
    m_fitMode = m;
    m_pan = QPointF();
    if (m_zoom != 1.0) {  // choosing a fit re-fits: drop any manual zoom
        m_zoom = 1.0;
        emit zoomChanged();
    }
    emit fitModeChanged();
    update();
}

void PdfView::setZoom(double z)
{
    z = qBound(kZoomMin, z, kZoomMax);
    if (qFuzzyCompare(z, m_zoom))
        return;
    m_zoom = z;
    clampPan(displayRect());
    emit zoomChanged();
    update();
}

// Base fit size (zoom == 1) then scaled by zoom, positioned centered + panned.
QRectF PdfView::displayRect() const
{
    if (!m_doc)
        return QRectF();
    const QSizeF pt = m_doc->pageSizePt(m_pageIndex);
    if (pt.isEmpty() || width() <= 0 || height() <= 0)
        return QRectF();
    const double aspect = pt.height() / pt.width();

    double baseW = width();
    double baseH = baseW * aspect;
    if (m_fitMode == FitPage && baseH > height()) {
        baseH = height();
        baseW = baseH / aspect;
    }
    const double dispW = baseW * m_zoom;
    const double dispH = baseH * m_zoom;
    const double x = (width() - dispW) / 2 + m_pan.x();
    const double y = (height() - dispH) / 2 + m_pan.y();
    return QRectF(x, y, dispW, dispH);
}

void PdfView::clampPan(const QRectF &rect)
{
    // When a dimension overflows the viewport, keep its edges outside; otherwise center it.
    if (rect.width() > width()) {
        const double centered = (width() - rect.width()) / 2;
        m_pan.setX(qBound(width() - rect.width() - centered, m_pan.x(), -centered));
    } else {
        m_pan.setX(0);
    }
    if (rect.height() > height()) {
        const double centered = (height() - rect.height()) / 2;
        m_pan.setY(qBound(height() - rect.height() - centered, m_pan.y(), -centered));
    } else {
        m_pan.setY(0);
    }
}

void PdfView::paint(QPainter *painter)
{
    painter->fillRect(0, 0, int(width()), int(height()), Qt::white);
    if (!m_doc)
        return;
    const QRectF rect = displayRect();
    if (rect.isEmpty())
        return;
    // Render only the visible intersection of the scaled page and the viewport, so a zoomed
    // page never rasterizes more than ~screen pixels (bounds both memory and render time).
    const QSize full(qRound(rect.width()), qRound(rect.height()));
    const QRectF visF = rect.intersected(QRectF(0, 0, width(), height()));
    if (visF.isEmpty())
        return;
    const QRect src(qRound(visF.x() - rect.x()), qRound(visF.y() - rect.y()),
                    qRound(visF.width()), qRound(visF.height()));
    const QImage img = m_doc->renderView(m_pageIndex, full, src);
    if (img.isNull())
        return;
    painter->drawImage(QPointF(rect.x() + src.x(), rect.y() + src.y()), img);
}

QVariantList PdfView::outline() const
{
    QVariantList out;
    if (!m_doc)
        return out;
    for (const PdfOutline &o : m_doc->outline())
        out.append(QVariantMap{{QStringLiteral("title"), o.title},
                               {QStringLiteral("page"), o.pageIndex},
                               {QStringLiteral("level"), o.level}});
    return out;
}

void PdfView::startSearch(const QString &query)
{
    cancelSearch();
    m_searchQuery = query;
    m_searchPage = 0;
    if (!m_doc || query.isEmpty()) {
        emit searchFinished(0, false);
        return;
    }
    m_searchTimer.start();
}

void PdfView::cancelSearch()
{
    if (m_searchTimer.isActive()) {
        m_searchTimer.stop();
        emit searchFinished(m_pageCount, true);
    }
    m_searchQuery.clear();
}

void PdfView::searchStep()
{
    if (!m_doc || m_searchQuery.isEmpty()) {
        m_searchTimer.stop();
        return;
    }
    const int end = qMin(m_searchPage + kSearchPagesPerTick, m_pageCount);
    for (; m_searchPage < end; ++m_searchPage) {
        const int c = m_doc->searchPage(m_searchPage, m_searchQuery);
        if (c > 0)
            emit searchHit(m_searchPage, c);
    }
    if (m_searchPage >= m_pageCount) {
        m_searchTimer.stop();
        emit searchFinished(m_pageCount, false);
    }
}

void PdfView::mousePressEvent(QMouseEvent *e)
{
    m_pressPos = m_lastPos = e->position();
    m_moved = false;
    e->accept();
}

void PdfView::mouseMoveEvent(QMouseEvent *e)
{
    const QPointF pos = e->position();
    const QPointF delta = pos - m_lastPos;
    m_lastPos = pos;
    if ((pos - m_pressPos).manhattanLength() > kTapSlop)
        m_moved = true;

    const QRectF rect = displayRect();
    const bool pannable = rect.width() > width() + 0.5 || rect.height() > height() + 0.5;
    if (pannable && m_moved) {
        m_pan += delta;
        clampPan(displayRect());
        update();
    }
    e->accept();
}

void PdfView::mouseReleaseEvent(QMouseEvent *e)
{
    const QPointF pos = e->position();
    const QPointF total = pos - m_pressPos;
    const QRectF rect = displayRect();
    const bool pannable = rect.width() > width() + 0.5 || rect.height() > height() + 0.5;

    if (!m_moved) {
        // Tap-zones (work zoomed or not, either hand): left third prev, right third next.
        if (pos.x() < width() / 3.0)
            prev();
        else if (pos.x() > width() * 2.0 / 3.0)
            next();
    } else if (!pannable && qAbs(total.x()) > kSwipeMin && qAbs(total.x()) > qAbs(total.y())) {
        // Horizontal swipe turns the page only when the page isn't being panned.
        if (total.x() < 0)
            next();  // content swept left
        else
            prev();
    }
    e->accept();
}

void PdfView::geometryChange(const QRectF &newGeometry, const QRectF &oldGeometry)
{
    QQuickPaintedItem::geometryChange(newGeometry, oldGeometry);
    if (newGeometry.size() != oldGeometry.size()) {
        clampPan(displayRect());
        update();
    }
}
