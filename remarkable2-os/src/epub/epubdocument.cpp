#include "epub/epubdocument.h"

#include <QRegularExpression>
#include <QSet>
#include <QStringConverter>
#include <QStringList>
#include <QXmlStreamReader>

#include <QtCore/private/qzipreader_p.h>

#include <memory>

// Port of Android XhtmlMapper.kt + Pagination.buildChapterText + EpubParser.kt.
// Byte-identical built text is the contract (kosync char_offset space). Where a
// deviation from Kotlin is unavoidable it is marked `// DEVIATION:`.

namespace {

// ---- constants (match EpubParser.kt) -------------------------------------------------

constexpr char16_t kImageChar = 0xFFFC;  // U+FFFC object replacement char (one per image)
constexpr QLatin1StringView kRuleText("* * *");
const QString kDcNs = QStringLiteral("http://purl.org/dc/elements/1.1/");
const QString kOpsNs = QStringLiteral("http://www.idpf.org/2007/ops");
const QString kXlinkNs = QStringLiteral("http://www.w3.org/1999/xlink");
constexpr QLatin1StringView kOpfMediaType("application/oebps-package+xml");
constexpr QLatin1StringView kNcxMediaType("application/x-dtbncx+xml");
constexpr int kChapterCacheSize = 3;

// ---- whitespace (Kotlin Char.isWhitespace == isCollapsibleSpace) ---------------------

// Kotlin's Char.isWhitespace() == Character.isWhitespace() || Character.isSpaceChar(),
// which already covers U+00A0/U+2007/U+202F (all Zs), so XhtmlMapper's explicit checks
// for those three are redundant and this single predicate reproduces isCollapsibleSpace
// exactly. Enumerated so it never drifts with Qt's bundled Unicode version.
bool isCollapsibleSpace(QChar c)
{
    const char16_t u = c.unicode();
    switch (u) {
    case 0x09: case 0x0A: case 0x0B: case 0x0C: case 0x0D:  // \t \n \v \f \r
    case 0x1C: case 0x1D: case 0x1E: case 0x1F:              // FS GS RS US (Java isWhitespace)
    case 0x20: case 0xA0: case 0x1680:
    case 0x2028: case 0x2029: case 0x202F: case 0x205F: case 0x3000:
        return true;
    default:
        return u >= 0x2000 && u <= 0x200A;  // Zs run (includes U+2007 figure space)
    }
}

bool isBlank(const QString &s)
{
    for (QChar c : s)
        if (!isCollapsibleSpace(c))
            return false;
    return true;
}

// ---- HTML named entities (HtmlEntities table, XhtmlMapper.kt) -------------------------

// The table minus the XML-predefined five: those are left in the byte stream for the XML
// parser to resolve (substituting "&amp;" -> "&" would produce a bare '&' the parser
// rejects). Net text is identical: the parser resolves amp/lt/gt/quot/apos to the same
// replacement Kotlin's defineEntityReplacementText did.
const QHash<QString, QString> &entitySubst()
{
    static const QHash<QString, QString> t = {
        {QStringLiteral("nbsp"), QStringLiteral(" ")}, {QStringLiteral("shy"), QStringLiteral("­")},
        {QStringLiteral("ensp"), QStringLiteral(" ")}, {QStringLiteral("emsp"), QStringLiteral(" ")},
        {QStringLiteral("thinsp"), QStringLiteral(" ")}, {QStringLiteral("zwnj"), QStringLiteral("‌")},
        {QStringLiteral("zwj"), QStringLiteral("‍")}, {QStringLiteral("lrm"), QStringLiteral("‎")},
        {QStringLiteral("rlm"), QStringLiteral("‏")},
        {QStringLiteral("ndash"), QStringLiteral("–")}, {QStringLiteral("mdash"), QStringLiteral("—")},
        {QStringLiteral("minus"), QStringLiteral("−")},
        {QStringLiteral("lsquo"), QStringLiteral("‘")}, {QStringLiteral("rsquo"), QStringLiteral("’")},
        {QStringLiteral("sbquo"), QStringLiteral("‚")},
        {QStringLiteral("ldquo"), QStringLiteral("“")}, {QStringLiteral("rdquo"), QStringLiteral("”")},
        {QStringLiteral("bdquo"), QStringLiteral("„")},
        {QStringLiteral("prime"), QStringLiteral("′")}, {QStringLiteral("Prime"), QStringLiteral("″")},
        {QStringLiteral("lsaquo"), QStringLiteral("‹")}, {QStringLiteral("rsaquo"), QStringLiteral("›")},
        {QStringLiteral("laquo"), QStringLiteral("«")}, {QStringLiteral("raquo"), QStringLiteral("»")},
        {QStringLiteral("hellip"), QStringLiteral("…")}, {QStringLiteral("bull"), QStringLiteral("•")},
        {QStringLiteral("dagger"), QStringLiteral("†")}, {QStringLiteral("Dagger"), QStringLiteral("‡")},
        {QStringLiteral("permil"), QStringLiteral("‰")}, {QStringLiteral("euro"), QStringLiteral("€")},
        {QStringLiteral("trade"), QStringLiteral("™")}, {QStringLiteral("copy"), QStringLiteral("©")},
        {QStringLiteral("reg"), QStringLiteral("®")},
        {QStringLiteral("deg"), QStringLiteral("°")}, {QStringLiteral("plusmn"), QStringLiteral("±")},
        {QStringLiteral("micro"), QStringLiteral("µ")}, {QStringLiteral("para"), QStringLiteral("¶")},
        {QStringLiteral("middot"), QStringLiteral("·")},
        {QStringLiteral("sect"), QStringLiteral("§")}, {QStringLiteral("cent"), QStringLiteral("¢")},
        {QStringLiteral("pound"), QStringLiteral("£")}, {QStringLiteral("curren"), QStringLiteral("¤")},
        {QStringLiteral("yen"), QStringLiteral("¥")},
        {QStringLiteral("brvbar"), QStringLiteral("¦")}, {QStringLiteral("uml"), QStringLiteral("¨")},
        {QStringLiteral("ordf"), QStringLiteral("ª")}, {QStringLiteral("not"), QStringLiteral("¬")},
        {QStringLiteral("macr"), QStringLiteral("¯")},
        {QStringLiteral("acute"), QStringLiteral("´")}, {QStringLiteral("cedil"), QStringLiteral("¸")},
        {QStringLiteral("ordm"), QStringLiteral("º")},
        {QStringLiteral("sup1"), QStringLiteral("¹")}, {QStringLiteral("sup2"), QStringLiteral("²")},
        {QStringLiteral("sup3"), QStringLiteral("³")},
        {QStringLiteral("frac14"), QStringLiteral("¼")}, {QStringLiteral("frac12"), QStringLiteral("½")},
        {QStringLiteral("frac34"), QStringLiteral("¾")},
        {QStringLiteral("iexcl"), QStringLiteral("¡")}, {QStringLiteral("iquest"), QStringLiteral("¿")},
        {QStringLiteral("times"), QStringLiteral("×")}, {QStringLiteral("divide"), QStringLiteral("÷")},
        {QStringLiteral("fnof"), QStringLiteral("ƒ")}, {QStringLiteral("circ"), QStringLiteral("ˆ")},
        {QStringLiteral("tilde"), QStringLiteral("˜")}, {QStringLiteral("oline"), QStringLiteral("‾")},
        {QStringLiteral("frasl"), QStringLiteral("⁄")},
        {QStringLiteral("OElig"), QStringLiteral("Œ")}, {QStringLiteral("oelig"), QStringLiteral("œ")},
        {QStringLiteral("Scaron"), QStringLiteral("Š")}, {QStringLiteral("scaron"), QStringLiteral("š")},
        {QStringLiteral("Yuml"), QStringLiteral("Ÿ")},
        {QStringLiteral("Agrave"), QStringLiteral("À")}, {QStringLiteral("Aacute"), QStringLiteral("Á")},
        {QStringLiteral("Acirc"), QStringLiteral("Â")}, {QStringLiteral("Atilde"), QStringLiteral("Ã")},
        {QStringLiteral("Auml"), QStringLiteral("Ä")}, {QStringLiteral("Aring"), QStringLiteral("Å")},
        {QStringLiteral("AElig"), QStringLiteral("Æ")}, {QStringLiteral("Ccedil"), QStringLiteral("Ç")},
        {QStringLiteral("Egrave"), QStringLiteral("È")}, {QStringLiteral("Eacute"), QStringLiteral("É")},
        {QStringLiteral("Ecirc"), QStringLiteral("Ê")}, {QStringLiteral("Euml"), QStringLiteral("Ë")},
        {QStringLiteral("Igrave"), QStringLiteral("Ì")}, {QStringLiteral("Iacute"), QStringLiteral("Í")},
        {QStringLiteral("Icirc"), QStringLiteral("Î")}, {QStringLiteral("Iuml"), QStringLiteral("Ï")},
        {QStringLiteral("ETH"), QStringLiteral("Ð")}, {QStringLiteral("Ntilde"), QStringLiteral("Ñ")},
        {QStringLiteral("Ograve"), QStringLiteral("Ò")}, {QStringLiteral("Oacute"), QStringLiteral("Ó")},
        {QStringLiteral("Ocirc"), QStringLiteral("Ô")}, {QStringLiteral("Otilde"), QStringLiteral("Õ")},
        {QStringLiteral("Ouml"), QStringLiteral("Ö")}, {QStringLiteral("Oslash"), QStringLiteral("Ø")},
        {QStringLiteral("Ugrave"), QStringLiteral("Ù")}, {QStringLiteral("Uacute"), QStringLiteral("Ú")},
        {QStringLiteral("Ucirc"), QStringLiteral("Û")}, {QStringLiteral("Uuml"), QStringLiteral("Ü")},
        {QStringLiteral("Yacute"), QStringLiteral("Ý")}, {QStringLiteral("THORN"), QStringLiteral("Þ")},
        {QStringLiteral("szlig"), QStringLiteral("ß")},
        {QStringLiteral("agrave"), QStringLiteral("à")}, {QStringLiteral("aacute"), QStringLiteral("á")},
        {QStringLiteral("acirc"), QStringLiteral("â")}, {QStringLiteral("atilde"), QStringLiteral("ã")},
        {QStringLiteral("auml"), QStringLiteral("ä")}, {QStringLiteral("aring"), QStringLiteral("å")},
        {QStringLiteral("aelig"), QStringLiteral("æ")}, {QStringLiteral("ccedil"), QStringLiteral("ç")},
        {QStringLiteral("egrave"), QStringLiteral("è")}, {QStringLiteral("eacute"), QStringLiteral("é")},
        {QStringLiteral("ecirc"), QStringLiteral("ê")}, {QStringLiteral("euml"), QStringLiteral("ë")},
        {QStringLiteral("igrave"), QStringLiteral("ì")}, {QStringLiteral("iacute"), QStringLiteral("í")},
        {QStringLiteral("icirc"), QStringLiteral("î")}, {QStringLiteral("iuml"), QStringLiteral("ï")},
        {QStringLiteral("eth"), QStringLiteral("ð")}, {QStringLiteral("ntilde"), QStringLiteral("ñ")},
        {QStringLiteral("ograve"), QStringLiteral("ò")}, {QStringLiteral("oacute"), QStringLiteral("ó")},
        {QStringLiteral("ocirc"), QStringLiteral("ô")}, {QStringLiteral("otilde"), QStringLiteral("õ")},
        {QStringLiteral("ouml"), QStringLiteral("ö")}, {QStringLiteral("oslash"), QStringLiteral("ø")},
        {QStringLiteral("ugrave"), QStringLiteral("ù")}, {QStringLiteral("uacute"), QStringLiteral("ú")},
        {QStringLiteral("ucirc"), QStringLiteral("û")}, {QStringLiteral("uuml"), QStringLiteral("ü")},
        {QStringLiteral("yacute"), QStringLiteral("ý")}, {QStringLiteral("thorn"), QStringLiteral("þ")},
        {QStringLiteral("yuml"), QStringLiteral("ÿ")},
    };
    return t;
}

// Decode raw bytes to a QString, honoring a UTF BOM or an XML `encoding=` declaration.
// EPUB content is effectively UTF-8/UTF-16; legacy single-byte encodings map to Latin-1.
// Feeding QXmlStreamReader a QString makes it treat input as already-Unicode and skip its
// own byte decoding, so entity substitution below is encoding-safe.
QString decodeXml(const QByteArray &bytes)
{
    if (auto enc = QStringConverter::encodingForData(bytes)) {  // BOM present
        QStringDecoder dec(*enc);
        return dec.decode(bytes);
    }
    QStringConverter::Encoding e = QStringConverter::Utf8;  // default
    const QByteArray head = bytes.left(256).toLower();
    int i = head.indexOf("encoding");
    if (i >= 0) {
        int eq = head.indexOf('=', i);
        int q1 = -1;
        for (int j = eq + 1; eq >= 0 && j < head.size(); ++j) {
            char ch = head[j];
            if (ch == '"' || ch == '\'') { q1 = j; break; }
            if (ch != ' ' && ch != '\t') break;
        }
        if (q1 >= 0) {
            char quote = head[q1];
            int q2 = head.indexOf(quote, q1 + 1);
            if (q2 > q1) {
                const QByteArray name = head.mid(q1 + 1, q2 - q1 - 1);
                if (name.contains("utf-16"))
                    e = QStringConverter::Utf16;
                else if (name.contains("8859-1") || name.contains("latin1") ||
                         name.contains("1252"))  // cp1252 ~ Latin-1 (differ 0x80-0x9F). ponytail: rare in EPUB.
                    e = QStringConverter::Latin1;
                // else default Utf8 (utf-8, us-ascii, unknown)
            }
        }
    }
    QStringDecoder dec(e);
    return dec.decode(bytes);
}

// Replace every "&name;" whose name is in the entity table with its replacement char.
// Leaves the XML-predefined five and numeric refs (&#..;) for the parser. Unknown named
// entities are left as-is so the parser errors on them, exactly as Kotlin's undefined-
// entity path throws.
// ponytail: substitutes inside comments/CDATA too; comments are ignored, script/style are
// SkippedTags, and a named entity as literal visible CDATA text is vanishingly rare.
QString substituteEntities(const QString &in)
{
    if (!in.contains('&'))
        return in;
    QString out;
    out.reserve(in.size());
    const int n = in.size();
    int i = 0;
    while (i < n) {
        const QChar c = in[i];
        if (c != '&') {
            out.append(c);
            ++i;
            continue;
        }
        const int j = i + 1;
        const int maxj = qMin(n, j + 32);  // entity names are short; cap the scan
        int k = j;
        while (k < maxj) {
            const QChar ch = in[k];
            const bool alnum = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') ||
                               (ch >= '0' && ch <= '9');
            if (!alnum)
                break;
            ++k;
        }
        if (k > j && k < n && in[k] == ';') {
            auto it = entitySubst().constFind(in.mid(j, k - j));
            if (it != entitySubst().constEnd()) {
                out.append(it.value());
                i = k + 1;
                continue;
            }
        }
        out.append(c);  // predefined / numeric / unknown: leave for the parser
        ++i;
    }
    return out;
}

QString prepareXml(const QByteArray &bytes) { return substituteEntities(decodeXml(bytes)); }

// ---- attribute access (XmlPullParser.getAttributeValue semantics) --------------------

// getAttributeValue(null, name): first attribute whose LOCAL name matches, any namespace.
bool getAttr(const QXmlStreamAttributes &a, QStringView name, QString *out)
{
    for (const QXmlStreamAttribute &x : a) {
        if (x.name() == name) {
            *out = x.value().toString();
            return true;
        }
    }
    return false;
}

// getAttributeValue(namespaceUri, name): local name + namespace both match.
bool getAttrNs(const QXmlStreamAttributes &a, QStringView ns, QStringView name, QString *out)
{
    for (const QXmlStreamAttribute &x : a) {
        if (x.name() == name && x.namespaceUri() == ns) {
            *out = x.value().toString();
            return true;
        }
    }
    return false;
}

// ---- block model + inline accumulator (XhtmlMapper.kt) -------------------------------

struct Span { int start, end, bit; };
struct Link { int start, end; QString href; };
struct MappedBlock {
    int kind = 0;    // 0 para, 1 heading, 2 rule, 3 image
    int level = 0;   // heading level (1..6), else 0
    QString text;    // para/heading text
    QString zipPath; // image path
    QVector<Span> spans;
    QVector<Link> links;
};
struct MappedChapter {
    QVector<MappedBlock> blocks;
    QHash<QString, int> anchors;
};

// Inline style bit for a tag name (b/strong=1 i/em=2 u=4 sub=8 sup=16), 0 if not an
// inline style tag. Bold and italic share a bit across their two tag spellings, exactly
// like the shared SpanStyle vals compared by equality in Kotlin.
int inlineStyleBit(const QString &name)
{
    if (name == QLatin1String("b") || name == QLatin1String("strong")) return 1;
    if (name == QLatin1String("i") || name == QLatin1String("em")) return 2;
    if (name == QLatin1String("u")) return 4;
    if (name == QLatin1String("sub")) return 8;
    if (name == QLatin1String("sup")) return 16;
    return 0;
}

// Builds one paragraph's text with whitespace collapsed on the way in; span/link offsets
// are exact offsets into the emitted (trimmed) string. Mirrors InlineAccumulator.
class InlineAccumulator {
public:
    void append(QStringView raw)
    {
        for (QChar ch : raw) {
            if (isCollapsibleSpace(ch)) {
                if (!m_text.isEmpty() && m_text.back() != ' ' && m_text.back() != '\n')
                    m_text.append(' ');
            } else {
                m_text.append(ch);
            }
        }
    }

