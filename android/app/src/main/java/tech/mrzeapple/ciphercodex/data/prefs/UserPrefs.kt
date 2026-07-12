package tech.mrzeapple.ciphercodex.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

enum class ReadingTheme { NIGHT, SEPIA, BLACK, PAPER, CONTRAST }

/** Library ordering. RECENT is the historical default (recently opened, then added). */
enum class LibrarySort { RECENT, TITLE, AUTHOR, ADDED, PROGRESS }

/** Horizontal page margin in dp. MEDIUM (24) is the historical default. */
enum class ReaderMargin(val dp: Int) { NARROW(14), MEDIUM(24), WIDE(40) }

/** Reading typeface. LITERATA is the bundled reading serif (historical default);
 *  the rest resolve to platform families (no bundled assets needed). */
enum class ReadingFontChoice { LITERATA, SERIF, SANS, MONO }

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
    /** Multiplier on the base line height, 0.8f..1.8f. */
    val lineSpacing: Float,
    val readerMargin: ReaderMargin,
    val justify: Boolean,
    val readingFont: ReadingFontChoice,
    /** When true the reader forces [brightness]; otherwise the system controls it. */
    val brightnessOverride: Boolean,
    /** Reader screen brightness 0.01f..1f, applied only when [brightnessOverride]. */
    val brightness: Float,
    /** Warm overlay strength 0f (off)..1f. */
    val warmth: Float,
    /** Hold the screen awake while reading. */
    val keepScreenOn: Boolean,
    /** Turn pages with the volume keys while reading (volume-down = next). */
    val volumeKeyTurn: Boolean,
    /** Daily reading target in minutes; 0 = no goal. */
    val dailyGoalMinutes: Int,
    /** Wall-clock millis of the last sync attempt; 0 = never. */
    val lastSyncAt: Long,
    val librarySort: LibrarySort,
    /** High-contrast ink-on-paper chrome for color e-ink (Boox Kaleido). */
    val einkMode: Boolean,
    /** WebDAV endpoint base URL, always trailing-slash-terminated when non-empty. */
    val webdavUrl: String,
    val webdavUser: String,
    val webdavPass: String,
    /** Wall-clock millis of the last WebDAV sync attempt; 0 = never. */
    val webdavLastSyncAt: Long,
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
        val lineSpacing = floatPreferencesKey("line_spacing")
        val readerMargin = stringPreferencesKey("reader_margin")
        val justify = booleanPreferencesKey("justify")
        val readingFont = stringPreferencesKey("reading_font")
        val brightnessOverride = booleanPreferencesKey("brightness_override")
        val brightness = floatPreferencesKey("brightness")
        val warmth = floatPreferencesKey("warmth")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val volumeKeyTurn = booleanPreferencesKey("volume_key_turn")
        val dailyGoalMinutes = intPreferencesKey("daily_goal_minutes")
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val librarySort = stringPreferencesKey("library_sort")
        val einkMode = booleanPreferencesKey("eink_mode")
        val webdavUrl = stringPreferencesKey("webdav_url")
        val webdavUser = stringPreferencesKey("webdav_user")
        val webdavPass = stringPreferencesKey("webdav_pass")
        val webdavLastSyncAt = longPreferencesKey("webdav_last_sync_at")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            syncEnabled = p[Keys.syncEnabled] ?: false,
            serverUrl = p[Keys.serverUrl] ?: DEFAULT_SERVER,
            username = p[Keys.username] ?: "",
            userKey = p[Keys.userKey] ?: "",
            deviceName = p[Keys.deviceName] ?: "CipherCodex-Android",
            deviceId = p[Keys.deviceId] ?: "",
            readingTheme = ReadingTheme.entries.firstOrNull { it.name == p[Keys.readingTheme] }
                ?: ReadingTheme.NIGHT,
            fontScale = p[Keys.fontScale] ?: 1.0f,
            lineSpacing = p[Keys.lineSpacing] ?: 1.0f,
            readerMargin = ReaderMargin.entries.firstOrNull { it.name == p[Keys.readerMargin] }
                ?: ReaderMargin.MEDIUM,
            justify = p[Keys.justify] ?: false,
            readingFont = ReadingFontChoice.entries.firstOrNull { it.name == p[Keys.readingFont] }
                ?: ReadingFontChoice.LITERATA,
            brightnessOverride = p[Keys.brightnessOverride] ?: false,
            brightness = p[Keys.brightness] ?: 0.5f,
            warmth = p[Keys.warmth] ?: 0f,
            keepScreenOn = p[Keys.keepScreenOn] ?: true,
            volumeKeyTurn = p[Keys.volumeKeyTurn] ?: false,
            dailyGoalMinutes = p[Keys.dailyGoalMinutes] ?: 0,
            lastSyncAt = p[Keys.lastSyncAt] ?: 0L,
            librarySort = LibrarySort.entries.firstOrNull { it.name == p[Keys.librarySort] }
                ?: LibrarySort.RECENT,
            einkMode = p[Keys.einkMode] ?: false,
            webdavUrl = p[Keys.webdavUrl] ?: "",
            webdavUser = p[Keys.webdavUser] ?: "",
            webdavPass = p[Keys.webdavPass] ?: "",
            webdavLastSyncAt = p[Keys.webdavLastSyncAt] ?: 0L,
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
    suspend fun setLineSpacing(value: Float) = context.dataStore.edit { it[Keys.lineSpacing] = value.coerceIn(0.8f, 1.8f) }
    suspend fun setReaderMargin(value: ReaderMargin) = context.dataStore.edit { it[Keys.readerMargin] = value.name }
    suspend fun setJustify(value: Boolean) = context.dataStore.edit { it[Keys.justify] = value }
    suspend fun setReadingFont(value: ReadingFontChoice) = context.dataStore.edit { it[Keys.readingFont] = value.name }
    suspend fun setBrightnessOverride(value: Boolean) = context.dataStore.edit { it[Keys.brightnessOverride] = value }
    suspend fun setBrightness(value: Float) = context.dataStore.edit { it[Keys.brightness] = value.coerceIn(0.01f, 1f) }
    suspend fun setWarmth(value: Float) = context.dataStore.edit { it[Keys.warmth] = value.coerceIn(0f, 1f) }
    suspend fun setKeepScreenOn(value: Boolean) = context.dataStore.edit { it[Keys.keepScreenOn] = value }
    suspend fun setVolumeKeyTurn(value: Boolean) = context.dataStore.edit { it[Keys.volumeKeyTurn] = value }
    suspend fun setDailyGoalMinutes(value: Int) = context.dataStore.edit { it[Keys.dailyGoalMinutes] = value.coerceIn(0, 600) }
    suspend fun setLastSyncAt(value: Long) = context.dataStore.edit { it[Keys.lastSyncAt] = value }
    suspend fun setLibrarySort(value: LibrarySort) = context.dataStore.edit { it[Keys.librarySort] = value.name }
    suspend fun setEinkMode(value: Boolean) = context.dataStore.edit { it[Keys.einkMode] = value }

    suspend fun setWebdavUrl(value: String) = context.dataStore.edit {
        val t = value.trim()
        it[Keys.webdavUrl] = if (t.isEmpty() || t.endsWith("/")) t else "$t/"
    }
    suspend fun setWebdavUser(value: String) = context.dataStore.edit { it[Keys.webdavUser] = value.trim() }
    suspend fun setWebdavPass(value: String) = context.dataStore.edit { it[Keys.webdavPass] = value }
    suspend fun setWebdavLastSyncAt(value: Long) = context.dataStore.edit { it[Keys.webdavLastSyncAt] = value }

    companion object {
        const val DEFAULT_SERVER = "https://sync.koreader.rocks"
    }
}
