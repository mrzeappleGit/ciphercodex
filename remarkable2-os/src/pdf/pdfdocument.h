#pragma once

#include <QImage>
#include <QList>
#include <QPair>
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
// PdfDocument on the GUI thread only.
class PdfDocument
{
public:
    static PdfDocument *open(const QString &path, QString *err);  // nullptr on failure
    ~PdfDocument();
    PdfDocument(const PdfDocument &) = delete;
    PdfDocument &operator=(const PdfDocument &) = delete;

    int pageCount() const { return m_pageCount; }
    QSizeF pageSizePt(int page) const;                       // (0,0) if out of range

    // Fit into `target` preserving aspect; grayscale on white. Returned image is the
    // fitted size (<= target in each dim), Format_Grayscale8. Null image on failure.
    QImage renderPage(int page, const QSize &target) const;
    QImage renderThumbnail(int page, int maxDim) const;      // fit into maxDim square

    QVector<PdfOutline> outline() const;                     // flattened bookmark tree
    QVector<QPair<int, int>> search(const QString &q) const; // (pageIndex, matchCount), ci

    // Document info dictionary tag ("Title", "Author", ...); "" if absent.
    QString metaText(const QString &tag) const;

private:
    explicit PdfDocument(fpdf_document_t__ *doc, int pageCount)
        : m_doc(doc), m_pageCount(pageCount) {}

    struct CacheEntry { int page; QSize size; QImage img; };

    fpdf_document_t__ *m_doc;
    int m_pageCount;
    mutable QList<CacheEntry> m_cache;  // MRU front; renderPage is const, cache is a cache
};
