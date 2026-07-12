#include "pdfdocument.h"

#include "pdfium/fpdf_doc.h"
#include "pdfium/fpdf_text.h"
#include "pdfium/fpdfview.h"

#include <QMutex>
#include <QSet>
#include <QtGlobal>
#include <QtMath>

namespace {

// PDFium is process-global and NOT thread-safe. Since the WebDAV sync worker renders covers for
// freshly downloaded books off the GUI thread while a PdfView may be open on the GUI thread, every
// entry point that touches FPDF_* is serialized behind this one lock. Recursive so the
// renderThumbnail -> renderPage -> renderView chain (each locking) doesn't self-deadlock.
QRecursiveMutex &pdfMutex()
{
    static QRecursiveMutex m;
    return m;
}

// FPDFBookmark_GetTitle/FPDF_GetMetaText return UTF-16LE; QString::fromUtf16 assumes
// host byte order. Both targets (armv7 device, x86_64 host) are LE — same assumption
// storage.cpp's BLOB codec already relies on.
static_assert(Q_BYTE_ORDER == Q_LITTLE_ENDIAN, "PDFium UTF-16LE strings assume LE host");

constexpr int kMaxOutline = 5000;    // cycle/size guard for malformed bookmark trees
constexpr int kCacheMaxEntries = 6;
constexpr qint64 kCacheMaxBytes = 32 * 1024 * 1024;  // ~12 screen-sized grayscale pages
constexpr int kMaxViewPx = 1872 * 1404 * 2;  // hard clamp: never render >2x panel area

// PDFium is single-init process-wide; every caller is on the GUI thread (see header).
void ensureLibrary()
{
    static bool inited = [] {
        FPDF_LIBRARY_CONFIG config{};
        config.version = 2;
        FPDF_InitLibraryWithConfig(&config);
        return true;
    }();
    Q_UNUSED(inited);
}

// Two-call idiom shared by GetTitle/GetMetaText: query byte length, then fill.
template <class Fill>
QString readUtf16(Fill fill)
{
    const unsigned long bytes = fill(nullptr, 0);
    if (bytes <= 2)  // just the 2-byte UTF-16 NUL terminator
        return QString();
    QByteArray buf(int(bytes), '\0');
    fill(buf.data(), bytes);
    return QString::fromUtf16(reinterpret_cast<const char16_t *>(buf.constData()),
                              int(bytes / 2) - 1);
}

void walkOutline(FPDF_DOCUMENT doc, FPDF_BOOKMARK bm, int level,
                 QSet<FPDF_BOOKMARK> &seen, QVector<PdfOutline> &out)
{
    while (bm && !seen.contains(bm) && out.size() < kMaxOutline) {
        seen.insert(bm);
        const QString title = readUtf16([&](void *b, unsigned long n) {
            return FPDFBookmark_GetTitle(bm, b, n);
        });
        const FPDF_DEST dest = FPDFBookmark_GetDest(doc, bm);
        const int page = dest ? FPDFDest_GetDestPageIndex(doc, dest) : -1;
        out.append({title, page, level});
        if (FPDF_BOOKMARK child = FPDFBookmark_GetFirstChild(doc, bm))
            walkOutline(doc, child, level + 1, seen, out);
        bm = FPDFBookmark_GetNextSibling(doc, bm);
    }
}

}  // namespace

PdfDocument *PdfDocument::open(const QString &path, QString *err)
{
    QMutexLocker locker(&pdfMutex());
    ensureLibrary();
    const QByteArray utf8 = path.toUtf8();
    FPDF_DOCUMENT doc = FPDF_LoadDocument(utf8.constData(), nullptr);
    if (!doc) {
        if (err)
            *err = QStringLiteral("cannot open PDF: %1").arg(path);
        return nullptr;
    }
    return new PdfDocument(doc, FPDF_GetPageCount(doc));
}

PdfDocument::~PdfDocument()
{
    QMutexLocker locker(&pdfMutex());
    FPDF_CloseDocument(m_doc);
}

QSizeF PdfDocument::pageSizePt(int page) const
{
    QMutexLocker locker(&pdfMutex());
    if (page < 0 || page >= m_pageCount)
        return QSizeF();
    FS_SIZEF sz;
    if (!FPDF_GetPageSizeByIndexF(m_doc, page, &sz))
        return QSizeF();
    return QSizeF(sz.width, sz.height);
}

void PdfDocument::cachePut(const CacheEntry &e) const
{
    m_cache.prepend(e);
    m_cacheBytes += e.img.sizeInBytes();
    // Bound by BOTH entry count and total bytes: a few zoomed viewport renders must not
    // pin hundreds of MB on a 1 GB device.
    while (m_cache.size() > kCacheMaxEntries
           || (m_cacheBytes > kCacheMaxBytes && m_cache.size() > 1)) {
        m_cacheBytes -= m_cache.last().img.sizeInBytes();
        m_cache.removeLast();
    }
}

