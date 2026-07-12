// Host golden tests for the byte-identical EPUB built-text pipeline (XhtmlMapper +
// buildChapterText port) and EpubParser resolvePath / spine numbering. Plain assert(),
// no framework. Each expected string is derived BY HAND from the Kotlin sources and the
// derivation is commented inline. Links: epubdocument.cpp + Qt6Core + Qt6CorePrivate
// (QZipReader/QZipWriter) — no GUI, no sqlite, no PDFium.
#include "epub/epubdocument.h"

#include <QByteArray>
#include <QChar>
#include <QString>
#include <QTemporaryDir>

#include <QtCore/private/qzipwriter_p.h>

#include <cassert>
#include <cstdio>
#include <functional>

using ccx::epub::buildChapterFromXhtml;
using ccx::epub::resolvePath;

// Build a BuiltChapter from XHTML `inner` wrapped in a single <body> root. <body> is a
// BlockBoundaryTag whose open/close only flush an (empty) block, so it is transparent to
// the produced text — same as the Android mapper walking a full document's body.
static BuiltChapter buildBody(const QString &inner,
                              const std::function<QString(const QString &)> &ri = {})
{
    const QString doc = QStringLiteral("<body>") + inner + QStringLiteral("</body>");
    return buildChapterFromXhtml(doc.toUtf8(), 0, ri);
}

static QChar OBJ() { return QChar(0xFFFC); }  // image object-replacement char

// ---- 1. whitespace collapse ----------------------------------------------------------
// <p>  a\n\tb  </p>: append() collapses each run of collapsible space to a single ' ' only
// when text is non-empty and its last char is not ' '/'\n'; flush() trims trailing ' '/'\n'
// (leading never gets added). "  a\n\tb  " -> 'a', '\n'->' ', '\t'->(skip), 'b', ' ', ' '->
// "a b " -> trim -> "a b".
static void testWhitespaceCollapse()
{
    const BuiltChapter b = buildBody(QStringLiteral("<p>  a\n\tb  </p>"));
    assert(b.text == QStringLiteral("a b"));
    assert(b.blockRanges.size() == 1 && b.blockRanges[0] == qMakePair(0, 3));
    assert(b.blockKinds == QVector<int>{0});
    assert(b.charCount == 3);
}

// ---- 2. NBSP-family collapse ---------------------------------------------------------
// U+00A0 / U+2007 / U+202F are all collapsible (isCollapsibleSpace / Kotlin isWhitespace),
// so "a b c d" collapses each to one ' ' -> "a b c d".
static void testNbspCollapse()
{
    const QString inner = QStringLiteral("<p>a") + QChar(0x00A0) + QLatin1Char('b') +
                          QChar(0x2007) + QLatin1Char('c') + QChar(0x202F) +
                          QLatin1Char('d') + QStringLiteral("</p>");
    const BuiltChapter b = buildBody(inner);
    assert(b.text == QStringLiteral("a b c d"));
    assert(b.charCount == 7);
}

// ---- 3. entities ---------------------------------------------------------------------
// &mdash; -> "—" (U+2014, not collapsible); &nbsp; -> U+00A0 (collapsible -> becomes ' ').
// "a&mdash;b&nbsp;c" -> 'a','—','b', U+00A0->' ', 'c' -> "a—b c".
static void testEntities()
{
    const BuiltChapter b = buildBody(QStringLiteral("<p>a&mdash;b&nbsp;c</p>"));
    assert(b.text == QString::fromUtf8("a\xE2\x80\x94""b c"));  // U+2014 == \xE2\x80\x94
    assert(b.charCount == 5);
}

// ---- 4. two paragraphs ---------------------------------------------------------------
// "p1","p2": '\n' separator kept OUTSIDE both ranges. text "p1\np2"; block0 [0,2) (separate,
// contentEnd=len-1=2), block1 [3,5) (last, contentEnd=len=5). charCount = 2+2.
static void testTwoParagraphs()
{
    const BuiltChapter b = buildBody(QStringLiteral("<p>p1</p><p>p2</p>"));
    assert(b.text == QStringLiteral("p1\np2"));
    assert((b.blockRanges == QVector<QPair<int, int>>{{0, 2}, {3, 5}}));
    assert(b.blockKinds == (QVector<int>{0, 0}));
    assert(b.charCount == 4);
}

