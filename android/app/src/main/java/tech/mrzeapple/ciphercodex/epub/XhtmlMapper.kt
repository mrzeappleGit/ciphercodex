package tech.mrzeapple.ciphercodex.epub

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import kotlin.math.min

/** Maps one spine XHTML document into the reader's flow [Block]s. */
internal object XhtmlMapper {

    /** @throws EpubParseException wrapping any parser failure, tagged with [entryName]. */
    fun parse(bytes: ByteArray, entryName: String): List<Block> {
        try {
            return readBlocks(newEpubXmlParser(bytes))
        } catch (e: EpubParseException) {
            throw e
        } catch (e: Exception) {
            throw EpubParseException("unparseable chapter '$entryName': ${e.message}", e)
        }
    }

    private fun readBlocks(parser: XmlPullParser): List<Block> {
        val blocks = mutableListOf<Block>()
        val acc = InlineAccumulator()
        val headingLevels = ArrayDeque<Int>()
        var skipDepth = 0

        fun flushBlock() {
            val text = acc.flush() ?: return
            val level = headingLevels.lastOrNull()
            blocks += if (level != null) Block.Heading(level, text) else Block.Paragraph(text)
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (skipDepth > 0) {
                        skipDepth++
                    } else {
                        val name = parser.name.lowercase()
                        when {
                            name in SkippedTags -> skipDepth = 1
                            name in BlockBoundaryTags -> flushBlock()
                            name.isHeadingTag() -> {
                                flushBlock()
                                headingLevels.addLast(name[1] - '0')
                            }
                            name == "hr" -> {
                                flushBlock()
                                blocks += Block.Rule
                            }
                            name == "br" -> acc.lineBreak()
                            else -> InlineStyles[name]?.let(acc::pushSpan)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (skipDepth > 0) {
                        skipDepth--
                    } else {
                        val name = parser.name.lowercase()
                        when {
                            name in BlockBoundaryTags -> flushBlock()
                            name.isHeadingTag() -> {
                                flushBlock()
                                headingLevels.removeLastOrNull()
                            }
                            else -> InlineStyles[name]?.let(acc::popSpan)
                        }
                    }
                }
                XmlPullParser.TEXT -> if (skipDepth == 0) acc.append(parser.text)
            }
            event = parser.next()
        }
        flushBlock()
        return blocks
    }
}

/** Namespace-aware pull parser over [bytes] with the common HTML named entities
 *  registered — XHTML in the wild references them without an inline DTD, and the
 *  entities must be defined after setInput (setInput resets parser state).
 *
 *  Must NOT use android.util.Xml.newPullParser(): it force-enables
 *  FEATURE_PROCESS_DOCDECL, which makes every defineEntityReplacementText call
 *  throw, so no entity would ever register. The factory parser leaves docdecl
 *  off by default. */
internal fun newEpubXmlParser(bytes: ByteArray): XmlPullParser {
    val parser = XmlPullParserFactory.newInstance().newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
    parser.setInput(ByteArrayInputStream(bytes), null)
    for ((entity, replacement) in HtmlEntities) {
        try {
            parser.defineEntityReplacementText(entity, replacement)
        } catch (e: Exception) {
            // Only the predefined XML five may legitimately be rejected (some
            // implementations forbid redefining them); anything else means
            // entity registration is broken and must not be hidden.
            if (entity !in XmlPredefinedEntities) throw e
        }
    }
    return parser
}

private val XmlPredefinedEntities = setOf("amp", "lt", "gt", "quot", "apos")

private val SkippedTags = setOf("script", "style", "head")

/** Tags whose open or close ends the current inline run and emits a block.
 *  Text sitting directly inside any of these (e.g. a bare div) becomes a paragraph. */
private val BlockBoundaryTags = setOf(
    "p", "div", "li", "blockquote", "ul", "ol", "dl", "dt", "dd",
    "section", "article", "aside", "header", "footer", "main", "nav", "address",
    "figure", "figcaption", "table", "thead", "tbody", "tfoot", "tr", "td", "th",
    "pre", "body",
)

private fun String.isHeadingTag(): Boolean = length == 2 && this[0] == 'h' && this[1] in '1'..'6'

private val BoldStyle = SpanStyle(fontWeight = FontWeight.Bold)
private val ItalicStyle = SpanStyle(fontStyle = FontStyle.Italic)
private val UnderlineStyle = SpanStyle(textDecoration = TextDecoration.Underline)
private val SubscriptStyle = SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.75.em)
private val SuperscriptStyle = SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.75.em)

private val InlineStyles = mapOf(
    "b" to BoldStyle,
    "strong" to BoldStyle,
    "i" to ItalicStyle,
    "em" to ItalicStyle,
    "u" to UnderlineStyle,
    "sub" to SubscriptStyle,
    "sup" to SuperscriptStyle,
)

/** Builds one paragraph's text with whitespace collapsed on the way in, so the
 *  span offsets recorded here are exact offsets into the emitted string. */
private class InlineAccumulator {
    private class OpenSpan(val style: SpanStyle, var start: Int)
    private class ClosedSpan(val style: SpanStyle, val start: Int, val end: Int)

    private val text = StringBuilder()
    private val openSpans = mutableListOf<OpenSpan>()
    private val closedSpans = mutableListOf<ClosedSpan>()

    fun append(raw: String) {
        for (ch in raw) {
            if (ch.isCollapsibleSpace()) {
                if (text.isNotEmpty() && text.last() != ' ' && text.last() != '\n') text.append(' ')
            } else {
                text.append(ch)
            }
        }
    }

