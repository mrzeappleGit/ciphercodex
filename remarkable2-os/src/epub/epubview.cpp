#include "epub/epubview.h"

#include "epub/epubdocument.h"
#include "epub/epubrender.h"

#include <QImage>
#include <QMouseEvent>
#include <QPainter>
#include <QSizeF>

// Mirrors PdfView: L/R tap-thirds + horizontal swipe turn pages, chunked search on a
// zero-interval timer, one whole-item repaint per turn (reading wants quality, no fast pen
// waveform). Cross-chapter turns, follow/return stack, and whole-book percentage port the
// Android ReaderViewModel.

static constexpr double kTapSlop = 12.0;    // px: drag under this is a tap, not a swipe
static constexpr double kSwipeMin = 80.0;   // px: horizontal travel that turns a page
static constexpr int kRendererCache = 2;    // current + the neighbor you came from (LRU)

static constexpr int kReturnStackMax = 10;  // RETURN_STACK_MAX
static constexpr int kNoteMaxChars = 600;   // NOTE_MAX_CHARS: longer target -> jump, not popup
static constexpr int kMinSearchLen = 2;     // MIN_SEARCH_LEN
static constexpr int kMaxSearchHits = 300;  // MAX_SEARCH_HITS
static constexpr int kSearchContext = 36;   // SEARCH_CONTEXT chars each side of a match

EpubView::EpubView(QQuickItem *parent) : QQuickPaintedItem(parent)
{
    setAcceptedMouseButtons(Qt::LeftButton);  // pen/touch arrive as synthesized mouse
    setOpaquePainting(true);
    setFillColor(Qt::white);
    m_searchTimer.setInterval(0);  // fire between event-loop turns
    connect(&m_searchTimer, &QTimer::timeout, this, &EpubView::searchStep);
}

EpubView::~EpubView()
{
    delete m_doc;
}

int EpubView::spineCount() const
{
    return m_doc ? m_doc->spineCount() : 0;
}

int EpubView::pagesInSpine()
{
    EpubRenderer *r = currentRenderer();
    return r ? r->pageCount() : 0;
}

EpubRenderer *EpubView::rendererFor(int spine)
{
    auto it = m_renderers.constFind(spine);
    if (it != m_renderers.constEnd()) {
        m_rendererOrder.removeOne(spine);
        m_rendererOrder.prepend(spine);
        return it.value().get();
    }
    auto r = std::make_shared<EpubRenderer>();
    r->setChapter(m_doc->chapter(spine));
    r->setTypography(m_family, m_bodyPx, m_lineSpacing, m_marginPx, m_justify);
    r->setViewport(width(), height());
    m_renderers.insert(spine, r);
    m_rendererOrder.prepend(spine);
    while (m_rendererOrder.size() > kRendererCache)
        m_renderers.remove(m_rendererOrder.takeLast());
    return r.get();
}

bool EpubView::openDocument(const QString &path)
{
    m_searchTimer.stop();  // a scan on the old doc must not outlive it
    m_searchQuery.clear();
    m_renderers.clear();
    m_rendererOrder.clear();
    m_returnStack.clear();
    updateCanReturn();

    delete m_doc;
    QString err;
    m_doc = EpubDocument::open(path, &err);
    m_spineIndex = 0;
    m_pageInSpine = 0;

    emit sourceChanged();  // spineCount changed
    emit spineChanged();
    if (m_doc) {
        emitTurn(true);  // lands on spine 0 page 0
    } else {
        m_percentage = 0;
        emit pageChanged();
        emit percentageChanged();
        update();
    }
    return m_doc != nullptr;
}

void EpubView::next()
{
    if (!m_doc)
        return;
    EpubRenderer *r = currentRenderer();
    if (m_pageInSpine + 1 < r->pageCount()) {
        ++m_pageInSpine;
        emitTurn(true);
    } else if (m_spineIndex + 1 < spineCount()) {
        m_spineIndex += 1;
        m_pageInSpine = 0;
        emit spineChanged();
        emitTurn(true);
    }
}

void EpubView::prev()
{
    if (!m_doc)
        return;
    if (m_pageInSpine > 0) {
        --m_pageInSpine;
        emitTurn(true);
    } else if (m_spineIndex > 0) {
        m_spineIndex -= 1;
        emit spineChanged();
        EpubRenderer *r = currentRenderer();  // previous chapter's last page
        m_pageInSpine = qMax(0, r->pageCount() - 1);
        emitTurn(true);
    }
}

void EpubView::goToLocation(int spine, int charOffset)
{
    if (!m_doc || spineCount() == 0)
        return;
    const int clamped = qBound(0, spine, spineCount() - 1);
    if (clamped != m_spineIndex) {
        m_spineIndex = clamped;
        emit spineChanged();
    }
    EpubRenderer *r = currentRenderer();
    m_pageInSpine = r->pageIndexForOffset(qMax(0, charOffset));
    emitTurn(true);
}

