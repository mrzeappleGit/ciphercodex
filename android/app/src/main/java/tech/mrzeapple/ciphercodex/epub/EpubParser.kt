package tech.mrzeapple.ciphercodex.epub

import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private const val DC_NS = "http://purl.org/dc/elements/1.1/"
private const val OPS_NS = "http://www.idpf.org/2007/ops"
private const val OPF_MEDIA_TYPE = "application/oebps-package+xml"
private const val NCX_MEDIA_TYPE = "application/x-dtbncx+xml"
private const val CHAPTER_CACHE_SIZE = 3

internal object EpubParser {

    fun open(file: File): EpubDocument {
        val zip = try {
            ZipFile(file)
        } catch (e: Exception) {
            throw EpubParseException("'${file.name}' is not a readable zip archive", e)
        }
        var handedOff = false
        try {
            val archive = ZipArchive(zip)
            val opfPath = try {
                parseContainer(archive.bytes("META-INF/container.xml"))
            } catch (e: EpubParseException) {
                throw e
            } catch (e: Exception) {
                throw EpubParseException("malformed META-INF/container.xml in '${file.name}'", e)
            }
            val pkg = try {
                parseOpf(archive.bytes(opfPath), opfPath.dirName())
            } catch (e: EpubParseException) {
                throw e
            } catch (e: Exception) {
                throw EpubParseException("malformed OPF '$opfPath' in '${file.name}'", e)
            }

            val manifestById = pkg.manifest.associateBy { it.id }
            // Keep every manifest-resolved itemref even when its zip entry is
            // missing: the X4 firmware numbers the spine the same way, and the
            // synced position 's=<index>' must mean the same chapter on both
            // devices. chapter() on a missing entry throws EpubParseException,
            // which the reader renders as a placeholder page.
            val spineItems = pkg.spineItemIds.mapNotNull { manifestById[it] }
            if (spineItems.none { archive.entry(it.path) != null }) {
                throw EpubParseException("'${file.name}' has no readable spine items")
            }

            val spinePaths = spineItems.map { it.path }
            val spineWeights = spineItems.map { item ->
                val entry = archive.entry(item.path)
                when {
                    entry == null -> 1L
                    entry.compressedSize > 0 -> entry.compressedSize
                    entry.size > 0 -> entry.size
                    else -> 1L
                }
            }
            // Keyed on both the exact resolved path and its lowercase form so
            // toc hrefs with case drift still land on their spine item.
            val spineIndexByPath = HashMap<String, Int>()
            spinePaths.forEachIndexed { index, path ->
                spineIndexByPath.putIfAbsent(path, index)
                spineIndexByPath.putIfAbsent(path.lowercase(), index)
            }

            val metadata = EpubMetadata(
                title = pkg.title,
                author = pkg.creator,
                language = pkg.language,
            )
            val document = ZipEpubDocument(
                archive = archive,
                metadata = metadata,
                spinePaths = spinePaths,
                toc = buildToc(archive, pkg, manifestById, spineIndexByPath, spinePaths.size),
                spineWeights = spineWeights,
                coverPath = findCoverPath(pkg, manifestById),
            )
            handedOff = true
            return document
        } catch (e: EpubParseException) {
            throw e
        } catch (e: Exception) {
            throw EpubParseException("failed to open '${file.name}'", e)
        } finally {
            if (!handedOff) runCatching { zip.close() }
        }
    }

