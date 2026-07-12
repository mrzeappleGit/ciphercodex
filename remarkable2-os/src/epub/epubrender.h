#pragma once

#include <QPair>
#include <QRectF>
#include <QSizeF>
#include <QString>
#include <QVector>

#include <memory>

#include "epub/epubdocument.h"

class QPainter;
class QPointF;
class QTextDocument;

// Device-local layout + pagination for one BuiltChapter. Builds a QTextDocument (one
// QTextBlock per BuiltChapter block) and cuts it into pages of whole layout lines that
// fit the content height — a direct port of Android Pagination.paginate. The built-text
// char offset space (kosync `o=`) is preserved: every block's doc text has the SAME length
// as its blockRange, so offset<->docPosition is a clean per-block affine map. Not a QObject;
// EpubView owns a small LRU of these.
class EpubRenderer
{
public:
    struct Page {
        int builtStartOffset = 0;      // built char offset of the page's first char (kosync `o=`)
        int builtEndOffset = 0;        // built char offset just past the page's last line
        qreal topPx = 0;               // y of the page's first line in the full-chapter layout
        qreal contentHeightPx = 0;     // last-line-bottom - topPx (clip height; may exceed the box)
        QString imageZipPath;          // non-empty => standalone image page (EpubView draws it)
    };

    EpubRenderer();
    ~EpubRenderer();
    EpubRenderer(const EpubRenderer &) = delete;
    EpubRenderer &operator=(const EpubRenderer &) = delete;

    void setChapter(const BuiltChapter &chapter);
    // Serif/Sans/Mono/Garamond token, body pixel size, line-spacing multiple, side/edge
    // margin px, justify. Re-lays out + re-paginates on the next query.
    void setTypography(const QString &family, int bodyPx, double lineSpacing, int marginPx,
                       bool justify);
    void setViewport(qreal width, qreal height);  // re-paginates on the next query

    int pageCount();
    Page page(int index);
    int pageIndexForOffset(int builtOffset);
    int builtOffsetForPage(int index);

    // Draws the current page's text: doc translated up by topPx, clipped to contentHeight.
    // Image pages draw nothing (left to EpubView). `size` is the item's pixel size.
    void render(QPainter *painter, int pageIndex, const QSizeF &size);

    // Link href under a view-space point on `pageIndex`, or "" if none (for tap-to-follow).
    QString linkAt(int pageIndex, const QPointF &pt, const QSizeF &size);

    // --- Selection + highlight-render seam (Phase 2c). All built-offset based. ---
    // Built char offset under a view-space point on pageIndex (documentLayout hitTest, undoing
    // the render() margin/topPx transform), or -1 for an out-of-range / image page.
    int offsetAtPoint(int pageIndex, const QPointF &pt, const QSizeF &size);
    // Word [start,end) (built offsets) at/nearest `builtOffset`; empty pair if none.
    QPair<int, int> wordRangeAt(int builtOffset);
    // View-space rects covering built range [start,end) on pageIndex (selection + saved
    // highlights); empty if the range doesn't intersect the page. Same transform as render().
    QVector<QRectF> rectsForRange(int pageIndex, int startOffset, int endOffset, const QSizeF &size);
    // Plain built-text slice [start,end) (highlight snapshot + copy).
    QString textSlice(int startOffset, int endOffset) const;

    // offset<->docPosition seam (pagination + later selection). Exact for interior offsets;
    // block-boundary/separator offsets clamp to the owning block's edge.
    int docPositionForOffset(int builtOffset);
    int offsetForDocPosition(int docPos);

    const BuiltChapter &chapter() const { return m_chapter; }

    // Whole-book percentage: (before + weights[spine]*within)/total, within clamped to [0,1].
    // Mirrors Android ReaderViewModel.wholeBookPercentage. Static: no chapter state needed.
    static double pctFor(const QVector<qint64> &spineWeights, int spine, int startCharOfPage,
                         int chapterCharCount);

private:
    void ensure();     // rebuild doc and/or paginate if dirty
    void rebuild();     // BuiltChapter -> QTextDocument (formats, spans, links)
    void paginate();    // laid-out doc -> m_pages (greedy line cut)

    BuiltChapter m_chapter;
    QString m_family = QStringLiteral("Serif");
    int m_bodyPx = 22;
    double m_lineSpacing = 1.4;
    int m_marginPx = 40;
    bool m_justify = false;
    qreal m_vpW = 0;
    qreal m_vpH = 0;

    std::unique_ptr<QTextDocument> m_doc;
    QVector<Page> m_pages;
    bool m_docDirty = true;
    bool m_pageDirty = true;
};
