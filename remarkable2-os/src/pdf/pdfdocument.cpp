#include "pdfdocument.h"

#include "pdfium/fpdf_doc.h"
#include "pdfium/fpdf_text.h"
#include "pdfium/fpdfview.h"

#include <QSet>
#include <QtGlobal>

namespace {

// FPDFBookmark_GetTitle/FPDF_GetMetaText return UTF-16LE; QString::fromUtf16 assumes
// host byte order. Both targets (armv7 device, x86_64 host) are LE — same assumption
// storage.cpp's BLOB codec already relies on.
static_assert(Q_BYTE_ORDER == Q_LITTLE_ENDIAN, "PDFium UTF-16LE strings assume LE host");

constexpr int kMaxOutline = 5000;  // cycle/size guard for malformed bookmark trees
constexpr int kCacheMax = 3;

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
    FPDF_CloseDocument(m_doc);
}

QSizeF PdfDocument::pageSizePt(int page) const
{
    if (page < 0 || page >= m_pageCount)
        return QSizeF();
    FS_SIZEF sz;
    if (!FPDF_GetPageSizeByIndexF(m_doc, page, &sz))
        return QSizeF();
    return QSizeF(sz.width, sz.height);
}

QImage PdfDocument::renderPage(int page, const QSize &target) const
{
    if (page < 0 || page >= m_pageCount || target.width() <= 0 || target.height() <= 0)
        return QImage();
    const QSizeF pt = pageSizePt(page);
    if (pt.isEmpty())
        return QImage();

    // Fit into target preserving aspect.
    const double scale = qMin(target.width() / pt.width(), target.height() / pt.height());
    const int outW = qMax(1, int(pt.width() * scale + 0.5));
    const int outH = qMax(1, int(pt.height() * scale + 0.5));
    const QSize outSize(outW, outH);

    for (int i = 0; i < m_cache.size(); ++i) {  // small LRU (kCacheMax): linear scan is fine
        if (m_cache[i].page == page && m_cache[i].size == outSize) {
            m_cache.move(i, 0);
            return m_cache.first().img;
        }
    }

    FPDF_BITMAP bmp = FPDFBitmap_Create(outW, outH, 0);  // BGRx, rows = outW*4 bytes
    if (!bmp)
        return QImage();
    FPDFBitmap_FillRect(bmp, 0, 0, outW, outH, 0xFFFFFFFF);  // white background
    FPDF_PAGE pg = FPDF_LoadPage(m_doc, page);
    QImage img;
    if (pg) {
        FPDF_RenderPageBitmap(bmp, pg, 0, 0, outW, outH, 0, FPDF_GRAYSCALE | FPDF_ANNOT);
        FPDF_ClosePage(pg);
        // Grayscale render yields R=G=B; wrap BGRx then let Qt down-convert (exact for gray).
        const QImage src(static_cast<const uchar *>(FPDFBitmap_GetBuffer(bmp)),
                         outW, outH, outW * 4, QImage::Format_RGB32);
        img = src.convertToFormat(QImage::Format_Grayscale8);  // deep-copies off the FPDF buffer
    }
    FPDFBitmap_Destroy(bmp);
    if (img.isNull())
        return img;

    m_cache.prepend({page, outSize, img});
    while (m_cache.size() > kCacheMax)
        m_cache.removeLast();
    return img;
}

QImage PdfDocument::renderThumbnail(int page, int maxDim) const
{
    return renderPage(page, QSize(maxDim, maxDim));
}

QVector<PdfOutline> PdfDocument::outline() const
{
    QVector<PdfOutline> out;
    QSet<FPDF_BOOKMARK> seen;
    walkOutline(m_doc, FPDFBookmark_GetFirstChild(m_doc, nullptr), 0, seen, out);
    return out;
}

QVector<QPair<int, int>> PdfDocument::search(const QString &q) const
{
    QVector<QPair<int, int>> hits;
    if (q.isEmpty())
        return hits;
    for (int page = 0; page < m_pageCount; ++page) {
        FPDF_PAGE pg = FPDF_LoadPage(m_doc, page);
        if (!pg)
            continue;
        if (FPDF_TEXTPAGE tp = FPDFText_LoadPage(pg)) {
            const int n = FPDFText_CountChars(tp);
            if (n > 0) {
                QVector<unsigned short> buf(n + 1);
                const int written = FPDFText_GetText(tp, 0, n, buf.data());
                const QString text = QString::fromUtf16(
                    reinterpret_cast<const char16_t *>(buf.constData()),
                    written > 0 ? written - 1 : 0);
                int count = 0;
                for (int at = text.indexOf(q, 0, Qt::CaseInsensitive); at >= 0;
                     at = text.indexOf(q, at + q.size(), Qt::CaseInsensitive))
                    ++count;
                if (count > 0)
                    hits.append({page, count});
            }
            FPDFText_ClosePage(tp);
        }
        FPDF_ClosePage(pg);
    }
    return hits;
}

QString PdfDocument::metaText(const QString &tag) const
{
    const QByteArray t = tag.toUtf8();
    return readUtf16([&](void *b, unsigned long n) {
        return FPDF_GetMetaText(m_doc, t.constData(), b, n);
    });
}