    void lineBreak()
    {
        if (m_text.isEmpty())
            return;
        if (m_text.back() == ' ')
            m_text.chop(1);
        if (!m_text.isEmpty())
            m_text.append('\n');
    }

    void pushSpan(int bit) { m_openSpans.append({bit, int(m_text.size())}); }

    void popSpan(int bit)
    {
        for (int idx = m_openSpans.size() - 1; idx >= 0; --idx) {
            if (m_openSpans[idx].bit == bit) {
                const OpenSpan s = m_openSpans[idx];
                m_openSpans.removeAt(idx);
                if (s.start < m_text.size())
                    m_closedSpans.append({s.bit, s.start, int(m_text.size())});
                return;
            }
        }
    }

    void pushLink(const QString &href) { m_openLinks.append({href, int(m_text.size())}); }

    void popLink()
    {
        if (m_openLinks.isEmpty())
            return;
        const OpenLink l = m_openLinks.takeLast();
        if (l.start < m_text.size())
            m_closedLinks.append({l.href, l.start, int(m_text.size())});
    }

    // Emits the trimmed accumulated text (false if blank) and resets, carrying still-open
    // spans/links into the next block at offset 0.
    bool flush(QString *outText, QVector<Span> *outSpans, QVector<Link> *outLinks)
    {
        int end = m_text.size();
        while (end > 0 && (m_text[end - 1] == ' ' || m_text[end - 1] == '\n'))
            --end;
        const bool produced = end != 0;
        if (produced) {
            *outText = m_text.left(end);
            outSpans->clear();
            outLinks->clear();
            for (const ClosedSpan &s : m_closedSpans) {
                const int ce = qMin(s.end, end);
                if (s.start < ce)
                    outSpans->append({s.start, ce, s.bit});
            }
            for (const OpenSpan &s : m_openSpans)
                if (s.start < end)
                    outSpans->append({s.start, end, s.bit});
            for (const ClosedLink &l : m_closedLinks) {
                const int ce = qMin(l.end, end);
                if (l.start < ce)
                    outLinks->append({l.start, ce, l.href});
            }
            for (const OpenLink &l : m_openLinks)
                if (l.start < end)
                    outLinks->append({l.start, end, l.href});
        }
        m_text.clear();
        m_closedSpans.clear();
        m_closedLinks.clear();
        for (OpenSpan &s : m_openSpans)
            s.start = 0;
        for (OpenLink &l : m_openLinks)
            l.start = 0;
        return produced;
    }

private:
    struct OpenSpan { int bit, start; };
    struct ClosedSpan { int bit, start, end; };
    struct OpenLink { QString href; int start; };
    struct ClosedLink { QString href; int start, end; };
    QString m_text;
    QVector<OpenSpan> m_openSpans;
    QVector<ClosedSpan> m_closedSpans;
    QVector<OpenLink> m_openLinks;
    QVector<ClosedLink> m_closedLinks;
};

bool isHeadingTag(const QString &name)
{
    return name.size() == 2 && name[0] == QLatin1Char('h') && name[1] >= QLatin1Char('1') &&
           name[1] <= QLatin1Char('6');
}

bool isSkippedTag(const QString &n)
{
    return n == QLatin1String("script") || n == QLatin1String("style") || n == QLatin1String("head");
}

// Tags whose open or close ends the current inline run and emits a block.
bool isBlockBoundaryTag(const QString &n)
{
    static const QSet<QString> s = {
        QStringLiteral("p"), QStringLiteral("div"), QStringLiteral("li"), QStringLiteral("blockquote"),
        QStringLiteral("ul"), QStringLiteral("ol"), QStringLiteral("dl"), QStringLiteral("dt"),
        QStringLiteral("dd"), QStringLiteral("section"), QStringLiteral("article"), QStringLiteral("aside"),
        QStringLiteral("header"), QStringLiteral("footer"), QStringLiteral("main"), QStringLiteral("nav"),
        QStringLiteral("address"), QStringLiteral("figure"), QStringLiteral("figcaption"),
        QStringLiteral("table"), QStringLiteral("thead"), QStringLiteral("tbody"), QStringLiteral("tfoot"),
        QStringLiteral("tr"), QStringLiteral("td"), QStringLiteral("th"), QStringLiteral("pre"),
        QStringLiteral("body"),
    };
    return s.contains(n);
}

// Maps one spine XHTML document into flow blocks + anchor map. Byte-identical to
// XhtmlMapper.readBlocks. `content` is already decoded + entity-substituted.
MappedChapter readBlocks(const QString &content,
                         const std::function<QString(const QString &)> &resolveImage, bool *ok)
{
    QVector<MappedBlock> blocks;
    QHash<QString, int> anchors;
    InlineAccumulator acc;
    QList<int> headingLevels;
    int skipDepth = 0;

    auto flushBlock = [&]() {
        MappedBlock b;
        if (!acc.flush(&b.text, &b.spans, &b.links))
            return;
        if (!headingLevels.isEmpty()) {
            b.kind = 1;
            b.level = headingLevels.last();
        }
        blocks.append(std::move(b));
    };

    QXmlStreamReader xml(content);
    while (!xml.atEnd()) {
        const QXmlStreamReader::TokenType tok = xml.readNext();
        if (tok == QXmlStreamReader::StartElement) {
            if (skipDepth > 0) {
                ++skipDepth;
                continue;
            }
            const QString name = xml.name().toString().toLower();
            const QXmlStreamAttributes attrs = xml.attributes();
            if (isSkippedTag(name)) {
                skipDepth = 1;
            } else if (isBlockBoundaryTag(name)) {
                flushBlock();
            } else if (isHeadingTag(name)) {
                flushBlock();
                headingLevels.append(name[1].unicode() - '0');
            } else if (name == QLatin1String("hr")) {
                flushBlock();
                MappedBlock b;
                b.kind = 2;
                blocks.append(b);
            } else if (name == QLatin1String("img") || name == QLatin1String("image")) {
                QString href;
                const bool have = getAttr(attrs, u"src", &href) || getAttr(attrs, u"href", &href) ||
                                  getAttrNs(attrs, kXlinkNs, u"href", &href);
                const QString path = (have && resolveImage) ? resolveImage(href) : QString();
                if (!path.isEmpty()) {
                    flushBlock();
                    MappedBlock b;
                    b.kind = 3;
                    b.zipPath = path;
                    blocks.append(b);
                }
            } else if (name == QLatin1String("a")) {
                QString href;
                if (getAttr(attrs, u"href", &href) &&
                    !href.startsWith(QLatin1String("http"), Qt::CaseInsensitive) &&
                    !href.startsWith(QLatin1String("mailto:"), Qt::CaseInsensitive))
                    acc.pushLink(href);
            } else if (name == QLatin1String("br")) {
                acc.lineBreak();
            } else if (int bit = inlineStyleBit(name)) {
                acc.pushSpan(bit);
            }
            // Record any anchor id -> block index it marks (first wins). skipDepth is 0
            // here unless this very tag opened a skipped subtree.
            if (skipDepth == 0) {
                QString id;
                if (getAttr(attrs, u"id", &id) && !isBlank(id) && !anchors.contains(id))
                    anchors.insert(id, int(blocks.size()));
            }
        } else if (tok == QXmlStreamReader::EndElement) {
            if (skipDepth > 0) {
                --skipDepth;
                continue;
            }
            const QString name = xml.name().toString().toLower();
            if (isBlockBoundaryTag(name)) {
                flushBlock();
            } else if (isHeadingTag(name)) {
                flushBlock();
                if (!headingLevels.isEmpty())
                    headingLevels.removeLast();
            } else if (name == QLatin1String("a")) {
                acc.popLink();
            } else if (int bit = inlineStyleBit(name)) {
                acc.popSpan(bit);
            }
        } else if (tok == QXmlStreamReader::Characters) {
            if (skipDepth == 0)
                acc.append(xml.text());
        }
    }
    flushBlock();
    if (ok)
        *ok = !xml.hasError();
    return {blocks, anchors};
}

// Flattens blocks into one offset space, byte-identical to Pagination.buildChapterText:
// blocks concatenated, "* * *" rules, one U+FFFC per image, '\n' between blocks kept
// OUTSIDE both adjacent ranges (contentEnd = length-1 when separated).
BuiltChapter buildChapterText(int spineIndex, const MappedChapter &mc)
{
    BuiltChapter bc;
    bc.spineIndex = spineIndex;
    bc.anchors = mc.anchors;

    const int last = int(mc.blocks.size()) - 1;
    for (int index = 0; index <= last; ++index) {
        const MappedBlock &block = mc.blocks[index];
        const int start = int(bc.text.size());
        const bool separate = index < last;
        int level = 0;
        switch (block.kind) {
        case 0:  // paragraph
        case 1:  // heading
            level = block.level;
            for (const Span &s : block.spans)
                bc.spans.append({{start + s.start, start + s.end}, s.bit});
            for (const Link &l : block.links)
                bc.links.append({{start + l.start, start + l.end}, l.href});
            bc.text += block.text;
            bc.charCount += int(block.text.size());
            break;
        case 2:  // rule
            bc.text += kRuleText;
            bc.charCount += 1;
            break;
        default:  // image
            bc.images.append({int(bc.text.size()), block.zipPath});
            bc.text += QChar(kImageChar);
            bc.charCount += 1;
            break;
        }
        if (separate)
            bc.text += QLatin1Char('\n');
        const int contentEnd = separate ? int(bc.text.size()) - 1 : int(bc.text.size());
        bc.blockRanges.append({start, contentEnd});
        bc.blockKinds.append(block.kind);
        bc.headingLevels.append(level);
    }
    return bc;
}

// ---- EpubParser.kt: path resolution, container/OPF/TOC parsing -----------------------

int hexDigit(QChar c)
{
    const char16_t u = c.unicode();
    if (u >= '0' && u <= '9') return u - '0';
    if (u >= 'a' && u <= 'f') return u - 'a' + 10;
    if (u >= 'A' && u <= 'F') return u - 'A' + 10;
    return -1;
}

// Percent-decode %HH (UTF-8), leaving '+' and lone/invalid '%' untouched — EpubParser.percentDecode.
QString percentDecode(const QString &encoded)
{
    if (!encoded.contains('%'))
        return encoded;
    QByteArray out;
    int literalStart = 0, i = 0;
    const int n = encoded.size();
    auto flushLiteral = [&](int end) {
        if (literalStart < end)
            out += encoded.mid(literalStart, end - literalStart).toUtf8();
    };
    while (i < n) {
        if (encoded[i] == '%' && i + 2 < n) {
            const int hi = hexDigit(encoded[i + 1]);
            const int lo = hexDigit(encoded[i + 2]);
            if (hi >= 0 && lo >= 0) {
                flushLiteral(i);
                out.append(char((hi << 4) | lo));
                i += 3;
                literalStart = i;
                continue;
            }
        }
        ++i;
    }
    flushLiteral(n);
    return QString::fromUtf8(out);
}

QString resolvePathImpl(const QString &baseDir, const QString &href)
{
    QString h = href;
    const int hash = h.indexOf('#');
    if (hash >= 0)
        h = h.left(hash);
    const int q = h.indexOf('?');
    if (q >= 0)
        h = h.left(q);
    QString cleaned = percentDecode(h);
    cleaned.replace('\\', '/');
    QString combined;
    if (cleaned.startsWith('/'))
        combined = cleaned.mid(1);
    else if (baseDir.isEmpty())
        combined = cleaned;
    else
        combined = baseDir + '/' + cleaned;
    QStringList segments;
    const QStringList parts = combined.split('/');
    for (const QString &seg : parts) {
        if (seg.isEmpty() || seg == QLatin1String("."))
            continue;
        if (seg == QLatin1String("..")) {
            if (!segments.isEmpty())
                segments.removeLast();
        } else {
            segments.append(seg);
        }
    }
    return segments.join('/');
}

QString dirName(const QString &path)
{
    const int i = path.lastIndexOf('/');
    return i < 0 ? QString() : path.left(i);
}

// Concatenated text content of the element the reader is currently positioned on
// (a StartElement). Consumes through the matching EndElement. Mirrors collectText.
QString collectText(QXmlStreamReader &xml)
{
    QString sb;
    int depth = 1;
    while (depth > 0 && !xml.atEnd()) {
        switch (xml.readNext()) {
        case QXmlStreamReader::StartElement: ++depth; break;
        case QXmlStreamReader::EndElement: --depth; break;
        case QXmlStreamReader::Characters: sb += xml.text(); break;
        default: break;
        }
    }
    return sb;
}

// [\s ]+ -> ' ', then trim. \s here is Java-regex ASCII whitespace (EpubParser).
QString collapseWhitespace(const QString &s)
{
    QString out;
    out.reserve(s.size());
    bool inRun = false;
    for (QChar c : s) {
        const char16_t u = c.unicode();
        const bool ws = u == ' ' || u == '\t' || u == '\n' || u == 0x0B || u == '\f' ||
                        u == '\r' || u == 0xA0;
        if (ws) {
            if (!inRun) {
                out.append(' ');
                inRun = true;
            }
        } else {
            out.append(c);
            inRun = false;
        }
    }
    // Java String.trim(): strip leading/trailing <= U+0020; post-collapse boundaries are ' '.
    int b = 0, e = out.size();
    while (b < e && out[b] <= QLatin1Char(' '))
        ++b;
    while (e > b && out[e - 1] <= QLatin1Char(' '))
        --e;
    return out.mid(b, e - b);
}

struct ManifestItem {
    QString id, href, path, mediaType;
    QSet<QString> properties;
};
struct PackageDoc {
    QString title, creator, language, description;
    QVector<ManifestItem> manifest;
    QVector<QString> spineItemIds;
    QString ncxId, coverMetaId;
};

QString parseContainer(const QByteArray &bytes)
{
    QXmlStreamReader xml(prepareXml(bytes));
    QString fallback;
    while (!xml.atEnd()) {
        if (xml.readNext() == QXmlStreamReader::StartElement && xml.name() == u"rootfile") {
            QString fullPath;
            if (getAttr(xml.attributes(), u"full-path", &fullPath) && !isBlank(fullPath)) {
                QString mt;
                getAttr(xml.attributes(), u"media-type", &mt);
                if (mt == kOpfMediaType)
                    return resolvePathImpl(QString(), fullPath);
                if (fallback.isEmpty())
                    fallback = fullPath;
            }
        }
    }
    return fallback.isEmpty() ? QString() : resolvePathImpl(QString(), fallback);
}

PackageDoc parseOpf(const QByteArray &bytes, const QString &opfDir)
{
    QXmlStreamReader xml(prepareXml(bytes));
    PackageDoc pkg;
    while (!xml.atEnd()) {
        if (xml.readNext() != QXmlStreamReader::StartElement)
            continue;
        if (xml.namespaceUri() == kDcNs) {
            const QString n = xml.name().toString();
            if (n == QLatin1String("title")) {
                if (pkg.title.isEmpty())
                    pkg.title = collapseWhitespace(collectText(xml));
            } else if (n == QLatin1String("creator")) {
                if (pkg.creator.isEmpty())
                    pkg.creator = collapseWhitespace(collectText(xml));
            } else if (n == QLatin1String("language")) {
                if (pkg.language.isEmpty())
                    pkg.language = collapseWhitespace(collectText(xml));
            } else if (n == QLatin1String("description")) {
                if (pkg.description.isEmpty())
                    pkg.description = collapseWhitespace(collectText(xml));
            }
            continue;
        }
        const QString n = xml.name().toString();
        const QXmlStreamAttributes attrs = xml.attributes();
        if (n == QLatin1String("item")) {
            QString id, href;
            if (getAttr(attrs, u"id", &id) && !isBlank(id) && getAttr(attrs, u"href", &href) &&
                !isBlank(href)) {
                ManifestItem it;
                it.id = id;
                it.href = href;
                it.path = resolvePathImpl(opfDir, href);
                getAttr(attrs, u"media-type", &it.mediaType);
                QString props;
                if (getAttr(attrs, u"properties", &props))
                    for (const QString &p : props.split(QRegularExpression(QStringLiteral("[ \t\n\r]"))))
                        if (!isBlank(p))
                            it.properties.insert(p);
                pkg.manifest.append(it);
            }
        } else if (n == QLatin1String("itemref")) {
            QString idref;
            if (getAttr(attrs, u"idref", &idref) && !isBlank(idref))
                pkg.spineItemIds.append(idref);
        } else if (n == QLatin1String("spine")) {
            getAttr(attrs, u"toc", &pkg.ncxId);
        } else if (n == QLatin1String("meta")) {
            QString metaName;
            if (getAttr(attrs, u"name", &metaName) && metaName == QLatin1String("cover")) {
                QString content;
                if (getAttr(attrs, u"content", &content))
                    pkg.coverMetaId = content.trimmed();
            }
        }
    }
    return pkg;
}

int spineIndexFor(const QHash<QString, int> &lookup, const QString &path)
{
    auto it = lookup.constFind(path);
    if (it != lookup.constEnd())
        return it.value();
    it = lookup.constFind(path.toLower());
    return it != lookup.constEnd() ? it.value() : -1;
}

void collectNavLinks(QXmlStreamReader &xml, const QString &navDir,
                     const QHash<QString, int> &spineIndexByPath, QVector<EpubTocEntry> &entries)
{
    int depth = 1;  // positioned inside <nav>
    while (!xml.atEnd()) {
        const QXmlStreamReader::TokenType t = xml.readNext();
        if (t == QXmlStreamReader::StartElement) {
            if (xml.name() == u"a") {
                QString href;
                const bool have = getAttr(xml.attributes(), u"href", &href);
                const QString title = collapseWhitespace(collectText(xml));  // consumes </a>
                if (have && !isBlank(href)) {
                    const int index = spineIndexFor(spineIndexByPath, resolvePathImpl(navDir, href));
                    if (index >= 0)
                        entries.append({title.isEmpty()
                                            ? QStringLiteral("Chapter %1").arg(index + 1)
                                            : title,
                                        index});
                }
            } else {
                ++depth;
            }
        } else if (t == QXmlStreamReader::EndElement) {
            if (--depth == 0)
                return;
        }
    }
}

QVector<EpubTocEntry> parseNavToc(const QByteArray &bytes, const QString &navDir,
                                  const QHash<QString, int> &spineIndexByPath)
{
    QXmlStreamReader xml(prepareXml(bytes));
    QVector<EpubTocEntry> entries;
    while (!xml.atEnd()) {
        if (xml.readNext() == QXmlStreamReader::StartElement && xml.name() == u"nav") {
            QString epubType, role;
            getAttrNs(xml.attributes(), kOpsNs, u"type", &epubType);
            getAttr(xml.attributes(), u"role", &role);
            const bool isToc = epubType.split(' ').contains(QStringLiteral("toc")) ||
                               role == QLatin1String("doc-toc");
            if (isToc) {
                collectNavLinks(xml, navDir, spineIndexByPath, entries);
                break;
            }
        }
    }
    return entries;
}

QVector<EpubTocEntry> parseNcxToc(const QByteArray &bytes, const QString &ncxDir,
                                  const QHash<QString, int> &spineIndexByPath)
{
    QXmlStreamReader xml(prepareXml(bytes));
    QVector<EpubTocEntry> entries;
    bool insideNavMap = false;
    QString pendingLabel;
    while (!xml.atEnd()) {
        const QXmlStreamReader::TokenType t = xml.readNext();
        if (t == QXmlStreamReader::StartElement) {
            const QStringView n = xml.name();
            if (n == u"navMap") {
                insideNavMap = true;
            } else if (n == u"text") {
                if (insideNavMap)
                    pendingLabel = collapseWhitespace(collectText(xml));
            } else if (n == u"content") {
                if (insideNavMap) {
                    QString src;
                    if (getAttr(xml.attributes(), u"src", &src) && !isBlank(src)) {
                        const int index =
                            spineIndexFor(spineIndexByPath, resolvePathImpl(ncxDir, src));
                        if (index >= 0)
                            entries.append({pendingLabel.isEmpty()
                                                ? QStringLiteral("Chapter %1").arg(index + 1)
                                                : pendingLabel,
                                            index});
                    }
                    pendingLabel.clear();
                }
            }
        } else if (t == QXmlStreamReader::EndElement) {
            if (xml.name() == u"navMap")
                insideNavMap = false;
        }
    }
    return entries;
}

QVector<EpubTocEntry> buildToc(const std::function<QByteArray(const QString &)> &fetch,
                               const PackageDoc &pkg,
                               const QHash<QString, ManifestItem> &manifestById,
                               const QHash<QString, int> &spineIndexByPath, int spineCount)
{
    for (const ManifestItem &nav : pkg.manifest) {
        if (nav.properties.contains(QStringLiteral("nav"))) {
            const QVector<EpubTocEntry> entries =
                parseNavToc(fetch(nav.path), dirName(nav.path), spineIndexByPath);
            if (!entries.isEmpty())
                return entries;
            break;  // firstOrNull { "nav" in properties }
        }
    }
    const ManifestItem *ncx = nullptr;
    if (!pkg.ncxId.isEmpty()) {
        auto it = manifestById.constFind(pkg.ncxId);
        if (it != manifestById.constEnd())
            ncx = &it.value();
    }
    if (!ncx)
        for (const ManifestItem &m : pkg.manifest)
            if (m.mediaType == kNcxMediaType) {
                ncx = &m;
                break;
            }
    if (ncx) {
        const QVector<EpubTocEntry> entries =
            parseNcxToc(fetch(ncx->path), dirName(ncx->path), spineIndexByPath);
        if (!entries.isEmpty())
            return entries;
    }
    QVector<EpubTocEntry> fallback;
    fallback.reserve(spineCount);
    for (int i = 0; i < spineCount; ++i)
        fallback.append({QStringLiteral("Chapter %1").arg(i + 1), i});
    return fallback;
}

QString findCoverPath(const PackageDoc &pkg, const QHash<QString, ManifestItem> &manifestById)
{
    for (const ManifestItem &m : pkg.manifest)
        if (m.properties.contains(QStringLiteral("cover-image")))
            return m.path;
    if (!pkg.coverMetaId.isEmpty()) {
        auto it = manifestById.constFind(pkg.coverMetaId);
        if (it != manifestById.constEnd() && it.value().mediaType.startsWith(QLatin1String("image/")))
            return it.value().path;
    }
    for (const ManifestItem &m : pkg.manifest)
        if (m.mediaType.startsWith(QLatin1String("image/")) &&
            (m.id.contains(QLatin1String("cover"), Qt::CaseInsensitive) ||
             m.href.contains(QLatin1String("cover"), Qt::CaseInsensitive)))
            return m.path;
    return QString();
}

}  // namespace