// ---- 5. rule + image -----------------------------------------------------------------
// Rule between paras: "* * *" (5 chars, textLength 1). text "p1\n* * *\np2"; ranges (0,2),
// (3,8),(9,11); charCount 2+1+2=5.
// Image: one U+FFFC in its own block; images records (charIndex,path). text "p1\n<OBJ>\np2";
// block1 is [3,4) kind image; images=[(3,"a.png")]; charCount 2+1+2=5.
static void testRuleAndImage()
{
    const BuiltChapter r = buildBody(QStringLiteral("<p>p1</p><hr/><p>p2</p>"));
    assert(r.text == QStringLiteral("p1\n* * *\np2"));
    assert((r.blockRanges == QVector<QPair<int, int>>{{0, 2}, {3, 8}, {9, 11}}));
    assert(r.blockKinds == (QVector<int>{0, 2, 0}));
    assert(r.charCount == 5);

    const auto identity = [](const QString &h) { return h; };  // resolveImage: keep href as path
    const BuiltChapter im =
        buildBody(QStringLiteral("<p>p1</p><img src=\"a.png\"/><p>p2</p>"), identity);
    QString expected = QStringLiteral("p1\n") + OBJ() + QStringLiteral("\np2");
    assert(im.text == expected);
    assert(im.text[3] == OBJ());
    assert((im.images == QVector<QPair<int, QString>>{{3, QStringLiteral("a.png")}}));
    assert(im.blockKinds == (QVector<int>{0, 3, 0}));
    assert((im.blockRanges == QVector<QPair<int, int>>{{0, 2}, {3, 4}, {5, 7}}));
    assert(im.charCount == 5);
}

// ---- 6. <br> inside a paragraph ------------------------------------------------------
// lineBreak() strips a trailing ' ' then appends '\n'. "a <br/>b": 'a',' '->"a ",<br> strips
// ' ' then '\n' -> "a\n", 'b' -> "a\nb". Literal '\n' stays inside the single block.
static void testLineBreak()
{
    const BuiltChapter b = buildBody(QStringLiteral("<p>a <br/>b</p>"));
    assert(b.text == QStringLiteral("a\nb"));
    assert(b.blockKinds == QVector<int>{0});
    assert(b.charCount == 3);
}

// ---- 7. heading ----------------------------------------------------------------------
// <h2>Hi</h2>: heading level 2, text "Hi". kind 1, headingLevels 2.
static void testHeading()
{
    const BuiltChapter b = buildBody(QStringLiteral("<h2>Hi</h2>"));
    assert(b.text == QStringLiteral("Hi"));
    assert(b.blockKinds == QVector<int>{1});
    assert(b.headingLevels == QVector<int>{2});
    assert(b.charCount == 2);
}

// ---- 8. skipped subtrees -------------------------------------------------------------
// <head>/<script>/<style> subtrees contribute no text (skipDepth). Only the two <p> emit.
static void testSkippedTags()
{
    const BuiltChapter b = buildBody(QStringLiteral(
        "<head><title>T</title></head><p>a</p><script>var x=1;</script>"
        "<style>.c{color:red}</style><p>b</p>"));
    assert(b.text == QStringLiteral("a\nb"));
    assert(b.blockKinds == (QVector<int>{0, 0}));
    assert(b.charCount == 2);
}

// ---- 9. malformed chapter: undefined entity -> empty placeholder ---------------------
// &rarr; is NOT in the HtmlEntities table, so substituteEntities leaves it verbatim and
// QXmlStreamReader raises a not-well-formed error on the undeclared entity. Android's
// XhtmlMapper.parse THROWS on that, and the reader renders a placeholder whose built text
// is empty (charCount 0). The port must DISCARD the "first" paragraph parsed before the
// error and return an empty BuiltChapter — not "first" (which is what a best-effort partial
// build would leak). Empty text keeps the kosync offset space identical across devices.
static void testMalformedChapter()
{
    const BuiltChapter b = buildBody(QStringLiteral("<p>first</p><p>bad &rarr; here</p>"));
    assert(b.text.isEmpty());
    assert(b.charCount == 0);
    assert(b.blockRanges.isEmpty() && b.blockKinds.isEmpty());
}

// ---- 10. CDATA: entity refs inside CDATA are literal ---------------------------------
// By XML rules text in "<![CDATA[ ... ]]>" is literal — "&nbsp;" is NOT resolved. Android
// registers entities on the parser (so CDATA is never touched); the port must skip the pre-
// substitution over CDATA spans. The 8 literal chars "a&nbsp;b" (no collapsible whitespace)
// survive verbatim — NOT collapsed to "a b" (that would be the wrong 3-char result).
static void testCdataLiteral()
{
    const BuiltChapter b = buildBody(QStringLiteral("<![CDATA[a&nbsp;b]]>"));
    assert(b.text == QStringLiteral("a&nbsp;b"));  // 8 chars: a & n b s p ; b
    assert(b.charCount == 8);
}