    fun lineBreak() {
        if (text.isEmpty()) return
        if (text.last() == ' ') text.setLength(text.length - 1)
        if (text.isNotEmpty()) text.append('\n')
    }

    fun pushSpan(style: SpanStyle) {
        openSpans += OpenSpan(style, text.length)
    }

    fun popSpan(style: SpanStyle) {
        // Close the innermost matching span; ignore stray close tags.
        val index = openSpans.indexOfLast { it.style == style }
        if (index < 0) return
        val span = openSpans.removeAt(index)
        if (span.start < text.length) closedSpans += ClosedSpan(span.style, span.start, text.length)
    }

    /** Emits the trimmed accumulated text (null if blank) and resets, carrying
     *  still-open inline spans over into the next block at offset 0. */
    fun flush(): AnnotatedString? {
        var end = text.length
        while (end > 0 && (text[end - 1] == ' ' || text[end - 1] == '\n')) end--
        val result = if (end == 0) null else {
            val builder = AnnotatedString.Builder(text.substring(0, end))
            for (span in closedSpans) {
                val clampedEnd = min(span.end, end)
                if (span.start < clampedEnd) builder.addStyle(span.style, span.start, clampedEnd)
            }
            for (span in openSpans) {
                if (span.start < end) builder.addStyle(span.style, span.start, end)
            }
            builder.toAnnotatedString()
        }
        text.setLength(0)
        closedSpans.clear()
        for (span in openSpans) span.start = 0
        return result
    }
}

private fun Char.isCollapsibleSpace(): Boolean =
    this == '\u00A0' || this == '\u2007' || this == '\u202F' || isWhitespace()

/** HTML4 named entities books actually use: the XML five, spacing and dash/quote
 *  punctuation, common symbols, and the Latin-1 accented range. */
private val HtmlEntities: Map<String, String> = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to "\u00A0", "shy" to "\u00AD", "ensp" to "\u2002", "emsp" to "\u2003",
    "thinsp" to "\u2009", "zwnj" to "\u200C", "zwj" to "\u200D", "lrm" to "\u200E", "rlm" to "\u200F",
    "ndash" to "–", "mdash" to "—", "minus" to "−",
    "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚",
    "ldquo" to "“", "rdquo" to "”", "bdquo" to "„",
    "prime" to "′", "Prime" to "″",
    "lsaquo" to "‹", "rsaquo" to "›", "laquo" to "«", "raquo" to "»",
    "hellip" to "…", "bull" to "•", "dagger" to "†", "Dagger" to "‡",
    "permil" to "‰", "euro" to "€", "trade" to "™", "copy" to "©", "reg" to "®",
    "deg" to "°", "plusmn" to "±", "micro" to "µ", "para" to "¶", "middot" to "·",
    "sect" to "§", "cent" to "¢", "pound" to "£", "curren" to "¤", "yen" to "¥",
    "brvbar" to "¦", "uml" to "¨", "ordf" to "ª", "not" to "¬", "macr" to "¯",
    "acute" to "´", "cedil" to "¸", "ordm" to "º",
    "sup1" to "¹", "sup2" to "²", "sup3" to "³",
    "frac14" to "¼", "frac12" to "½", "frac34" to "¾",
    "iexcl" to "¡", "iquest" to "¿", "times" to "×", "divide" to "÷",
    "fnof" to "ƒ", "circ" to "ˆ", "tilde" to "˜", "oline" to "‾", "frasl" to "⁄",
    "OElig" to "Œ", "oelig" to "œ", "Scaron" to "Š", "scaron" to "š", "Yuml" to "Ÿ",
    "Agrave" to "À", "Aacute" to "Á", "Acirc" to "Â", "Atilde" to "Ã",
    "Auml" to "Ä", "Aring" to "Å", "AElig" to "Æ", "Ccedil" to "Ç",
    "Egrave" to "È", "Eacute" to "É", "Ecirc" to "Ê", "Euml" to "Ë",
    "Igrave" to "Ì", "Iacute" to "Í", "Icirc" to "Î", "Iuml" to "Ï",
    "ETH" to "Ð", "Ntilde" to "Ñ",
    "Ograve" to "Ò", "Oacute" to "Ó", "Ocirc" to "Ô", "Otilde" to "Õ",
    "Ouml" to "Ö", "Oslash" to "Ø",
    "Ugrave" to "Ù", "Uacute" to "Ú", "Ucirc" to "Û", "Uuml" to "Ü",
    "Yacute" to "Ý", "THORN" to "Þ", "szlig" to "ß",
    "agrave" to "à", "aacute" to "á", "acirc" to "â", "atilde" to "ã",
    "auml" to "ä", "aring" to "å", "aelig" to "æ", "ccedil" to "ç",
    "egrave" to "è", "eacute" to "é", "ecirc" to "ê", "euml" to "ë",
    "igrave" to "ì", "iacute" to "í", "icirc" to "î", "iuml" to "ï",
    "eth" to "ð", "ntilde" to "ñ",
    "ograve" to "ò", "oacute" to "ó", "ocirc" to "ô", "otilde" to "õ",
    "ouml" to "ö", "oslash" to "ø",
    "ugrave" to "ù", "uacute" to "ú", "ucirc" to "û", "uuml" to "ü",
    "yacute" to "ý", "thorn" to "þ", "yuml" to "ÿ",
)
