package tech.mrzeapple.ciphercodex.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.mrzeapple.ciphercodex.CipherCodexApp
import tech.mrzeapple.ciphercodex.data.prefs.LibrarySort
import tech.mrzeapple.ciphercodex.data.prefs.ReaderMargin
import tech.mrzeapple.ciphercodex.data.prefs.ReadingFontChoice
import tech.mrzeapple.ciphercodex.data.prefs.ReadingTheme
import tech.mrzeapple.ciphercodex.data.prefs.Settings
import tech.mrzeapple.ciphercodex.data.prefs.UserPrefs
import tech.mrzeapple.ciphercodex.sync.Digests
import tech.mrzeapple.ciphercodex.sync.KosyncAccount
import tech.mrzeapple.ciphercodex.sync.KosyncResult

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Testing : ConnectionState
    data class Ok(val message: String) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CipherCodexApp
    private val prefs = app.prefs

    val settings: StateFlow<Settings> = prefs.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        DEFAULTS,
    )

    // Editable text lives here rather than being derived from the settings
    // flow — typing must not fight the async DataStore round-trip (setServerUrl
    // trims on write, which would yank the cursor mid-edit).
    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    // Write-only: never seeded from prefs (only the md5 userKey is stored).
    // Blank means "keep the stored key" when testing the connection.
    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _syncStatus = MutableStateFlow<String?>(null)
    val syncStatus: StateFlow<String?> = _syncStatus.asStateFlow()

    // WebDAV sync — same editable-field pattern as the kosync fields above.
    // Unlike kosync, the raw password is what's stored (Task 6), so it's safe
    // to seed the field from prefs rather than leaving it write-only.
    private val _webdavUrl = MutableStateFlow("")
    val webdavUrl: StateFlow<String> = _webdavUrl.asStateFlow()

    private val _webdavUser = MutableStateFlow("")
    val webdavUser: StateFlow<String> = _webdavUser.asStateFlow()

    private val _webdavPass = MutableStateFlow("")
    val webdavPass: StateFlow<String> = _webdavPass.asStateFlow()

    private val _webdavConnection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val webdavConnection: StateFlow<ConnectionState> = _webdavConnection.asStateFlow()

    private val _webdavSyncStatus = MutableStateFlow<String?>(null)
    val webdavSyncStatus: StateFlow<String?> = _webdavSyncStatus.asStateFlow()

    val webdavRunning: StateFlow<Boolean> = app.webdavSync.running

    fun setWebdavUrl(value: String) {
        _webdavUrl.value = value
        viewModelScope.launch { prefs.setWebdavUrl(value) }
    }

    fun setWebdavUser(value: String) {
        _webdavUser.value = value
        viewModelScope.launch { prefs.setWebdavUser(value) }
    }

    fun setWebdavPass(value: String) {
        _webdavPass.value = value
        viewModelScope.launch { prefs.setWebdavPass(value) }
    }

    fun testWebdavConnection() {
        if (_webdavConnection.value is ConnectionState.Testing) return
        viewModelScope.launch {
            _webdavConnection.value = ConnectionState.Testing
            val result = app.webdavSync.testConnection(_webdavUrl.value, _webdavUser.value, _webdavPass.value)
            _webdavConnection.value = result.fold(
                onSuccess = { ConnectionState.Ok("LINK ESTABLISHED") },
                onFailure = { ConnectionState.Error(it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    fun webdavSyncNow() {
        if (_webdavSyncStatus.value == "SYNCING...") return
        viewModelScope.launch {
            _webdavSyncStatus.value = "SYNCING..."
            val summary = app.webdavSync.syncNow()
            _webdavSyncStatus.value = summary.error
                ?: "↑${summary.booksUp} ↓${summary.booksDown} ~${summary.entities}"
        }
    }

    fun syncNow() {
        if (_syncStatus.value == "SYNCING...") return
        viewModelScope.launch {
            _syncStatus.value = "SYNCING..."
            val summary = app.syncManager.syncAllDirty()
            _syncStatus.value = when {
                !prefs.current().syncEnabled -> "SYNC DISABLED"
                summary.attempted == 0 -> "NOTHING TO SYNC"
                summary.failed > 0 -> "PUSHED ${summary.pushed} · ${summary.failed} FAILED"
                else -> "PUSHED ${summary.pushed}"
            }
        }
    }

    init {
        viewModelScope.launch {
            prefs.deviceId() // generate on first visit; surfaces via the settings flow
            val s = prefs.current()
            // Guarded so a keystroke racing the initial load is never clobbered.
            if (_serverUrl.value.isEmpty()) _serverUrl.value = s.serverUrl
            if (_username.value.isEmpty()) _username.value = s.username
            if (_deviceName.value.isEmpty()) _deviceName.value = s.deviceName
            if (_webdavUrl.value.isEmpty()) _webdavUrl.value = s.webdavUrl
            if (_webdavUser.value.isEmpty()) _webdavUser.value = s.webdavUser
            if (_webdavPass.value.isEmpty()) _webdavPass.value = s.webdavPass
        }
    }

    fun setSyncEnabled(value: Boolean) {
        viewModelScope.launch { prefs.setSyncEnabled(value) }
    }

    fun setServerUrl(value: String) {
        _serverUrl.value = value
        viewModelScope.launch { prefs.setServerUrl(value) }
    }

    fun setUsername(value: String) {
        _username.value = value
        viewModelScope.launch { prefs.setUsername(value) }
    }

    fun setDeviceName(value: String) {
        _deviceName.value = value
        viewModelScope.launch { prefs.setDeviceName(value) }
    }

    fun setPassword(value: String) {
        _password.value = value
    }

    fun setReadingTheme(value: ReadingTheme) {
        viewModelScope.launch { prefs.setReadingTheme(value) }
    }

    fun adjustFontScale(delta: Float) {
        viewModelScope.launch { prefs.setFontScale(prefs.current().fontScale + delta) }
    }

    fun adjustLineSpacing(delta: Float) {
        viewModelScope.launch { prefs.setLineSpacing(prefs.current().lineSpacing + delta) }
    }

    fun setReaderMargin(value: ReaderMargin) {
        viewModelScope.launch { prefs.setReaderMargin(value) }
    }

    fun setJustify(value: Boolean) {
        viewModelScope.launch { prefs.setJustify(value) }
    }

    fun setReadingFont(value: ReadingFontChoice) {
        viewModelScope.launch { prefs.setReadingFont(value) }
    }

    fun setBrightnessOverride(value: Boolean) {
        viewModelScope.launch { prefs.setBrightnessOverride(value) }
    }

    fun setBrightness(value: Float) {
        viewModelScope.launch { prefs.setBrightness(value) }
    }

    fun setWarmth(value: Float) {
        viewModelScope.launch { prefs.setWarmth(value) }
    }

    fun setKeepScreenOn(value: Boolean) {
        viewModelScope.launch { prefs.setKeepScreenOn(value) }
    }

    fun setVolumeKeyTurn(value: Boolean) {
        viewModelScope.launch { prefs.setVolumeKeyTurn(value) }
    }

    fun setDailyGoalMinutes(value: Int) {
        viewModelScope.launch { prefs.setDailyGoalMinutes(value) }
    }

    fun setEinkMode(value: Boolean) {
        viewModelScope.launch { prefs.setEinkMode(value) }
    }

    fun testConnection(register: Boolean) {
        if (_connection.value is ConnectionState.Testing) return
        viewModelScope.launch {
            _connection.value = ConnectionState.Testing
            val server = _serverUrl.value.trim().trimEnd('/')
            val user = _username.value.trim()
            val key =
                if (_password.value.isEmpty()) prefs.current().userKey
                else Digests.md5Hex(_password.value)
            if (server.isEmpty() || user.isEmpty() || key.isEmpty()) {
                _connection.value = ConnectionState.Error("MISSING CREDENTIALS")
                return@launch
            }
            when (val result = app.syncManager.testConnection(KosyncAccount(server, user, key), register)) {
                is KosyncResult.Ok -> {
                    prefs.setServerUrl(server)
                    prefs.setUsername(user)
                    prefs.setDeviceName(_deviceName.value)
                    prefs.setUserKey(key)
                    _connection.value = ConnectionState.Ok("LINK ESTABLISHED")
                }
                is KosyncResult.Err -> _connection.value = ConnectionState.Error(result.message)
            }
        }
    }

    private companion object {
        // Mirrors the fallbacks in UserPrefs so the first frame matches what
        // the DataStore read will emit for a fresh install.
        val DEFAULTS = Settings(
            syncEnabled = false,
            serverUrl = UserPrefs.DEFAULT_SERVER,
            username = "",
            userKey = "",
            deviceName = "CipherCodex-Android",
            deviceId = "",
            readingTheme = ReadingTheme.NIGHT,
            fontScale = 1.0f,
            lineSpacing = 1.0f,
            readerMargin = ReaderMargin.MEDIUM,
            justify = false,
            readingFont = ReadingFontChoice.LITERATA,
            brightnessOverride = false,
            brightness = 0.5f,
            warmth = 0f,
            keepScreenOn = true,
            volumeKeyTurn = false,
            dailyGoalMinutes = 0,
            lastSyncAt = 0L,
            librarySort = LibrarySort.RECENT,
            einkMode = false,
            webdavUrl = "",
            webdavUser = "",
            webdavPass = "",
            webdavLastSyncAt = 0L,
        )
    }
}