// ---- 11. windows-1252 decode of 0x80-0x9F -------------------------------------------
// A doc declared encoding="windows-1252" with raw bytes 0x93/0x94/0x97: cp1252 maps these
// to U+201C/U+201D/U+2014 (curly double quotes + em dash), NOT the C1 control chars a naive
// Latin-1 decode would yield. None are collapsible whitespace, so "a“b”c—d" (7 code units)
// is the built text. Bytes are split across string literals so \x.. escapes don't swallow
// the following hex-digit letter.
static void testWindows1252()
{
    const QByteArray raw = QByteArray(
        "<?xml version=\"1.0\" encoding=\"windows-1252\"?>"
        "<body><p>a\x93" "b\x94" "c\x97" "d</p></body>");
    const BuiltChapter b = buildChapterFromXhtml(raw, 0);
    const QString expected = QStringLiteral("a") + QChar(0x201C) + QLatin1Char('b') +
                             QChar(0x201D) + QLatin1Char('c') + QChar(0x2014) + QLatin1Char('d');
    assert(b.text == expected);
    assert(b.charCount == 7);
}

// ---- bonus: inline spans, links, anchors (style-bit / href ranges) -------------------
static void testSpansLinksAnchors()
{
    // <b> around "bold": span ((1,5),bit1). text "aboldc".
    const BuiltChapter sp = buildBody(QStringLiteral("<p>a<b>bold</b>c</p>"));
    assert(sp.text == QStringLiteral("aboldc"));
    assert((sp.spans == QVector<QPair<QPair<int, int>, int>>{{{1, 5}, 1}}));

    // internal link "note" over [4,8); "see note here".
    const BuiltChapter lk =
        buildBody(QStringLiteral("<p>see <a href=\"ch2.xhtml#n\">note</a> here</p>"));
    assert(lk.text == QStringLiteral("see note here"));
    assert((lk.links == QVector<QPair<QPair<int, int>, QString>>{
                {{4, 8}, QStringLiteral("ch2.xhtml#n")}}));

    // external links do not become link ranges (http/mailto skipped).
    const BuiltChapter ex =
        buildBody(QStringLiteral("<p><a href=\"http://x.com\">x</a></p>"));
    assert(ex.text == QStringLiteral("x"));
    assert(ex.links.isEmpty());

    // id on a block -> anchors[id] == that block's index (first wins).
    const BuiltChapter an = buildBody(QStringLiteral("<p id=\"intro\">Hello</p>"));
    assert(an.anchors.value(QStringLiteral("intro"), -1) == 0);
}

// ---- resolvePath (EpubParser.resolvePath) --------------------------------------------
static void testResolvePath()
{
    assert(resolvePath(QStringLiteral("OEBPS"), QStringLiteral("chapter1.xhtml")) ==
           QStringLiteral("OEBPS/chapter1.xhtml"));
    // "../" pops the last base segment.
    assert(resolvePath(QStringLiteral("OEBPS/text"), QStringLiteral("../images/cover.png")) ==
           QStringLiteral("OEBPS/images/cover.png"));
    // fragment then query stripped.
    assert(resolvePath(QStringLiteral("OEBPS"), QStringLiteral("c.xhtml#frag")) ==
           QStringLiteral("OEBPS/c.xhtml"));
    assert(resolvePath(QStringLiteral("OEBPS"), QStringLiteral("c.xhtml?x=1")) ==
           QStringLiteral("OEBPS/c.xhtml"));
    // empty base.
    assert(resolvePath(QString(), QStringLiteral("content.opf")) == QStringLiteral("content.opf"));
    // leading '/' resolves against archive root, ignoring base.
    assert(resolvePath(QStringLiteral("dir"), QStringLiteral("/abs/path.xhtml")) ==
           QStringLiteral("abs/path.xhtml"));
    // "." and ".." normalized.
    assert(resolvePath(QStringLiteral("OEBPS"), QStringLiteral("sub/./a/../b.xhtml")) ==
           QStringLiteral("OEBPS/sub/b.xhtml"));
    // percent-decode (%20 -> space); '+' left as-is.
    assert(resolvePath(QStringLiteral("OEBPS"), QStringLiteral("a%20b+c.xhtml")) ==
           QStringLiteral("OEBPS/a b+c.xhtml"));
    // backslash normalized to '/'.
    assert(resolvePath(QStringLiteral("OEBPS"), QStringLiteral("sub\\a.xhtml")) ==
           QStringLiteral("OEBPS/sub/a.xhtml"));
    // ".." beyond root is dropped (cannot escape the archive).
    assert(resolvePath(QString(), QStringLiteral("../../a.xhtml")) == QStringLiteral("a.xhtml"));
}

