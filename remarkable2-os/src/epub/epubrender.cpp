#include "epub/epubrender.h"

#include <QAbstractTextDocumentLayout>
#include <QFont>
#include <QHash>
#include <QPainter>
#include <QPointF>
#include <QTextBlock>
#include <QTextCursor>
#include <QTextDocument>
#include <QTextLayout>
#include <QTextLine>

// Port of Android ui/reader/Pagination.kt (buildChapterText render inputs + paginate +
// pageIndexFor) and ReaderViewModel.wholeBookPercentage. Pagination is device-local: page
// cuts need NOT match Android, only the char-offset space does (owned by EpubDocument).

namespace {

// Heading font sizes h1..h6 as multiples of the body size (Pagination.HeadingScales).
const double kHeadingScales[] = {1.6, 1.45, 1.3, 1.2, 1.1, 1.05};
constexpr int kParaFirstLineIndentPx = 16;  // Pagination: TextIndent(firstLine = 16.sp)
constexpr char16_t kImageChar = 0xFFFC;
constexpr char16_t kLineSep = 0x2028;  // QChar::LineSeparator: keeps <br> inside one QTextBlock

// Map a UI font token to an installed device family (Noto Serif/Sans/Mono, EB Garamond).
QString familyFor(const QString &token)
{
    if (token.compare(QLatin1String("Sans"), Qt::CaseInsensitive) == 0)
        return QStringLiteral("Noto Sans");
    if (token.compare(QLatin1String("Mono"), Qt::CaseInsensitive) == 0)
        return QStringLiteral("Noto Mono");
    if (token.compare(QLatin1String("Garamond"), Qt::CaseInsensitive) == 0)
        return QStringLiteral("EB Garamond");
    return QStringLiteral("Noto Serif");  // Serif / unknown
}

}  // namespace

EpubRenderer::EpubRenderer() = default;
EpubRenderer::~EpubRenderer() = default;

void EpubRenderer::setChapter(const BuiltChapter &chapter)
{
    m_chapter = chapter;
    m_docDirty = true;
    m_pageDirty = true;
}

void EpubRenderer::setTypography(const QString &family, int bodyPx, double lineSpacing,
                                 int marginPx, bool justify)
{
    m_family = family;
    m_bodyPx = qMax(1, bodyPx);
    m_lineSpacing = lineSpacing > 0 ? lineSpacing : 1.0;
    m_marginPx = qMax(0, marginPx);
    m_justify = justify;
    m_docDirty = true;  // font metrics change the layout
    m_pageDirty = true;
}

void EpubRenderer::setViewport(qreal width, qreal height)
{
    if (qFuzzyCompare(width, m_vpW) && qFuzzyCompare(height, m_vpH))
        return;
    m_vpW = width;
    m_vpH = height;
    m_pageDirty = true;
}

void EpubRenderer::ensure()
{
    if (m_docDirty) {
        rebuild();
        m_docDirty = false;
        m_pageDirty = true;
    }
    if (m_pageDirty) {
        paginate();
        m_pageDirty = false;
    }
}