QImage PdfDocument::renderView(int page, const QSize &fullScaled, const QRect &srcIn) const
{
    QMutexLocker locker(&pdfMutex());
    if (page < 0 || page >= m_pageCount || fullScaled.width() <= 0 || fullScaled.height() <= 0)
        return QImage();
    // Clamp the requested region to the scaled page, and its area to the viewport ceiling.
    QRect src = srcIn.intersected(QRect(QPoint(0, 0), fullScaled));
    if (src.isEmpty())
        return QImage();
    if (qint64(src.width()) * src.height() > kMaxViewPx) {
        const double k = qSqrt(double(kMaxViewPx) / (double(src.width()) * src.height()));
        src.setSize(QSize(qMax(1, int(src.width() * k)), qMax(1, int(src.height() * k))));
    }

    for (int i = 0; i < m_cache.size(); ++i) {  // small cache: linear scan is fine
        if (m_cache[i].page == page && m_cache[i].full == fullScaled && m_cache[i].src == src) {
            m_cache.move(i, 0);
            return m_cache.first().img;
        }
    }

    const int w = src.width(), h = src.height();
    FPDF_BITMAP bmp = FPDFBitmap_Create(w, h, 0);  // BGRx, rows = w*4 bytes
    if (!bmp)
        return QImage();
    FPDFBitmap_FillRect(bmp, 0, 0, w, h, 0xFFFFFFFF);  // white background
    FPDF_PAGE pg = FPDF_LoadPage(m_doc, page);
    QImage img;
    if (pg) {
        // Place the full scaled page at (-src.x, -src.y): PDFium clips to the bitmap, so only
        // the visible sub-rectangle is ever rasterized regardless of zoom.
        FPDF_RenderPageBitmap(bmp, pg, -src.x(), -src.y(), fullScaled.width(), fullScaled.height(),
                              0, FPDF_GRAYSCALE | FPDF_ANNOT);
        FPDF_ClosePage(pg);
        const QImage rgb(static_cast<const uchar *>(FPDFBitmap_GetBuffer(bmp)),
                         w, h, w * 4, QImage::Format_RGB32);
        img = rgb.convertToFormat(QImage::Format_Grayscale8);  // deep-copies off the FPDF buffer
    }
    FPDFBitmap_Destroy(bmp);
    if (!img.isNull())
        cachePut({page, fullScaled, src, img});
    return img;
}

QImage PdfDocument::renderPage(int page, const QSize &target) const
{
    if (page < 0 || page >= m_pageCount || target.width() <= 0 || target.height() <= 0)
        return QImage();
    const QSizeF pt = pageSizePt(page);
    if (pt.isEmpty())
        return QImage();
    const double scale = qMin(target.width() / pt.width(), target.height() / pt.height());
    const QSize full(qMax(1, int(pt.width() * scale + 0.5)), qMax(1, int(pt.height() * scale + 0.5)));
    return renderView(page, full, QRect(QPoint(0, 0), full));  // whole page
}

QImage PdfDocument::renderThumbnail(int page, int maxDim) const
{
    return renderPage(page, QSize(maxDim, maxDim));
}

QVector<PdfOutline> PdfDocument::outline() const
{
    QMutexLocker locker(&pdfMutex());
    QVector<PdfOutline> out;
    QSet<FPDF_BOOKMARK> seen;
    walkOutline(m_doc, FPDFBookmark_GetFirstChild(m_doc, nullptr), 0, seen, out);
    return out;
}

int PdfDocument::searchPage(int page, const QString &q) const
{
    QMutexLocker locker(&pdfMutex());
    if (q.isEmpty() || page < 0 || page >= m_pageCount)
        return 0;
    FPDF_PAGE pg = FPDF_LoadPage(m_doc, page);
    if (!pg)
        return 0;
    int count = 0;
    if (FPDF_TEXTPAGE tp = FPDFText_LoadPage(pg)) {
        const int n = FPDFText_CountChars(tp);
        if (n > 0) {
            QVector<unsigned short> buf(n + 1);
            const int written = FPDFText_GetText(tp, 0, n, buf.data());
            const QString text = QString::fromUtf16(
                reinterpret_cast<const char16_t *>(buf.constData()),
                written > 0 ? written - 1 : 0);
            for (int at = text.indexOf(q, 0, Qt::CaseInsensitive); at >= 0;
                 at = text.indexOf(q, at + q.size(), Qt::CaseInsensitive))
                ++count;
        }
        FPDFText_ClosePage(tp);
    }
    FPDF_ClosePage(pg);
    return count;
}

QString PdfDocument::metaText(const QString &tag) const
{
    QMutexLocker locker(&pdfMutex());
    const QByteArray t = tag.toUtf8();
    return readUtf16([&](void *b, unsigned long n) {
        return FPDF_GetMetaText(m_doc, t.constData(), b, n);
    });
}