// ---- public seam ---------------------------------------------------------------------

namespace ccx::epub {

BuiltChapter buildChapterFromXhtml(const QByteArray &xhtml, int spineIndex,
                                   const std::function<QString(const QString &)> &resolveImage)
{
    bool ok = true;
    const MappedChapter mc = readBlocks(prepareXml(xhtml), resolveImage, &ok);
    // ok is informational: a well-formed doc parses fully (byte-identical); a malformed one
    // yields whatever blocks were emitted before the error (best-effort placeholder text).
    return buildChapterText(spineIndex, mc);
}

QString resolvePath(const QString &baseDir, const QString &href)
{
    return resolvePathImpl(baseDir, href);
}

}  // namespace ccx::epub

// ---- EpubDocument --------------------------------------------------------------------

QString EpubDocument::entryFilePath(const QString &normPath) const
{
    auto it = m_byName.constFind(normPath);
    if (it != m_byName.constEnd())
        return it.value();
    it = m_byLower.constFind(normPath.toLower());
    return it != m_byLower.constEnd() ? it.value() : QString();
}

QByteArray EpubDocument::entryBytes(const QString &normPath) const
{
    const QString fp = entryFilePath(normPath);
    return fp.isEmpty() ? QByteArray() : m_zip->fileData(fp);
}