// ---- spine numbering: missing spine entry retained (not compacted) -------------------
static void testSpineNumbering(const QString &dir)
{
    const QByteArray container =
        "<?xml version=\"1.0\"?>\n"
        "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n"
        "  <rootfiles><rootfile full-path=\"content.opf\" "
        "media-type=\"application/oebps-package+xml\"/></rootfiles>\n"
        "</container>\n";
    const QByteArray opf =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\">\n"
        "  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
        "    <dc:title>Test Book</dc:title>\n"
        "    <dc:creator>An Author</dc:creator>\n"
        "    <dc:language>en</dc:language>\n"
        "  </metadata>\n"
        "  <manifest>\n"
        "    <item id=\"c0\" href=\"ch0.xhtml\" media-type=\"application/xhtml+xml\"/>\n"
        "    <item id=\"c1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>\n"
        "    <item id=\"c2\" href=\"ch2.xhtml\" media-type=\"application/xhtml+xml\"/>\n"
        "  </manifest>\n"
        "  <spine>\n"
        "    <itemref idref=\"c0\"/>\n"
        "    <itemref idref=\"c1\"/>\n"
        "    <itemref idref=\"c2\"/>\n"
        "  </spine>\n"
        "</package>\n";
    const QByteArray ch0 = "<html><body><p>Chapter zero.</p></body></html>";
    const QByteArray ch2 = "<html><body><p>Chapter two.</p></body></html>";

    const QString path = dir + QStringLiteral("/book.epub");
    {
        QZipWriter zw(path);
        zw.addFile(QStringLiteral("mimetype"), QByteArray("application/epub+zip"));
        zw.addFile(QStringLiteral("META-INF/container.xml"), container);
        zw.addFile(QStringLiteral("content.opf"), opf);
        zw.addFile(QStringLiteral("ch0.xhtml"), ch0);
        // ch1.xhtml deliberately NOT written: its spine slot must be retained as a placeholder.
        zw.addFile(QStringLiteral("ch2.xhtml"), ch2);
        zw.close();
    }

    QString err;
    EpubDocument *doc = EpubDocument::open(path, &err);
    assert(doc && err.isEmpty());

    // Spine is NOT compacted: 3 itemrefs -> 3 slots, index preserved.
    assert(doc->spineCount() == 3);
    assert(doc->chapter(0).text == QStringLiteral("Chapter zero."));
    assert(doc->chapter(1).text.isEmpty() && doc->chapter(1).charCount == 0);  // missing -> placeholder
    assert(doc->chapter(2).text == QStringLiteral("Chapter two."));

    // Weights: present entries use (uncompressed) size >= 1; the missing one is exactly 1.
    const QVector<qint64> w = doc->spineWeights();
    assert(w.size() == 3);
    assert(w[0] >= 1 && w[2] >= 1);
    assert(w[1] == 1);

    // Metadata parsed from OPF.
    assert(doc->metadata().title == QStringLiteral("Test Book"));
    assert(doc->metadata().author == QStringLiteral("An Author"));
    assert(doc->metadata().language == QStringLiteral("en"));

    // No nav/ncx -> fallback TOC of spineCount entries.
    const QVector<EpubTocEntry> toc = doc->toc();
    assert(toc.size() == 3);
    assert(toc[0].title == QStringLiteral("Chapter 1") && toc[0].spineIndex == 0);
    assert(toc[2].title == QStringLiteral("Chapter 3") && toc[2].spineIndex == 2);

    // Internal link resolves across chapters; external does not.
    const LinkTarget lt = doc->resolveLink(0, QStringLiteral("ch2.xhtml#sec"));
    assert(lt.ok && lt.spineIndex == 2 && lt.anchor == QStringLiteral("sec"));
    assert(!doc->resolveLink(0, QStringLiteral("http://example.com")).ok);

    delete doc;
}

int main()
{
    QTemporaryDir tmp;
    assert(tmp.isValid());

    testWhitespaceCollapse();
    testNbspCollapse();
    testEntities();
    testTwoParagraphs();
    testRuleAndImage();
    testLineBreak();
    testHeading();
    testSkippedTags();
    testMalformedChapter();
    testCdataLiteral();
    testWindows1252();
    testSpansLinksAnchors();
    testResolvePath();
    testSpineNumbering(tmp.path());

    printf("ALL EPUB TEXT TESTS PASSED\n");
    return 0;
}