    private fun parseContainer(bytes: ByteArray): String {
        val parser = newEpubXmlParser(bytes)
        var fallback: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                val fullPath = parser.getAttributeValue(null, "full-path")
                if (!fullPath.isNullOrBlank()) {
                    if (parser.getAttributeValue(null, "media-type") == OPF_MEDIA_TYPE) {
                        return resolvePath("", fullPath)
                    }
                    if (fallback == null) fallback = fullPath
                }
            }
            event = parser.next()
        }
        return fallback?.let { resolvePath("", it) }
            ?: throw EpubParseException("container.xml declares no OPF rootfile")
    }

    private fun parseOpf(bytes: ByteArray, opfDir: String): PackageDoc {
        val parser = newEpubXmlParser(bytes)
        var title: String? = null
        var creator: String? = null
        var language: String? = null
        val manifest = mutableListOf<ManifestItem>()
        val spineItemIds = mutableListOf<String>()
        var ncxId: String? = null
        var coverMetaId: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                if (parser.namespace == DC_NS) {
                    when (parser.name) {
                        "title" -> if (title == null) {
                            title = parser.collectText().collapseWhitespace().ifBlank { null }
                        }
                        "creator" -> if (creator == null) {
                            creator = parser.collectText().collapseWhitespace().ifBlank { null }
                        }
                        "language" -> if (language == null) {
                            language = parser.collectText().collapseWhitespace().ifBlank { null }
                        }
                    }
                } else {
                    when (parser.name) {
                        "item" -> {
                            val id = parser.getAttributeValue(null, "id")
                            val href = parser.getAttributeValue(null, "href")
                            if (!id.isNullOrBlank() && !href.isNullOrBlank()) {
                                manifest += ManifestItem(
                                    id = id,
                                    href = href,
                                    path = resolvePath(opfDir, href),
                                    mediaType = parser.getAttributeValue(null, "media-type").orEmpty(),
                                    properties = parser.getAttributeValue(null, "properties")
                                        ?.split(' ', '\t', '\n', '\r')
                                        ?.filterTo(mutableSetOf()) { it.isNotBlank() }
                                        ?: emptySet(),
                                )
                            }
                        }
                        "itemref" -> parser.getAttributeValue(null, "idref")
                            ?.takeIf { it.isNotBlank() }
                            ?.let(spineItemIds::add)
                        "spine" -> ncxId = parser.getAttributeValue(null, "toc")
                        "meta" -> if (parser.getAttributeValue(null, "name") == "cover") {
                            coverMetaId = parser.getAttributeValue(null, "content")?.trim()
                        }
                    }
                }
            }
            event = parser.next()
        }
        return PackageDoc(title, creator, language, manifest, spineItemIds, ncxId, coverMetaId)
    }

    private fun buildToc(
        archive: ZipArchive,
        pkg: PackageDoc,
        manifestById: Map<String, ManifestItem>,
        spineIndexByPath: Map<String, Int>,
        spineCount: Int,
    ): List<EpubTocEntry> {
        pkg.manifest.firstOrNull { "nav" in it.properties }?.let { nav ->
            val entries = runCatching {
                parseNavToc(archive.bytes(nav.path), nav.path.dirName(), spineIndexByPath)
            }.getOrDefault(emptyList())
            if (entries.isNotEmpty()) return entries
        }
        val ncx = pkg.ncxId?.let { manifestById[it] }
            ?: pkg.manifest.firstOrNull { it.mediaType == NCX_MEDIA_TYPE }
        if (ncx != null) {
            val entries = runCatching {
                parseNcxToc(archive.bytes(ncx.path), ncx.path.dirName(), spineIndexByPath)
            }.getOrDefault(emptyList())
            if (entries.isNotEmpty()) return entries
        }
        return List(spineCount) { EpubTocEntry("Chapter ${it + 1}", it) }
    }

    private fun parseNavToc(
        bytes: ByteArray,
        navDir: String,
        spineIndexByPath: Map<String, Int>,
    ): List<EpubTocEntry> {
        val parser = newEpubXmlParser(bytes)
        val entries = mutableListOf<EpubTocEntry>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "nav") {
                val epubType = parser.getAttributeValue(OPS_NS, "type")
                val isToc = epubType?.split(' ')?.contains("toc") == true ||
                    parser.getAttributeValue(null, "role") == "doc-toc"
                if (isToc) {
                    collectNavLinks(parser, navDir, spineIndexByPath, entries)
                    break
                }
            }
            event = parser.next()
        }
        return entries
    }

    private fun collectNavLinks(
        parser: XmlPullParser,
        navDir: String,
        spineIndexByPath: Map<String, Int>,
        entries: MutableList<EpubTocEntry>,
    ) {
        val navDepth = parser.depth
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> return
                XmlPullParser.END_TAG -> if (parser.depth == navDepth) return
                XmlPullParser.START_TAG -> if (parser.name == "a") {
                    val href = parser.getAttributeValue(null, "href")
                    val title = parser.collectText().collapseWhitespace()
                    if (!href.isNullOrBlank()) {
                        val index = spineIndexFor(spineIndexByPath, resolvePath(navDir, href))
                        if (index != null) {
                            entries += EpubTocEntry(title.ifBlank { "Chapter ${index + 1}" }, index)
                        }
                    }
                }
            }
        }
    }

    private fun parseNcxToc(
        bytes: ByteArray,
        ncxDir: String,
        spineIndexByPath: Map<String, Int>,
    ): List<EpubTocEntry> {
        val parser = newEpubXmlParser(bytes)
        val entries = mutableListOf<EpubTocEntry>()
        var insideNavMap = false
        var pendingLabel: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "navMap" -> insideNavMap = true
                    "text" -> if (insideNavMap) pendingLabel = parser.collectText().collapseWhitespace()
                    "content" -> if (insideNavMap) {
                        val src = parser.getAttributeValue(null, "src")
                        if (!src.isNullOrBlank()) {
                            val index = spineIndexFor(spineIndexByPath, resolvePath(ncxDir, src))
                            if (index != null) {
                                entries += EpubTocEntry(
                                    pendingLabel.orEmpty().ifBlank { "Chapter ${index + 1}" },
                                    index,
                                )
                            }
                        }
                        pendingLabel = null
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "navMap") insideNavMap = false
            }
            event = parser.next()
        }
        return entries
    }

    private fun findCoverPath(pkg: PackageDoc, manifestById: Map<String, ManifestItem>): String? {
        pkg.manifest.firstOrNull { "cover-image" in it.properties }?.let { return it.path }
        pkg.coverMetaId
            ?.let { manifestById[it] }
            ?.takeIf { it.mediaType.startsWith("image/") }
            ?.let { return it.path }
        return pkg.manifest.firstOrNull { item ->
            item.mediaType.startsWith("image/") &&
                (item.id.contains("cover", ignoreCase = true) ||
                    item.href.contains("cover", ignoreCase = true))
        }?.path
    }

    private fun spineIndexFor(spineIndexByPath: Map<String, Int>, path: String): Int? =
        spineIndexByPath[path] ?: spineIndexByPath[path.lowercase()]
}

