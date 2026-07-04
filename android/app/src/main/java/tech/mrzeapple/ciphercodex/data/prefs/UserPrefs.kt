package tech.mrzeapple.ciphercodex.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

enum class ReadingTheme { NIGHT, SEPIA }

/** Library ordering. RECENT is the historical default (recently opened, then added). */
enum class LibrarySort { RECENT, TITLE, AUTHOR, ADDED, PROGRESS }

data class Settings(
    val syncEnabled: Boolean,
    val serverUrl: String,
    val username: String,
    /** MD5 hex of the password — kosync sends the hash, never the password. */
    val userKey: String,
    val deviceName: String,
    val deviceId: String,
    val readingTheme: ReadingTheme,
    /** Multiplier on ReadingBodyStyle's base size, 0.75f..1.75f. */
    val fontScale: Float,
    /** Hold the screen awake while reading. */
    val keepScreenOn: Boolean,
    val librarySort: LibrarySort,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class UserPrefs(private val context: Context) {

    private object Keys {
        val syncEnabled = booleanPreferencesKey("sync_enabled")
        val serverUrl = stringPreferencesKey("server_url")
        val username = stringPreferencesKey("username")
        val userKey = stringPreferencesKey("user_key")
        val deviceName = stringPreferencesKey("device_name")
        val deviceId = stringPreferencesKey("device_id")
        val readingTheme = stringPreferencesKey("reading_theme")
        val fontScale = floatPreferencesKey("font_scale")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val librarySort = stringPreferencesKey("library_sort")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            syncEnabled = p[Keys.syncEnabled] ?: false,
            serverUrl = p[Keys.serverUrl] ?: DEFAULT_SERVER,
            username = p[Keys.username] ?: "",
            userKey = p[Keys.userKey] ?: "",
            deviceName = p[Keys.deviceName] ?: "CipherCodex-Android",
            deviceId = p[Keys.deviceId] ?: "",
            readingTheme = when (p[Keys.readingTheme]) {
                ReadingTheme.SEPIA.name -> ReadingTheme.SEPIA
                else -> ReadingTheme.NIGHT
            },
            fontScale = p[Keys.fontScale] ?: 1.0f,
            keepScreenOn = p[Keys.keepScreenOn] ?: true,
            librarySort = LibrarySort.entries.firstOrNull { it.name == p[Keys.librarySort] }
                ?: LibrarySort.RECENT,
        )
    }

    suspend fun current(): Settings = settings.first()

    /** Stable per-install id, created on first call. */
    suspend fun deviceId(): String {
        val existing = current().deviceId
        if (existing.isNotEmpty()) return existing
        val id = UUID.randomUUID().toString().replace("-", "")
        context.dataStore.edit { it[Keys.deviceId] = id }
        return id
    }

    suspend fun setSyncEnabled(value: Boolean) = context.dataStore.edit { it[Keys.syncEnabled] = value }
    suspend fun setServerUrl(value: String) = context.dataStore.edit { it[Keys.serverUrl] = value.trim().trimEnd('/') }
    suspend fun setUsername(value: String) = context.dataStore.edit { it[Keys.username] = value.trim() }
    suspend fun setUserKey(value: String) = context.dataStore.edit { it[Keys.userKey] = value }
    suspend fun setDeviceName(value: String) = context.dataStore.edit { it[Keys.deviceName] = value.trim() }
    suspend fun setReadingTheme(value: ReadingTheme) = context.dataStore.edit { it[Keys.readingTheme] = value.name }
    suspend fun setFontScale(value: Float) = context.dataStore.edit { it[Keys.fontScale] = value.coerceIn(0.75f, 1.75f) }
    suspend fun setKeepScreenOn(value: Boolean) = context.dataStore.edit { it[Keys.keepScreenOn] = value }
    suspend fun setLibrarySort(value: LibrarySort) = context.dataStore.edit { it[Keys.librarySort] = value.name }

    companion object {
        const val DEFAULT_SERVER = "https://sync.koreader.rocks"
    }
}