void EpubRenderer::rebuild()
{
    m_doc = std::make_unique<QTextDocument>();
    m_doc->setDocumentMargin(0);

    QFont base(familyFor(m_family));
    base.setPixelSize(m_bodyPx);
    m_doc->setDefaultFont(base);

    QTextOption opt;
    opt.setWrapMode(QTextOption::WrapAtWordBoundaryOrAnywhere);
    m_doc->setDefaultTextOption(opt);

    const int lineHeightPct = qRound(m_lineSpacing * 100.0);
    const int blockCount = m_chapter.blockRanges.size();

    QTextCursor cur(m_doc.get());
    for (int i = 0; i < blockCount; ++i) {
        const int kind = m_chapter.blockKinds.value(i, 0);
        const int level = m_chapter.headingLevels.value(i, 0);
        const auto range = m_chapter.blockRanges[i];
        QString btext = m_chapter.text.mid(range.first, range.second - range.first);

        QTextBlockFormat bf;
        bf.setLineHeight(lineHeightPct, QTextBlockFormat::ProportionalHeight);
        QTextCharFormat cf;  // base char format for the block's inserted text
        switch (kind) {
        case 1: {  // heading: bold + scaled font, left-aligned
            bf.setAlignment(Qt::AlignLeft);
            QFont hf(familyFor(m_family));
            hf.setPixelSize(qRound(m_bodyPx * kHeadingScales[qBound(0, level - 1, 5)]));
            hf.setBold(true);
            cf.setFont(hf);
            break;
        }
        case 2:  // rule: centered "* * *"
            bf.setAlignment(Qt::AlignHCenter);
            break;
        case 3:  // image: centered placeholder (U+FFFC), one layout line
            bf.setAlignment(Qt::AlignHCenter);
            btext = QString(QChar(kImageChar));
            break;
        default:  // paragraph: first-line indent, left or justified
            bf.setAlignment(m_justify ? Qt::AlignJustify : Qt::AlignLeft);
            bf.setTextIndent(kParaFirstLineIndentPx);
            break;
        }

        // <br> arrives as '\n' inside the built block text; make it a line break WITHIN the
        // QTextBlock (U+2028) instead of a block split, so one QTextBlock == one built block
        // and the offset<->docPosition map stays affine (both are one UTF-16 unit).
        if (kind == 0 || kind == 1)
            btext.replace(QChar(u'\n'), QChar(kLineSep));

        if (i == 0)
            cur.setBlockFormat(bf);
        else
            cur.insertBlock(bf);
        cur.insertText(btext, cf);
    }

    // Apply inline spans (bold/italic/underline/sub/sup) over their built ranges.
    for (const auto &s : m_chapter.spans) {
        const int ds = docPositionForOffset(s.first.first);
        const int de = docPositionForOffset(s.first.second);
        if (de <= ds)
            continue;
        QTextCursor c(m_doc.get());
        c.setPosition(ds);
        c.setPosition(de, QTextCursor::KeepAnchor);
        QTextCharFormat f;
        const int bit = s.second;
        if (bit & 1)
            f.setFontWeight(QFont::Bold);
        if (bit & 2)
            f.setFontItalic(true);
        if (bit & 4)
            f.setFontUnderline(true);
        if (bit & 8)
            f.setVerticalAlignment(QTextCharFormat::AlignSubScript);
        if (bit & 16)
            f.setVerticalAlignment(QTextCharFormat::AlignSuperScript);
        c.mergeCharFormat(f);
    }
    // Links: mark as anchors so linkAt() can read the href back off the char format.
    for (const auto &l : m_chapter.links) {
        const int ds = docPositionForOffset(l.first.first);
        const int de = docPositionForOffset(l.first.second);
        if (de <= ds)
            continue;
        QTextCursor c(m_doc.get());
        c.setPosition(ds);
        c.setPosition(de, QTextCursor::KeepAnchor);
        QTextCharFormat f;
        f.setAnchor(true);
        f.setAnchorHref(l.second);
        f.setFontUnderline(true);
        c.mergeCharFormat(f);
    }
}

void EpubRenderer::paginate()
{
    m_pages.clear();
    if (!m_doc)
        rebuild();

    const qreal contentW = qMax<qreal>(1, m_vpW - 2 * m_marginPx);
    const qreal safeH = qMax<qreal>(1, m_vpH - 2 * m_marginPx);
    m_doc->setTextWidth(contentW);
    QAbstractTextDocumentLayout *dl = m_doc->documentLayout();

    // Flatten every layout line across all blocks into one ordered list (Android measures the
    // whole chapter as one layout; iterating blocks in order reproduces the same line sequence).
    struct L {
        qreal top, bottom;
        int startOff, endOff;
        QString imagePath;
    };
    QVector<L> lines;
    QHash<int, QString> imgPathAt;
    for (const auto &img : m_chapter.images)
        imgPathAt.insert(img.first, img.second);

    for (QTextBlock b = m_doc->begin(); b.isValid(); b = b.next()) {
        const int bi = b.blockNumber();
        const bool isImage = m_chapter.blockKinds.value(bi, 0) == 3;
        const QString imgPath =
            isImage && bi < m_chapter.blockRanges.size()
                ? imgPathAt.value(m_chapter.blockRanges[bi].first)
                : QString();
        const qreal blockTop = dl->blockBoundingRect(b).top();
        QTextLayout *lay = b.layout();
        const int lc = lay ? lay->lineCount() : 0;
        for (int j = 0; j < lc; ++j) {
            const QTextLine line = lay->lineAt(j);
            const qreal top = blockTop + line.y();
            const int startDoc = b.position() + line.textStart();
            const int endDoc = startDoc + line.textLength();
            lines.append({top, top + line.height(), offsetForDocPosition(startDoc),
                          offsetForDocPosition(endDoc), imgPath});
        }
    }

    if (lines.isEmpty()) {  // blank chapter: one empty page
        m_pages.append({0, m_chapter.text.size(), 0, 0, QString()});
        return;
    }

    // Greedy cut into pages of whole lines fitting safeH; image lines are standalone pages.
    int i = 0;
    while (i < lines.size()) {
        const qreal top = lines[i].top;
        if (!lines[i].imagePath.isEmpty()) {
            m_pages.append({lines[i].startOff, lines[i].endOff, top, lines[i].bottom - top,
                            lines[i].imagePath});
            ++i;
        } else {
            int last = i;
            while (last + 1 < lines.size() && lines[last + 1].imagePath.isEmpty() &&
                   lines[last + 1].bottom - top <= safeH)
                ++last;
            m_pages.append({lines[i].startOff, lines[last].endOff, top,
                            lines[last].bottom - top, QString()});
            i = last + 1;
        }
    }
}