private class ManifestItem(
    val id: String,
    val href: String,
    /** [href] resolved against the OPF directory into a normalized zip entry path. */
    val path: String,
    val mediaType: String,
    val properties: Set<String>,
)

private class PackageDoc(
    val title: String?,
    val creator: String?,
    val language: String?,
    val manifest: List<ManifestItem>,
    val spineItemIds: List<String>,
    /** spine@toc idref (EPUB 2 NCX). */
    val ncxId: String?,
    /** meta[name=cover]@content manifest id (EPUB 2 cover convention). */
    val coverMetaId: String?,
)

/** Zip access with normalized entry names. Not thread-safe: callers synchronize. */
private class ZipArchive(private val zip: ZipFile) {
    private val byName = HashMap<String, ZipEntry>()
    private val byLowerName = HashMap<String, ZipEntry>()

    init {
        for (entry in zip.entries()) {
            if (entry.isDirectory) continue
            val name = entry.name.replace('\\', '/').removePrefix("/")
            byName.putIfAbsent(name, entry)
            byLowerName.putIfAbsent(name.lowercase(), entry)
        }
    }

    fun entry(path: String): ZipEntry? = byName[path] ?: byLowerName[path.lowercase()]

    fun bytes(path: String): ByteArray {
        val entry = entry(path) ?: throw EpubParseException("missing zip entry '$path'")
        try {
            zip.getInputStream(entry).use { return it.readBytes() }
        } catch (e: IOException) {
            throw EpubParseException("cannot read zip entry '$path'", e)
        }
    }

