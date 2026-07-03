package tech.mrzeapple.ciphercodex.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import tech.mrzeapple.ciphercodex.epub.Block
import tech.mrzeapple.ciphercodex.epub.EpubChapter
import tech.mrzeapple.ciphercodex.ui.theme.ReadingBodyStyle

/** One rendered page: a maximal run of whole layout lines that fits the page
 *  height. [topPx] is the y of the run's first line within the full-chapter
 *  layout; rendering offsets the chapter text up by this amount inside a
 *  clipped, page-sized box so the layout reflows identically. */
data class Page(
    val startChar: Int,
    val endChar: Int,
    val topPx: Float,
    /** Pixel height of this page's own lines (last line bottom - topPx). The
     *  renderer must clip to THIS, not the page box: a page cut early by a
     *  following image still has later lines that fit the box vertically. */
    val contentHeightPx: Float,
    /** Non-null for a full-page image (covers, illustrations): render the zip
     *  entry's bitmap instead of the text run. */
    val imagePath: String? = null,
)

/** Chapter text built once from its blocks. Char offsets in [blockRanges]
 *  (and in [Page]) index into [text]. Images occupy one U+FFFC char each, in
 *  their own paragraph, listed in [images] as charIndex -> zip path. */
data class ChapterText(
    val text: AnnotatedString,
    val blockRanges: List<IntRange>,
    val images: List<Pair<Int, String>>,
)

private const val IMAGE_CHAR = '￼'

data class PaginatedChapter(
    val spineIndex: Int,
    val text: AnnotatedString,
    val blockRanges: List<IntRange>,
    val pages: List<Page>,
) {
    /** Page whose [Page.startChar, Page.endChar) contains [charOffset];
     *  offsets past the end clamp to the last page. */
    fun pageIndexFor(charOffset: Int): Int {
        pages.forEachIndexed { index, page ->
            if (charOffset >= page.startChar && charOffset < page.endChar) return index
        }
        return if (pages.isNotEmpty() && charOffset >= pages.last().startChar) pages.lastIndex else 0
    }
}

// Heading span sizes h1..h6 as multiples of the body size.
private val HeadingScales = floatArrayOf(1.6f, 1.45f, 1.3f, 1.2f, 1.1f, 1.05f)

// Em units keep the built string independent of fontScale: heading sizes and
// line heights resolve against whatever body size the measure style carries.
private val BodyLineHeightRatio = ReadingBodyStyle.lineHeight.value / ReadingBodyStyle.fontSize.value

private const val RULE_TEXT = "* * *"

/** Flattens a chapter's blocks into one AnnotatedString: indented paragraphs,
 *  bold scaled headings, centered rules, blocks separated by "\n" (kept inside
 *  the preceding paragraph's range so it never forms a stray paragraph). */
fun buildChapterText(chapter: EpubChapter): ChapterText {
    val ranges = ArrayList<IntRange>(chapter.blocks.size)
    val images = ArrayList<Pair<Int, String>>()
    val text = buildAnnotatedString {
        chapter.blocks.forEachIndexed { index, block ->
            val start = length
            val separate = index < chapter.blocks.lastIndex
            when (block) {
                is Block.Paragraph ->
                    withStyle(ParagraphStyle(textIndent = TextIndent(firstLine = 16.sp))) {
                        append(block.text)
                        if (separate) append('\n')
                    }
                is Block.Heading -> {
                    val scale = HeadingScales[(block.level - 1).coerceIn(0, HeadingScales.lastIndex)]
                    withStyle(ParagraphStyle(lineHeight = (BodyLineHeightRatio * scale).em)) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = scale.em)) {
                            append(block.text)
                        }
                        if (separate) append('\n')
                    }
                }
                Block.Rule ->
                    withStyle(ParagraphStyle(textAlign = TextAlign.Center)) {
                        append(RULE_TEXT)
                        if (separate) append('\n')
                    }
                is Block.Image -> {
                    // One object-replacement char in its own paragraph: it gets
                    // its own layout line, which paginate() turns into a
                    // standalone image page, and offsets stay page-mappable.
                    images += length to block.zipPath
                    withStyle(ParagraphStyle(textAlign = TextAlign.Center)) {
                        append(IMAGE_CHAR)
                        if (separate) append('\n')
                    }
                }
            }
            val contentEnd = if (separate) length - 1 else length
            ranges += start until contentEnd
        }
    }
    return ChapterText(text, ranges, images)
}

/** Measures the whole chapter once at [widthPx] with unconstrained height and
 *  cuts it greedily into pages of whole lines fitting [heightPx]. Every page
 *  advances at least one line, so this always terminates; a blank chapter
 *  yields a single empty page. */
fun paginate(
    chapter: EpubChapter,
    measurer: TextMeasurer,
    style: TextStyle,
    widthPx: Int,
    heightPx: Int,
): PaginatedChapter {
    val built = buildChapterText(chapter)
    val text = built.text
    if (text.isBlank()) {
        return PaginatedChapter(
            spineIndex = chapter.spineIndex,
            text = text,
            blockRanges = built.blockRanges,
            pages = listOf(Page(startChar = 0, endChar = text.length, topPx = 0f, contentHeightPx = 0f)),
        )
    }

    val safeWidth = widthPx.coerceAtLeast(1)
    val safeHeight = heightPx.coerceAtLeast(1)
    val layout = measurer.measure(
        text = text,
        style = style,
        constraints = Constraints(maxWidth = safeWidth),
    )

    // Lines holding an image's U+FFFC char become standalone image pages.
    val imageLines = HashMap<Int, String>(built.images.size)
    for ((charIndex, path) in built.images) {
        imageLines[layout.getLineForOffset(charIndex)] = path
    }

    val pages = ArrayList<Page>()
    var line = 0
    while (line < layout.lineCount) {
        val top = layout.getLineTop(line)
        val imagePath = imageLines[line]
        if (imagePath != null) {
            pages += Page(
                startChar = layout.getLineStart(line),
                endChar = layout.getLineEnd(line),
                topPx = top,
                contentHeightPx = layout.getLineBottom(line) - top,
                imagePath = imagePath,
            )
            line++
        } else {
            var last = line
            while (last + 1 < layout.lineCount &&
                last + 1 !in imageLines &&
                layout.getLineBottom(last + 1) - top <= safeHeight
            ) {
                last++
            }
            pages += Page(
                startChar = layout.getLineStart(line),
                endChar = layout.getLineEnd(last),
                topPx = top,
                contentHeightPx = layout.getLineBottom(last) - top,
            )
            line = last + 1
        }
    }
    if (pages.isEmpty()) {
        pages += Page(startChar = 0, endChar = text.length, topPx = 0f, contentHeightPx = 0f)
    }
    return PaginatedChapter(
        spineIndex = chapter.spineIndex,
        text = text,
        blockRanges = built.blockRanges,
        pages = pages,
    )
}
