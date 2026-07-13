package tech.mrzeapple.ciphercodex.sync.webdav

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Field names are the frozen phase3b contract — do not rename.
@Serializable data class SnapBook(
    val digest: String, val guid: String = "", val title: String = "",
    val author: String? = null, val format: Int = 1, // 1 = epub, 0 = pdf (matches rM2 syncstore)
    val addedAt: Long = 0, val lastOpenedAt: Long? = null,
    val deleted: Int = 0, val updatedAt: Long = 0,
)
@Serializable data class SnapProgress(
    val bookDigest: String, val spineIndex: Int = 0, val charOffset: Int = 0,
    val percentage: Float = 0f, val deleted: Int = 0, val updatedAt: Long = 0,
)
@Serializable data class SnapBookmark(
    val guid: String, val bookDigest: String, val spineIndex: Int = 0,
    val charOffset: Int = 0, val percentage: Float = 0f, val label: String = "",
    val createdAt: Long = 0, val deleted: Int = 0, val updatedAt: Long = 0,
)
@Serializable data class SnapHighlight(
    val guid: String, val bookDigest: String, val spineIndex: Int = 0,
    val startChar: Int = 0, val endChar: Int = 0, val text: String = "",
    val note: String? = null, val colorId: Int = 0,
    val createdAt: Long = 0, val deleted: Int = 0, val updatedAt: Long = 0,
)
@Serializable data class SnapCollection(
    val guid: String, val name: String = "", val createdAt: Long = 0,
    val deleted: Int = 0, val updatedAt: Long = 0,
)
@Serializable data class SnapBookCollection(
    val collectionGuid: String, val bookDigest: String,
    val deleted: Int = 0, val updatedAt: Long = 0,
)
@Serializable data class SnapSession(
    val guid: String, val bookDigest: String, val startedAt: Long = 0,
    val endedAt: Long = 0, val pagesTurned: Int = 0,
    val startPercentage: Float = 0f, val endPercentage: Float = 0f,
    val deleted: Int = 0, val updatedAt: Long = 0,
)
@Serializable data class SnapPageText(
    val pageGuid: String,
    val text: String,
    val sourceStamp: Long = -1,
    val deleted: Int = 0,
    val updatedAt: Long,
)
@Serializable data class Snapshot(
    val deviceId: String = "", val generatedAt: Long = 0,
    val books: List<SnapBook> = emptyList(),
    val progress: List<SnapProgress> = emptyList(),
    val bookmarks: List<SnapBookmark> = emptyList(),
    val highlights: List<SnapHighlight> = emptyList(),
    val collections: List<SnapCollection> = emptyList(),
    val bookCollections: List<SnapBookCollection> = emptyList(),
    val sessions: List<SnapSession> = emptyList(),
    val pageTexts: List<SnapPageText> = emptyList(),
)

object SnapshotJson {
    // ignoreUnknownKeys drops notebooks/pages/strokes (and future fields) on the floor.
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    fun decode(text: String): Snapshot = json.decodeFromString(Snapshot.serializer(), text)
    fun encode(s: Snapshot): String = json.encodeToString(Snapshot.serializer(), s)
}