void EpubView::setTypography(const QString &family, int bodyPx, double lineSpacing, int marginPx,
                             bool justify)
{
    m_family = family;
    m_bodyPx = bodyPx;
    m_lineSpacing = lineSpacing;
    m_marginPx = marginPx;
    m_justify = justify;
    reflowKeepingPosition();
}

// Re-lay out the current chapter (new typography or viewport) but stay on the same built
// offset — page cuts move, the reading position must not.
void EpubView::reflowKeepingPosition()
{
    if (!m_doc)
        return;
    const int offset = currentRenderer()->builtOffsetForPage(m_pageInSpine);
    m_renderers.clear();  // every cached layout is now stale
    m_rendererOrder.clear();
    EpubRenderer *r = currentRenderer();
    m_pageInSpine = r->pageIndexForOffset(offset);
    emitTurn(false);  // position preserved: refresh pct/page, but no new locationChanged
}

void EpubView::emitTurn(bool emitLocation)
{
    EpubRenderer *r = currentRenderer();
    if (!r) {
        update();
        return;
    }
    m_pageInSpine = qBound(0, m_pageInSpine, qMax(0, r->pageCount() - 1));
    const int offset = r->builtOffsetForPage(m_pageInSpine);
    m_percentage =
        EpubRenderer::pctFor(m_doc->spineWeights(), m_spineIndex, offset, r->chapter().charCount);
    emit pageChanged();
    emit percentageChanged();
    if (emitLocation)
        emit locationChanged(m_spineIndex, offset);
    update();
}

QVariantList EpubView::toc()
{
    QVariantList out;
    if (!m_doc)
        return out;
    for (const EpubTocEntry &e : m_doc->toc())
        out.append(QVariantMap{{QStringLiteral("title"), e.title},
                               {QStringLiteral("spine"), e.spineIndex}});
    return out;
}

QVariantMap EpubView::follow(const QString &href)
{
    QVariantMap res{{QStringLiteral("ok"), false},
                    {QStringLiteral("footnote"), false},
                    {QStringLiteral("text"), QString()},
                    {QStringLiteral("spine"), m_spineIndex},
                    {QStringLiteral("offset"), 0}};
    if (!m_doc)
        return res;
    const LinkTarget t = m_doc->resolveLink(m_spineIndex, href);
    if (!t.ok)  // external URL / unresolvable
        return res;

    const BuiltChapter ch = m_doc->chapter(t.spineIndex);
    int blockIndex = -1;
    if (!t.anchor.isEmpty())
        blockIndex = ch.anchors.value(t.anchor, -1);
    const int charOffset =
        (blockIndex >= 0 && blockIndex < ch.blockRanges.size()) ? ch.blockRanges[blockIndex].first
                                                                 : 0;
    QString noteText;
    if (blockIndex >= 0 && blockIndex < ch.blockRanges.size()) {
        const int kind = ch.blockKinds.value(blockIndex, 0);
        if (kind == 0 || kind == 1) {  // paragraph or heading target
            const auto rng = ch.blockRanges[blockIndex];
            noteText = ch.text.mid(rng.first, rng.second - rng.first).trimmed();
        }
    }
    res[QStringLiteral("ok")] = true;
    res[QStringLiteral("spine")] = t.spineIndex;
    res[QStringLiteral("offset")] = charOffset;
    // Short, same-chapter target: footnote popup, no navigation. Else: jump (return-stackable).
    if (!noteText.isEmpty() && t.spineIndex == m_spineIndex && noteText.size() <= kNoteMaxChars) {
        res[QStringLiteral("footnote")] = true;
        res[QStringLiteral("text")] = noteText.left(1200);
    } else {
        pushReturn();
        goToLocation(t.spineIndex, charOffset);
    }
    return res;
}

void EpubView::pushReturn()
{
    EpubRenderer *r = currentRenderer();
    const int offset = r ? r->builtOffsetForPage(m_pageInSpine) : 0;
    m_returnStack.append({m_spineIndex, offset});
    while (m_returnStack.size() > kReturnStackMax)
        m_returnStack.removeFirst();
    updateCanReturn();
}

void EpubView::back()
{
    if (m_returnStack.isEmpty())
        return;
    const QPair<int, int> prev = m_returnStack.takeLast();
    updateCanReturn();
    goToLocation(prev.first, prev.second);
}

void EpubView::updateCanReturn()
{
    const bool v = !m_returnStack.isEmpty();
    if (v != m_canReturn) {
        m_canReturn = v;
        emit canReturnChanged();
    }
}

void EpubView::startSearch(const QString &query)
{
    cancelSearch();  // supersede any running scan
    m_searchQuery = query.trimmed();
    m_searchSpine = 0;
    m_searchHits = 0;
    if (!m_doc || m_searchQuery.size() < kMinSearchLen) {
        emit searchFinished(false);
        return;
    }
    m_searchTimer.start();
}

