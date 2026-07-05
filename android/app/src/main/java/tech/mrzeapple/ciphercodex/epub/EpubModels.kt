package tech.mrzeapple.ciphercodex.epub

import androidx.compose.ui.text.AnnotatedString
import java.io.Closeable
import java.io.File

data class EpubMetadata(
    /** dc:title, or null when the EPUB declares none; callers choose the fallback. */
    val title: String?,
    val author: String?,
    val language: String?,
    /** dc:description (the publisher blurb), or null when the EPUB declares none. */
    val description: String? = null,
)

data class EpubTocEntry(
    val title: String,
    val spineIndex: Int,
)

/** One flow unit of chapter content. Inline styling (bold/italic/etc.) lives in
 *  the AnnotatedString spans; block-level structure lives here. */
sealed interface Block {
    data class Paragraph(val text: AnnotatedString) : Block
    data class Heading(val level: Int, val text: AnnotatedString) : Block
    data object Rule : Block

    /** An image in the reading flow (cover pages, illustrations). [zipPath] is
     *  the resolved, normalized zip entry path; bytes come from
     *  [EpubDocument.imageBytes]. Rendered as its own full page. */
    data class Image(val zipPath: String) : Block
}

val Block.textLength: Int
    get() = when (this) {
        is Block.Paragraph -> text.length
        is Block.Heading -> text.length
        Block.Rule -> 1
        is Block.Image -> 1
    }

data class EpubChapter(
    val spineIndex: Int,
    val blocks: List<Block>,
    /** Anchor id -> index of the [Block] it marks, for resolving intra-chapter
     *  footnote/link targets (href "#id"). */
    val anchors: Map<String, Int> = emptyMap(),
) {
    val charCount: Int by lazy { blocks.sumOf { it.textLength } }
}

interface EpubDocument : Closeable {
    val metadata: EpubMetadata
    val spineCount: Int
    val toc: List<EpubTocEntry>

    /** Relative size weight per spine item (compressed byte size from the zip
     *  directory; 1 for spine items whose zip entry is missing). Used for
     *  whole-book percentage math without parsing every chapter up front.
     *  Always spineCount entries, each >= 1. */
    val spineWeights: List<Long>

    /** Parse and return one spine item. May be called from any thread; result
     *  should be cached by the implementation (LRU of a few chapters). */
    fun chapter(spineIndex: Int): EpubChapter

    /** Raw bytes of the cover image if the EPUB declares one. */
    fun coverImageBytes(): ByteArray?

    /** Raw bytes of an in-flow image by its resolved zip path (from
     *  [Block.Image]); null when missing or unreadable — never throws. */
    fun imageBytes(zipPath: String): ByteArray?

    /** Resolves an internal link href tapped in spine item [fromSpineIndex] to
     *  its target spine index + anchor id. Handles same-file "#id" and cross-file
     *  "path#id"; null when the target is outside the book (e.g. external URLs). */
    fun resolveLink(fromSpineIndex: Int, href: String): LinkTarget?
}

/** A resolved internal-link destination: the [spineIndex] to open and the
 *  optional [anchor] id within it. */
data class LinkTarget(val spineIndex: Int, val anchor: String?)

object Epub {
    /** Opens an EPUB: container.xml -> OPF -> spine/manifest/metadata parsed
     *  eagerly; chapter content parsed lazily via [EpubDocument.chapter].
     *  @throws EpubParseException on anything unreadable. */
    fun open(file: File): EpubDocument = EpubParser.open(file)
}

class EpubParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
