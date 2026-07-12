#pragma once

#include <QImage>
#include <QList>
#include <QPair>
#include <QRect>
#include <QSize>
#include <QSizeF>
#include <QString>
#include <QVector>

struct fpdf_document_t__;

struct PdfOutline {
    QString title;
    int pageIndex;  // 0-based; -1 if the bookmark has no page destination
    int level;      // 0 = top level
};

// PDFium (libpdfium.so) document wrapper. One process-wide FPDF_InitLibraryWithConfig
// happens lazily on first open(). PDFium is NOT thread-safe: construct and use every
// PdfDocument on the GUI thread only. Rendering is bounded to the visible viewport so
// memory and time never scale with zoom.
class PdfDocument
{
public:
    static PdfDocument *open(const QString &path, QString *err);  // nullptr on failure
    ~PdfDocument();
    PdfDocument(const PdfDocument &) = delete;
    PdfDocument &operator=(const PdfDocument &) = delete;

    int pageCount() const { return m_pageCount; }
    QSizeF pageSizePt(int page) const;                       // (0,0) if out of range

    // Render only `src` (a sub-rectangle, in pixels, of the page scaled to `fullScaled`) —
    // always <= viewport pixels, so a zoomed page never allocates a giant bitmap. Grayscale
    // on white, Format_Grayscale8, image size == src.size() clamped to fullScaled bounds.
    QImage renderView(int page, const QSize &fullScaled, const QRect &src) const;
    // Whole page fitted into `target` (used for covers/thumbnails). Bounded by target.
    QImage renderPage(int page, const QSize &target) const;
    QImage renderThumbnail(int page, int maxDim) const;      // fit into maxDim square

    QVector<PdfOutline> outline() const;                     // flattened bookmark tree
    // Count case-insensitive matches of `q` on one page (for chunked, cancelable search).
    int searchPage(int page, const QString &q) const;

    // Document info dictionary tag ("Title", "Author", ...); "" if absent.
    QString metaText(const QString &tag) const;

private:
    explicit PdfDocument(fpdf_document_t__ *doc, int pageCount)
        : m_doc(doc), m_pageCount(pageCount) {}

    struct CacheEntry { int page; QSize full; QRect src; QImage img; };
    void cachePut(const CacheEntry &e) const;

    fpdf_document_t__ *m_doc;
    int m_pageCount;
    mutable QList<CacheEntry> m_cache;  // MRU front; renderView is const, cache is a cache
    mutable qint64 m_cacheBytes = 0;
};
