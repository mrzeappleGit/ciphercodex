package tech.mrzeapple.ciphercodex.sync.webdav

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** Minimal href scan — enough for dufs/Nextcloud PROPFIND (no XML lib on purpose:
 *  the JVM parser isn't available in unit tests and the shape is trivial). */
object WebDavXml {
    private val href = Regex("<[a-zA-Z]*:?href>(.*?)</[a-zA-Z]*:?href>", RegexOption.IGNORE_CASE)

    fun childNames(propfindXml: String, requestPath: String): List<String> {
        val self = requestPath.trimEnd('/')
        return href.findAll(propfindXml)
            .map { URLDecoder.decode(it.groupValues[1].trim(), "UTF-8").trimEnd('/') }
            .filter { it.isNotEmpty() && it != self }
            .map { it.substringAfterLast('/') }
            .filter { it.isNotEmpty() }
            .toList()
    }
}

class WebDavClient(baseUrl: String, user: String, pass: String) {
    private val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    private val auth = Credentials.basic(user, pass)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun req(relPath: String) =
        Request.Builder().url(base + relPath).header("Authorization", auth)

    private fun run(r: Request): Response {
        val resp = http.newCall(r).execute()
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            throw IOException("HTTP $code for ${r.method} ${r.url.encodedPath}")
        }
        return resp
    }

    fun list(relDir: String): List<String> {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
        val resp = run(req(relDir).method("PROPFIND",
            body.toRequestBody("application/xml".toMediaType()))
            .header("Depth", "1").build())
        return resp.use { WebDavXml.childNames(it.body!!.string(), "/" + it.request.url.encodedPath.trimStart('/')) }
    }

    fun get(relPath: String): ByteArray =
        run(req(relPath).get().build()).use { it.body!!.bytes() }

    fun getToFile(relPath: String, dest: File) {
        run(req(relPath).get().build()).use { resp ->
            dest.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
        }
    }

    fun put(relPath: String, data: ByteArray) {
        run(req(relPath).put(data.toRequestBody("application/json".toMediaType())).build()).close()
    }

    fun putFile(relPath: String, src: File, contentType: String) {
        run(req(relPath).put(src.asRequestBody(contentType.toMediaType())).build()).close()
    }

    fun mkcol(relDir: String) {
        val resp = http.newCall(req(relDir).method("MKCOL", null).build()).execute()
        val ok = resp.isSuccessful || resp.code == 405 // 405 = already exists
        resp.close()
        if (!ok) throw IOException("MKCOL $relDir failed")
    }

    fun move(fromRel: String, toRel: String) {
        run(req(fromRel).method("MOVE", null)
            .header("Destination", base + toRel)
            .header("Overwrite", "T").build()).close()
    }

    fun test(): Result<Unit> = runCatching {
        run(req("").method("PROPFIND",
            "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"
                .toRequestBody("application/xml".toMediaType()))
            .header("Depth", "0").build()).close()
    }
}
