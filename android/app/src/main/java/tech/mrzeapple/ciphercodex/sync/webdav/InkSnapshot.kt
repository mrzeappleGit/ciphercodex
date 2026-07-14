package tech.mrzeapple.ciphercodex.sync.webdav

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** View of the ink arrays in a device snapshot, kept separate from [Snapshot] so the two
 *  decode failure domains stay separate — a malformed ink row degrades only the ink pass,
 *  never book sync. (Previously decode-only; Android now exports these too via [InkSnapshotJson.encodeMerged].) */
@Serializable data class InkNotebook(
    val guid: String, val title: String = "", val createdAt: Long = 0,
    val deleted: Int = 0, val updatedAt: Long = 0,
)
@Serializable data class InkPage(
    val guid: String, val notebookGuid: String, val seq: Int = 0,
    val deleted: Int = 0, val updatedAt: Long = 0,
)
@Serializable data class InkStroke(
    val guid: String, val pageGuid: String, val tool: Int = 0,
    val baseWidth: Float = 0f, @SerialName("points_b64") val pointsB64: String = "",
    val createdAt: Long = 0, val deleted: Int = 0, val updatedAt: Long = 0,
)
@Serializable data class InkSnapshot(
    val deviceId: String = "",
    val notebooks: List<InkNotebook> = emptyList(),
    val pages: List<InkPage> = emptyList(),
    val strokes: List<InkStroke> = emptyList(),
)

object InkSnapshotJson {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    fun decode(text: String): InkSnapshot = json.decodeFromString(InkSnapshot.serializer(), text)

    /** One wire object: the frozen Snapshot fields plus the ink arrays. Snapshot.kt
     *  stays untouched and the two decode failure domains stay separate — a malformed
     *  ink row degrades the ink pass, never book sync. */
    fun encodeMerged(base: Snapshot, ink: InkSnapshot): String {
        val b = json.encodeToJsonElement(Snapshot.serializer(), base).jsonObject
        val i = json.encodeToJsonElement(InkSnapshot.serializer(), ink).jsonObject
        return JsonObject(b + i).toString()
    }
}
