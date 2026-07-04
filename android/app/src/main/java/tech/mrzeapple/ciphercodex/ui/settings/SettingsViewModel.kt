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

    init {
        viewModelScope.launch {
            prefs.deviceId() // generate on first visit; surfaces via the settings flow
            val s = prefs.current()
            // Guarded so a keystroke racing the initial load is never clobbered.
            if (_serverUrl.value.isEmpty()) _serverUrl.value = s.serverUrl
            if (_username.value.isEmpty()) _username.value = s.username
            if (_deviceName.value.isEmpty()) _deviceName.value = s.deviceName
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

    fun setDailyGoalMinutes(value: Int) {
        viewModelScope.launch { prefs.setDailyGoalMinutes(value) }
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
            dailyGoalMinutes = 0,
            librarySort = LibrarySort.RECENT,
        )
    }
}
