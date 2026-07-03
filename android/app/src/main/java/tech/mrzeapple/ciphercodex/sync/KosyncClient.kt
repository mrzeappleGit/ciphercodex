package tech.mrzeapple.ciphercodex.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.floor

/** kosync protocol client. Wire format and error codes follow KOReader and the
 *  reference koreader-sync-server; shared types live in KosyncContracts. */
class KosyncClient : KosyncApi {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun register(account: KosyncAccount): KosyncResult<Unit> = withContext(Dispatchers.IO) {
        val url = url(account, "users", "create") ?: return@withContext badUrl(account)
        val body = buildJsonObject {
            put("username", account.username)
            put("password", account.userKey)
        }
        // Registration is the one call that must NOT carry auth headers.
        execute({ request(url).post(body.toBody()).build() }) { response ->
            if (response.code == 201) KosyncResult.Ok(Unit) else errorFrom(response)
        }
    }

    override suspend fun authorize(account: KosyncAccount): KosyncResult<Unit> = withContext(Dispatchers.IO) {
        val url = url(account, "users", "auth") ?: return@withContext badUrl(account)
        execute({ request(url).auth(account).get().build() }) { response ->
            if (response.code == 200) KosyncResult.Ok(Unit) else errorFrom(response)
        }
    }

    override suspend fun getProgress(account: KosyncAccount, document: String): KosyncResult<RemoteProgress?> =
        withContext(Dispatchers.IO) {
            val url = url(account, "syncs", "progress", document) ?: return@withContext badUrl(account)
            execute({ request(url).auth(account).get().build() }) { response ->
                if (response.code != 200) return@execute errorFrom(response)
                val obj = runCatching {
                    json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                }.getOrNull()
                    ?: return@execute KosyncResult.Err(response.code, "Unexpected response from server")
                // "No record" is a 200 with an empty object: detect by the absent
                // percentage field, never by status.
                val percentage = obj.number("percentage")?.toFloat()
                    ?: return@execute KosyncResult.Ok(null)
                KosyncResult.Ok(
                    RemoteProgress(
                        document = obj.string("document") ?: document,
                        progress = obj.string("progress").orEmpty(),
                        percentage = percentage,
                        device = obj.string("device"),
                        deviceId = obj.string("device_id"),
                        timestamp = (obj["timestamp"] as? JsonPrimitive)?.longOrNull,
                    )
                )
            }
        }

    override suspend fun updateProgress(account: KosyncAccount, progress: RemoteProgress): KosyncResult<Unit> =
        withContext(Dispatchers.IO) {
            val url = url(account, "syncs", "progress") ?: return@withContext badUrl(account)
            val body = buildJsonObject {
                put("document", progress.document)
                put("progress", progress.progress)
                // KOReader convention: truncate (never round up) to 4 decimals.
                put("percentage", floor(progress.percentage.toDouble() * 10000.0) / 10000.0)
                progress.device?.let { put("device", it) }
                progress.deviceId?.let { put("device_id", it) }
            }
            execute({ request(url).auth(account).put(body.toBody()).build() }) { response ->
                if (response.code == 200) KosyncResult.Ok(Unit) else errorFrom(response)
            }
        }

    /** Every kosync request needs this accept header — the reference server
     *  responds 412 without it. */
    private fun request(url: HttpUrl): Request.Builder =
        Request.Builder().url(url).header("accept", "application/vnd.koreader.v1+json")

    private fun Request.Builder.auth(account: KosyncAccount): Request.Builder = this
        .header("x-auth-user", account.username)
        // Servers compare the key verbatim; the md5 hex must be lowercase.
        .header("x-auth-key", account.userKey.lowercase())

    private fun url(account: KosyncAccount, vararg segments: String): HttpUrl? {
        // Stored without a trailing slash, but defend anyway.
        val base = account.serverUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
        return base.newBuilder().apply {
            segments.forEach { addPathSegment(it) } // percent-encodes each segment
        }.build()
    }

    private fun badUrl(account: KosyncAccount): KosyncResult.Err =
        KosyncResult.Err(null, "Invalid server URL: ${account.serverUrl}")

    /** Takes a factory, not a Request: OkHttp throws IllegalArgumentException for
     *  non-ASCII header values (e.g. auth headers from a non-ASCII username) at
     *  build time, so construction must happen inside the try to be catchable. */
    private fun <T> execute(buildRequest: () -> Request, handle: (Response) -> KosyncResult<T>): KosyncResult<T> =
        try {
            client.newCall(buildRequest()).execute().use(handle)
        } catch (e: IOException) {
            KosyncResult.Err(null, e.message ?: e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            KosyncResult.Err(null, e.message ?: "Invalid credentials or URL")
        }

    private fun errorFrom(response: Response): KosyncResult.Err {
        val obj = runCatching {
            json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
        }.getOrNull()
        val code = (obj?.get("code") as? JsonPrimitive)?.intOrNull
        val message = friendlyMessage(code)
            ?: obj?.string("message")
            ?: response.message.ifBlank { "HTTP ${response.code}" }
        return KosyncResult.Err(response.code, message)
    }

    /** Known koreader-sync-server error codes, mapped to readable text. */
    private fun friendlyMessage(code: Int?): String? = when (code) {
        2001 -> "Unauthorized"
        2002 -> "Username is already registered"
        2003 -> "Invalid request"
        2005 -> "Registration is disabled on this server"
        else -> null
    }

    private fun JsonObject.toBody(): RequestBody = toString().toRequestBody(MEDIA_JSON)

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** Lenient: the value may arrive as an int, float, or numeric string. */
    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private companion object {
        val MEDIA_JSON = "application/json".toMediaType()
    }
}