qint64 EpubDocument::entrySize(const QString &normPath) const
{
    auto it = m_sizeByName.constFind(normPath);
    if (it != m_sizeByName.constEnd())
        return it.value();
    it = m_sizeByLower.constFind(normPath.toLower());
    return it != m_sizeByLower.constEnd() ? it.value() : -1;
}

EpubDocument *EpubDocument::open(const QString &path, QString *err)
{
    auto fail = [&](const QString &m) -> EpubDocument * {
        if (err)
            *err = m;
        return nullptr;
    };

    auto zip = std::make_unique<QZipReader>(path, QIODevice::ReadOnly);
    if (!zip->isReadable())
        return fail(QStringLiteral("'%1' is not a readable zip archive").arg(path));

    auto doc = std::unique_ptr<EpubDocument>(new EpubDocument());
    doc->m_zip = zip.release();
    // Normalized entry index: name + lowercase -> raw filePath / uncompressed size (first wins).
    for (const QZipReader::FileInfo &fi : doc->m_zip->fileInfoList()) {
        if (fi.isDir)
            continue;
        QString name = fi.filePath;
        name.replace('\\', '/');
        if (name.startsWith('/'))
            name.remove(0, 1);
        if (!doc->m_byName.contains(name)) {
            doc->m_byName.insert(name, fi.filePath);
            doc->m_sizeByName.insert(name, fi.size);
        }
        const QString lower = name.toLower();
        if (!doc->m_byLower.contains(lower)) {
            doc->m_byLower.insert(lower, fi.filePath);
            doc->m_sizeByLower.insert(lower, fi.size);
        }
    }

    const QByteArray containerBytes = doc->entryBytes(QStringLiteral("META-INF/container.xml"));
    if (containerBytes.isEmpty())
        return fail(QStringLiteral("missing META-INF/container.xml in '%1'").arg(path));
    const QString opfPath = parseContainer(containerBytes);
    if (opfPath.isEmpty())
        return fail(QStringLiteral("container.xml declares no OPF rootfile"));
    const QByteArray opfBytes = doc->entryBytes(opfPath);
    if (opfBytes.isEmpty())
        return fail(QStringLiteral("missing OPF '%1' in '%2'").arg(opfPath, path));
    const PackageDoc pkg = parseOpf(opfBytes, dirName(opfPath));

    QHash<QString, ManifestItem> manifestById;
    for (const ManifestItem &it : pkg.manifest)
        if (!manifestById.contains(it.id))
            manifestById.insert(it.id, it);

    // Keep every manifest-resolved itemref, INCLUDING ones whose zip entry is missing:
    // the spine index must mean the same chapter across devices. Do not compact.
    QVector<ManifestItem> spineItems;
    for (const QString &id : pkg.spineItemIds) {
        auto it = manifestById.constFind(id);
        if (it != manifestById.constEnd())
            spineItems.append(it.value());
    }
    bool anyReadable = false;
    for (const ManifestItem &it : spineItems)
        if (doc->hasEntry(it.path)) {
            anyReadable = true;
            break;
        }
    if (!anyReadable)
        return fail(QStringLiteral("'%1' has no readable spine items").arg(path));

    for (const ManifestItem &it : spineItems) {
        doc->m_spinePaths.append(it.path);
        qint64 w = 1;  // DEVIATION: QZipReader exposes only uncompressed size (no compressedSize),
        if (doc->hasEntry(it.path)) {          // so weights use uncompressed bytes. Percentage may
            const qint64 sz = doc->entrySize(it.path);  // differ slightly cross-device; position is
            w = sz > 0 ? sz : 1;                         // carried by char_offset, so this is tolerated.
        }
        doc->m_spineWeights.append(w);
    }

    for (int i = 0; i < doc->m_spinePaths.size(); ++i) {
        const QString &p = doc->m_spinePaths[i];
        if (!doc->m_spineLookup.contains(p))
            doc->m_spineLookup.insert(p, i);
        const QString lp = p.toLower();
        if (!doc->m_spineLookup.contains(lp))
            doc->m_spineLookup.insert(lp, i);
    }

    doc->m_meta = EpubMeta{pkg.title, pkg.creator, pkg.language, pkg.description};
    auto fetch = [&](const QString &p) { return doc->entryBytes(p); };
    doc->m_toc = buildToc(fetch, pkg, manifestById, doc->m_spineLookup, doc->m_spinePaths.size());
    doc->m_coverPath = findCoverPath(pkg, manifestById);
    return doc.release();
}

