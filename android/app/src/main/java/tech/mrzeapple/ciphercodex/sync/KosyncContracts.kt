package tech.mrzeapple.ciphercodex.sync

/** Connection details for a kosync server. [userKey] is md5(password) hex. */
data class KosyncAccount(
    val serverUrl: String,
    val username: String,
    val userKey: String,
)

/** One progress record as stored by the server. [progress] is an opaque string
 *  to the server: KOReader stores an xpointer there; we store the string built
 *  by [ProgressCodec]. [percentage] is 0f..1f and is the interop field that
 *  works across reader implementations. */
data class RemoteProgress(
    val document: String,
    val progress: String,
    val percentage: Float,
    val device: String?,
    val deviceId: String?,
    /** Server-side unix timestamp (seconds) of the record, when provided. */
    val timestamp: Long?,
)

sealed interface KosyncResult<out T> {
    data class Ok<T>(val value: T) : KosyncResult<T>
    /** [httpCode] null means transport failure (no response). */
    data class Err(val httpCode: Int?, val message: String) : KosyncResult<Nothing>
}

interface KosyncApi {
    suspend fun register(account: KosyncAccount): KosyncResult<Unit>
    suspend fun authorize(account: KosyncAccount): KosyncResult<Unit>
    /** Ok(null) when the server has no record for this document. */
    suspend fun getProgress(account: KosyncAccount, document: String): KosyncResult<RemoteProgress?>
    suspend fun updateProgress(account: KosyncAccount, progress: RemoteProgress): KosyncResult<Unit>
}

/** CipherCodex's own position encoding for the opaque progress field. Both the
 *  Android app and CipherCodex OS on the X4 read and write this exact format:
 *
 *      ciphercodex:s=<spineIndex>;o=<charOffset>
 *
 *  Foreign values (e.g. KOReader xpointers) fail to decode; callers then fall
 *  back to positioning by percentage. */
object ProgressCodec {
    private val RE = Regex("""^ciphercodex:s=(\d+);o=(\d+)$""")

    fun encode(spineIndex: Int, charOffset: Int): String = "ciphercodex:s=$spineIndex;o=$charOffset"

    fun decode(progress: String): Pair<Int, Int>? =
        RE.matchEntire(progress.trim())?.let { m ->
            val s = m.groupValues[1].toIntOrNull() ?: return null
            val o = m.groupValues[2].toIntOrNull() ?: return null
            s to o
        }
}

/** Outcome of the pull done when a book is opened. */
sealed interface PullResult {
    /** Remote has a newer/different position worth jumping to. */
    data class RemoteNewer(
        val spineIndex: Int?,
        val charOffset: Int?,
        val percentage: Float,
        val fromDevice: String?,
    ) : PullResult
    data object UpToDate : PullResult
    data object NoRemote : PullResult
    data object Disabled : PullResult
    data class Failed(val message: String) : PullResult
}

sealed interface PushResult {
    data object Pushed : PushResult
    data object NothingToPush : PushResult
    data object Disabled : PushResult
    data class Failed(val message: String) : PushResult
}