int EpubRenderer::pageCount()
{
    ensure();
    return m_pages.size();
}

EpubRenderer::Page EpubRenderer::page(int index)
{
    ensure();
    if (index < 0 || index >= m_pages.size())
        return {};
    return m_pages[index];
}

int EpubRenderer::pageIndexForOffset(int builtOffset)
{
    ensure();
    for (int i = 0; i < m_pages.size(); ++i)
        if (builtOffset >= m_pages[i].builtStartOffset && builtOffset < m_pages[i].builtEndOffset)
            return i;
    if (!m_pages.isEmpty() && builtOffset >= m_pages.last().builtStartOffset)
        return m_pages.size() - 1;
    return 0;
}

int EpubRenderer::builtOffsetForPage(int index)
{
    ensure();
    if (index < 0 || index >= m_pages.size())
        return 0;
    return m_pages[index].builtStartOffset;
}

int EpubRenderer::docPositionForOffset(int builtOffset)
{
    const int n = m_chapter.blockRanges.size();
    for (int bi = 0; bi < n; ++bi) {
        const int s = m_chapter.blockRanges[bi].first;
        const int e = m_chapter.blockRanges[bi].second;
        if (builtOffset < s)  // in the separator gap before this block: clamp to its start
            return m_doc->findBlockByNumber(bi).position();
        if (builtOffset <= e)  // interior, or the block-end/separator position
            return m_doc->findBlockByNumber(bi).position() + (builtOffset - s);
    }
    return m_doc ? qMax(0, m_doc->characterCount() - 1) : 0;
}

int EpubRenderer::offsetForDocPosition(int docPos)
{
    if (!m_doc)
        return 0;
    const QTextBlock b = m_doc->findBlock(docPos);
    const int bi = b.blockNumber();
    if (bi < 0 || bi >= m_chapter.blockRanges.size())
        return 0;
    const auto range = m_chapter.blockRanges[bi];
    const int within = qBound(0, docPos - b.position(), range.second - range.first);
    return range.first + within;
}

void EpubRenderer::render(QPainter *painter, int pageIndex, const QSizeF &size)
{
    ensure();
    if (pageIndex < 0 || pageIndex >= m_pages.size())
        return;
    const Page &pg = m_pages[pageIndex];
    if (!pg.imageZipPath.isEmpty())
        return;  // image page: EpubView paints the bitmap

    const qreal contentW = qMax<qreal>(1, size.width() - 2 * m_marginPx);
    painter->save();
    painter->translate(m_marginPx, m_marginPx);
    painter->setClipRect(QRectF(0, 0, contentW, pg.contentHeightPx));
    painter->translate(0, -pg.topPx);
    m_doc->drawContents(painter, QRectF(0, pg.topPx, contentW, pg.contentHeightPx));
    painter->restore();
}

QString EpubRenderer::linkAt(int pageIndex, const QPointF &pt, const QSizeF &size)
{
    ensure();
    if (pageIndex < 0 || pageIndex >= m_pages.size())
        return QString();
    const Page &pg = m_pages[pageIndex];
    if (!pg.imageZipPath.isEmpty())
        return QString();
    // View point -> doc coordinates (undo the render translate), then hit-test to a doc pos.
    const QPointF docPt(pt.x() - m_marginPx, pt.y() - m_marginPx + pg.topPx);
    const int docPos = m_doc->documentLayout()->hitTest(docPt, Qt::FuzzyHit);
    if (docPos < 0)
        return QString();
    const int off = offsetForDocPosition(docPos);
    for (const auto &l : m_chapter.links)
        if (off >= l.first.first && off < l.first.second)
            return l.second;
    return QString();
}

double EpubRenderer::pctFor(const QVector<qint64> &w, int spine, int startCharOfPage,
                            int chapterCharCount)
{
    if (w.isEmpty())
        return 0.0;
    double total = 0;
    for (qint64 x : w)
        total += double(x);
    if (total < 1.0)
        total = 1.0;
    double before = 0;
    const int lim = qBound(0, spine, w.size());
    for (int i = 0; i < lim; ++i)
        before += double(w[i]);
    const double weight = (spine >= 0 && spine < w.size()) ? double(w[spine]) : 1.0;
    double within = double(startCharOfPage) / double(qMax(1, chapterCharCount));
    within = qBound(0.0, within, 1.0);
    return qBound(0.0, (before + weight * within) / total, 1.0);
}