    fun close() = zip.close()
}

private class ZipEpubDocument(
    private val archive: ZipArchive,
    override val metadata: EpubMetadata,
    private val spinePaths: List<String>,
    override val toc: List<EpubTocEntry>,
    override val spineWeights: List<Long>,
    private val coverPath: String?,
) : EpubDocument {

    override val spineCount: Int get() = spinePaths.size

    private val lock = Any()
    private var closed = false
    private val cache = object : LinkedHashMap<Int, EpubChapter>(CHAPTER_CACHE_SIZE + 1, 1f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, EpubChapter>): Boolean =
            size > CHAPTER_CACHE_SIZE
    }

    override fun chapter(spineIndex: Int): EpubChapter {
        require(spineIndex in spinePaths.indices) {
            "spineIndex $spineIndex outside 0..${spinePaths.size - 1}"
        }
        synchronized(lock) {
            if (closed) throw EpubParseException("document is closed")
            cache[spineIndex]?.let { return it }
            val path = spinePaths[spineIndex]
            val baseDir = path.dirName()
            val chapter = EpubChapter(
                spineIndex,
                XhtmlMapper.parse(archive.bytes(path), path) { href ->
                    resolvePath(baseDir, href).takeIf { archive.entry(it) != null }
                },
            )
            cache[spineIndex] = chapter
            return chapter
        }
    }

    override fun coverImageBytes(): ByteArray? {
        if (coverPath == null) return null
        return imageBytes(coverPath)
    }

    override fun imageBytes(zipPath: String): ByteArray? {
        synchronized(lock) {
            if (closed) return null
            return try {
                archive.bytes(zipPath)
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            cache.clear()
            runCatching { archive.close() }
        }
    }
}

/** Concatenated text content of the current element. Leaves the parser on the
 *  element's own END_TAG. */
private fun XmlPullParser.collectText(): String {
    val sb = StringBuilder()
    val startDepth = depth
    while (true) {
        when (next()) {
            XmlPullParser.TEXT -> sb.append(text)
            XmlPullParser.END_TAG -> if (depth == startDepth) return sb.toString()
            XmlPullParser.END_DOCUMENT -> return sb.toString()
        }
    }
}

private val WhitespaceRun = Regex("[\\s\\u00A0]+")

private fun String.collapseWhitespace(): String = replace(WhitespaceRun, " ").trim()

private fun String.dirName(): String = substringBeforeLast('/', "")

/** Joins [href] onto [baseDir], strips fragment/query, percent-decodes, and
 *  normalizes "." / ".." segments into a zip entry path. */
private fun resolvePath(baseDir: String, href: String): String {
    val cleaned = percentDecode(href.substringBefore('#').substringBefore('?')).replace('\\', '/')
    val combined = when {
        cleaned.startsWith("/") -> cleaned.removePrefix("/")
        baseDir.isEmpty() -> cleaned
        else -> "$baseDir/$cleaned"
    }
    val segments = mutableListOf<String>()
    for (segment in combined.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
            else -> segments += segment
        }
    }
    return segments.joinToString("/")
}

private fun percentDecode(encoded: String): String {
    if ('%' !in encoded) return encoded
    val out = ByteArrayOutputStream(encoded.length)
    var literalStart = 0
    var i = 0
    fun flushLiteral(end: Int) {
        if (literalStart < end) {
            out.write(encoded.substring(literalStart, end).toByteArray(Charsets.UTF_8))
        }
    }
    while (i < encoded.length) {
        if (encoded[i] == '%' && i + 2 < encoded.length) {
            val hi = Character.digit(encoded[i + 1], 16)
            val lo = Character.digit(encoded[i + 2], 16)
            if (hi >= 0 && lo >= 0) {
                flushLiteral(i)
                out.write((hi shl 4) or lo)
                i += 3
                literalStart = i
                continue
            }
        }
        i++
    }
    flushLiteral(encoded.length)
    return String(out.toByteArray(), Charsets.UTF_8)
}
