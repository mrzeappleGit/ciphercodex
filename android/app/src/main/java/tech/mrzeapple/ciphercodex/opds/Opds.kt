package tech.mrzeapple.ciphercodex.opds

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** One OPDS feed entry: either a navigation link (a sub-catalog) or an
 *  acquisition entry (a downloadable book), sometimes both. */
data class OpdsEntry(
    val title: String,
    val author: String?,
    /** Absolute URL of a sub-catalog (navigation feed), if this is a folder. */
    val navHref: String?,
    /** Absolute URL of the EPUB to download, if this is a book. */
    val acquireHref: String?,
    val coverHref: String?,
) {
    val isBook: Boolean get() = acquireHref != null
    val isNavigation: Boolean get() = navHref != null && acquireHref == null
}

data class OpdsFeed(val title: String, val entries: List<OpdsEntry>)

sealed interface OpdsResult {
    data class Ok(val feed: OpdsFeed) : OpdsResult
    data class Err(val message: String) : OpdsResult
}

/** Minimal OPDS 1.x (Atom) catalog client. Parses navigation + acquisition
 *  feeds; resolves relative links against the feed URL. */
class OpdsClient {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetch(url: String): OpdsResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/atom+xml, application/xml")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext OpdsResult.Err("HTTP ${response.code}")
                val stream = response.body?.byteStream()
                    ?: return@withContext OpdsResult.Err("Empty response")
                // Resolve relative links against the FINAL url (after redirects).
                OpdsResult.Ok(parseFeed(stream, response.request.url.toString()))
            }
        } catch (e: IllegalArgumentException) {
            OpdsResult.Err("Not a valid URL")
        } catch (e: Exception) {
            OpdsResult.Err(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun parseFeed(input: InputStream, baseUrl: String): OpdsFeed {
        val parser = Xml.newPullParser()
        parser.setInput(input, null)

        var feedTitle = ""
        val entries = ArrayList<OpdsEntry>()

        var inEntry = false
        var inAuthor = false
        var entryTitle = ""
        var author: String? = null
        var nav: String? = null
        var acquire: String? = null
        var cover: String? = null

        // A malformed element (e.g. an xhtml <title> with child markup that
        // nextText can't read) returns whatever was parsed so far rather than
        // failing the whole feed.
        try {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "entry" -> {
                        inEntry = true
                        entryTitle = ""; author = null; nav = null; acquire = null; cover = null
                    }
                    "author" -> if (inEntry) inAuthor = true
                    "title" -> {
                        val text = parser.nextText().trim()
                        if (inEntry) entryTitle = text else if (feedTitle.isEmpty()) feedTitle = text
                    }
                    "name" -> if (inEntry && inAuthor) author = parser.nextText().trim()
                    "link" -> if (inEntry) {
                        val rel = parser.getAttributeValue(null, "rel").orEmpty()
                        val type = parser.getAttributeValue(null, "type").orEmpty()
                        val href = parser.getAttributeValue(null, "href")
                        if (href != null) {
                            val abs = resolve(baseUrl, href)
                            when {
                                // EPUB only — the importer reads EPUB, so a mobi/pdf
                                // acquisition link must never become the download target.
                                type.contains("epub", true) -> if (acquire == null) acquire = abs
                                rel.contains("image") -> if (cover == null) cover = abs
                                type.contains("application/atom+xml", true) -> if (nav == null) nav = abs
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "entry" -> {
                        inEntry = false
                        if (entryTitle.isNotEmpty() && (nav != null || acquire != null)) {
                            entries += OpdsEntry(entryTitle, author, nav, acquire, cover)
                        }
                    }
                    "author" -> inAuthor = false
                }
            }
            event = parser.next()
        }
        } catch (e: Exception) {
            // return the entries collected before the error
        }
        return OpdsFeed(feedTitle.ifEmpty { "CATALOG" }, entries)
    }

    private fun resolve(baseUrl: String, href: String): String =
        baseUrl.toHttpUrlOrNull()?.resolve(href)?.toString() ?: href
}
