package tech.mrzeapple.ciphercodex.sync.webdav

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** DECODE-ONLY view of the ink arrays in a device snapshot. Deliberately separate
 *  from [Snapshot] so Android's own export can never grow ink keys. */
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
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    fun decode(text: String): InkSnapshot = json.decodeFromString(InkSnapshot.serializer(), text)
}