void EpubView::cancelSearch()
{
    if (m_searchTimer.isActive()) {
        m_searchTimer.stop();
        emit searchFinished(true);
    }
    m_searchQuery.clear();
}

void EpubView::searchStep()
{
    if (!m_doc || m_searchQuery.isEmpty()) {
        m_searchTimer.stop();
        return;
    }
    if (m_searchSpine >= m_doc->spineCount()) {
        m_searchTimer.stop();
        emit searchFinished(false);
        return;
    }
    const int spine = m_searchSpine++;
    const QString text = m_doc->chapter(spine).text;  // its own char-offset space
    const int qlen = m_searchQuery.size();
    int from = 0;
    while (from <= text.size() - qlen) {
        const int idx = text.indexOf(m_searchQuery, from, Qt::CaseInsensitive);
        if (idx < 0)
            break;
        emit searchHit(spine, idx, snippetFor(text, idx, qlen));
        if (++m_searchHits >= kMaxSearchHits) {
            m_searchTimer.stop();
            emit searchFinished(false);
            return;
        }
        from = idx + qlen;
    }
    if (m_searchSpine >= m_doc->spineCount()) {
        m_searchTimer.stop();
        emit searchFinished(false);
    }
}

// One-line context around a match (SEARCH_CONTEXT each side), newlines flattened, ellipses
// on truncated ends. Plain text — the reader list styles the match itself.
QString EpubView::snippetFor(const QString &text, int matchStart, int matchLen) const
{
    const int start = qMax(0, matchStart - kSearchContext);
    const int end = qMin(text.size(), matchStart + matchLen + kSearchContext);
    QString body = text.mid(start, end - start);
    body.replace(QChar(u'\n'), QChar(u' '));
    QString out;
    if (start > 0)
        out += QChar(0x2026);
    out += body;
    if (end < text.size())
        out += QChar(0x2026);
    return out;
}

void EpubView::paint(QPainter *painter)
{
    painter->fillRect(0, 0, int(width()), int(height()), Qt::white);
    EpubRenderer *r = currentRenderer();
    if (!r || r->pageCount() == 0)
        return;
    const EpubRenderer::Page pg = r->page(m_pageInSpine);
    if (!pg.imageZipPath.isEmpty()) {
        // Standalone image page: draw the bitmap fit inside the content box, centered.
        QImage img;
        if (!img.loadFromData(m_doc->imageBytes(pg.imageZipPath)))
            return;
        const QRectF box(m_marginPx, m_marginPx, qMax<qreal>(1, width() - 2 * m_marginPx),
                         qMax<qreal>(1, height() - 2 * m_marginPx));
        const QImage scaled = img.scaled(box.size().toSize(), Qt::KeepAspectRatio,
                                         Qt::SmoothTransformation);
        const qreal x = box.x() + (box.width() - scaled.width()) / 2;
        const qreal y = box.y() + (box.height() - scaled.height()) / 2;
        painter->drawImage(QPointF(x, y), scaled);
        return;
    }
    r->render(painter, m_pageInSpine, QSizeF(width(), height()));
}

void EpubView::mousePressEvent(QMouseEvent *e)
{
    m_pressPos = m_lastPos = e->position();
    m_moved = false;
    e->accept();
}

void EpubView::mouseMoveEvent(QMouseEvent *e)
{
    const QPointF pos = e->position();
    m_lastPos = pos;
    if ((pos - m_pressPos).manhattanLength() > kTapSlop)
        m_moved = true;
    e->accept();
}

void EpubView::mouseReleaseEvent(QMouseEvent *e)
{
    const QPointF pos = e->position();
    const QPointF total = pos - m_pressPos;
    if (!m_moved) {
        // Tap-zones (either hand): left third prev, right third next, middle third follows a link.
        if (pos.x() < width() / 3.0) {
            prev();
        } else if (pos.x() > width() * 2.0 / 3.0) {
            next();
        } else if (EpubRenderer *r = currentRenderer()) {
            const QString href = r->linkAt(m_pageInSpine, pos, QSizeF(width(), height()));
            if (!href.isEmpty())
                emit linkActivated(href);
        }
    } else if (qAbs(total.x()) > kSwipeMin && qAbs(total.x()) > qAbs(total.y())) {
        // Horizontal swipe turns the page (content reflows; there is no pan to preserve).
        if (total.x() < 0)
            next();  // content swept left
        else
            prev();
    }
    e->accept();
}

void EpubView::geometryChange(const QRectF &newGeometry, const QRectF &oldGeometry)
{
    QQuickPaintedItem::geometryChange(newGeometry, oldGeometry);
    if (newGeometry.size() != oldGeometry.size())
        reflowKeepingPosition();  // re-paginate at the new size, keep the reading offset
}