EpubDocument::~EpubDocument()
{
    delete m_zip;  // defined here where QZipReader is complete
}

BuiltChapter EpubDocument::chapter(int spineIndex)
{
    if (spineIndex < 0 || spineIndex >= m_spinePaths.size()) {
        BuiltChapter bc;
        bc.spineIndex = spineIndex;
        return bc;
    }
    auto cached = m_cache.constFind(spineIndex);
    if (cached != m_cache.constEnd()) {
        m_cacheOrder.removeOne(spineIndex);
        m_cacheOrder.prepend(spineIndex);
        return cached.value();
    }

    const QString path = m_spinePaths[spineIndex];
    const QString baseDir = dirName(path);
    BuiltChapter bc;
    if (!hasEntry(path)) {
        // DEVIATION: Android throws EpubParseException on a missing/unparseable spine entry
        // and the reader renders a placeholder page. Here we return an empty BuiltChapter
        // (empty text, charCount 0) — same placeholder outcome, no exception.
        bc.spineIndex = spineIndex;
    } else {
        auto resolveImage = [&](const QString &href) -> QString {
            const QString p = resolvePathImpl(baseDir, href);
            return hasEntry(p) ? p : QString();
        };
        bc = ccx::epub::buildChapterFromXhtml(entryBytes(path), spineIndex, resolveImage);
    }

    m_cache.insert(spineIndex, bc);
    m_cacheOrder.prepend(spineIndex);
    while (m_cacheOrder.size() > kChapterCacheSize) {
        m_cache.remove(m_cacheOrder.takeLast());
    }
    return bc;
}

QByteArray EpubDocument::coverImageBytes()
{
    return m_coverPath.isEmpty() ? QByteArray() : imageBytes(m_coverPath);
}

QByteArray EpubDocument::imageBytes(const QString &zipPath)
{
    return entryBytes(zipPath);  // "" on missing; QZipReader::fileData never throws
}

LinkTarget EpubDocument::resolveLink(int fromSpineIndex, const QString &href)
{
    if (fromSpineIndex < 0 || fromSpineIndex >= m_spinePaths.size())
        return {0, QString(), false};
    const int hash = href.indexOf('#');
    const QString filePart = hash >= 0 ? href.left(hash) : href;
    QString anchor;
    if (hash >= 0)
        anchor = href.mid(hash + 1);  // empty if trailing '#'; treated as "no anchor"
    int targetSpine;
    if (filePart.isEmpty()) {
        targetSpine = fromSpineIndex;
    } else {
        const QString p = resolvePathImpl(dirName(m_spinePaths[fromSpineIndex]), filePart);
        targetSpine = spineIndexFor(m_spineLookup, p);
        if (targetSpine < 0)
            return {0, QString(), false};
    }
    return {targetSpine, anchor, true};
}
