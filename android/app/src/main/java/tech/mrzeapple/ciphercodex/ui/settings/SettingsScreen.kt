package tech.mrzeapple.ciphercodex.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import tech.mrzeapple.ciphercodex.data.prefs.ReaderMargin
import tech.mrzeapple.ciphercodex.data.prefs.ReadingFontChoice
import tech.mrzeapple.ciphercodex.data.prefs.ReadingTheme
import tech.mrzeapple.ciphercodex.data.prefs.Settings
import tech.mrzeapple.ciphercodex.ui.components.CipherButton
import tech.mrzeapple.ciphercodex.ui.components.CipherCaption
import tech.mrzeapple.ciphercodex.ui.components.CipherHeader
import tech.mrzeapple.ciphercodex.ui.components.CipherPanel
import tech.mrzeapple.ciphercodex.ui.components.CipherShapeSmall
import tech.mrzeapple.ciphercodex.ui.components.CipherTextField
import tech.mrzeapple.ciphercodex.ui.theme.LocalCipherColors
import tech.mrzeapple.ciphercodex.ui.theme.ReadingBlackBackground
import tech.mrzeapple.ciphercodex.ui.theme.ReadingBlackText
import tech.mrzeapple.ciphercodex.ui.theme.ReadingContrastBackground
import tech.mrzeapple.ciphercodex.ui.theme.ReadingContrastText
import tech.mrzeapple.ciphercodex.ui.theme.ReadingNightBackground
import tech.mrzeapple.ciphercodex.ui.theme.ReadingNightText
import tech.mrzeapple.ciphercodex.ui.theme.ReadingPaperBackground
import tech.mrzeapple.ciphercodex.ui.theme.ReadingPaperText
import tech.mrzeapple.ciphercodex.ui.theme.ReadingSepiaBackground
import tech.mrzeapple.ciphercodex.ui.theme.ReadingSepiaText
import kotlin.math.roundToInt

private const val FONT_STEP = 0.125f
private const val FONT_MIN = 0.75f
private const val FONT_MAX = 1.75f

@Composable
fun SettingsScreen(onBack: (() -> Unit)? = null) {
    val c = LocalCipherColors.current
    val vm: SettingsViewModel = viewModel()
    val settings by vm.settings.collectAsState()
    val connection by vm.connection.collectAsState()
    val serverUrl by vm.serverUrl.collectAsState()
    val username by vm.username.collectAsState()
    val password by vm.password.collectAsState()
    val deviceName by vm.deviceName.collectAsState()
    val syncStatus by vm.syncStatus.collectAsState()
    val webdavUrl by vm.webdavUrl.collectAsState()
    val webdavUser by vm.webdavUser.collectAsState()
    val webdavPass by vm.webdavPass.collectAsState()
    val webdavConnection by vm.webdavConnection.collectAsState()
    val webdavSyncStatus by vm.webdavSyncStatus.collectAsState()
    val webdavRunning by vm.webdavRunning.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (onBack != null) 4.dp else 16.dp, top = 8.dp, end = 16.dp),
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = c.cyan,
                    )
                }
            }
            CipherHeader(title = "SYSTEM", modifier = Modifier.weight(1f))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SyncPanel(
                settings = settings,
                serverUrl = serverUrl,
                username = username,
                password = password,
                deviceName = deviceName,
                connection = connection,
                onSyncEnabled = vm::setSyncEnabled,
                onServerUrl = vm::setServerUrl,
                onUsername = vm::setUsername,
                onPassword = vm::setPassword,
                onDeviceName = vm::setDeviceName,
                onLogin = { vm.testConnection(register = false) },
                onRegister = { vm.testConnection(register = true) },
                syncStatus = syncStatus,
                onSyncNow = vm::syncNow,
            )
            WebDavPanel(
                settings = settings,
                url = webdavUrl,
                user = webdavUser,
                pass = webdavPass,
                connection = webdavConnection,
                running = webdavRunning,
                syncStatus = webdavSyncStatus,
                onUrl = vm::setWebdavUrl,
                onUser = vm::setWebdavUser,
                onPass = vm::setWebdavPass,
                onTest = vm::testWebdavConnection,
                onSyncNow = vm::webdavSyncNow,
            )
            ReadingPanel(
                settings = settings,
                onEink = vm::setEinkMode,
                onTheme = vm::setReadingTheme,
                onAdjustFontScale = vm::adjustFontScale,
                onAdjustLineSpacing = vm::adjustLineSpacing,
                onReaderMargin = vm::setReaderMargin,
                onJustify = vm::setJustify,
                onReadingFont = vm::setReadingFont,
                onBrightnessOverride = vm::setBrightnessOverride,
                onBrightness = vm::setBrightness,
                onWarmth = vm::setWarmth,
                onKeepScreenOn = vm::setKeepScreenOn,
                onVolumeKeyTurn = vm::setVolumeKeyTurn,
                onDailyGoal = vm::setDailyGoalMinutes,
            )
            AboutPanel(deviceId = settings.deviceId)
        }
    }
}

@Composable
private fun SyncPanel(
    settings: Settings,
    serverUrl: String,
    username: String,
    password: String,
    deviceName: String,
    connection: ConnectionState,
    onSyncEnabled: (Boolean) -> Unit,
    onServerUrl: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onDeviceName: (String) -> Unit,
    onLogin: () -> Unit,
    onRegister: () -> Unit,
    syncStatus: String?,
    onSyncNow: () -> Unit,
) {
    val cipher = LocalCipherColors.current
    CipherPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "SYNC",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "REMOTE POSITION SYNC",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    CipherCaption("KOSYNC PROTOCOL")
                }
                Switch(
                    checked = settings.syncEnabled,
                    onCheckedChange = onSyncEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = cipher.cyan,
                        checkedTrackColor = cipher.static,
                        checkedBorderColor = cipher.cyan,
                        uncheckedThumbColor = cipher.muted,
                        uncheckedTrackColor = cipher.static,
                        uncheckedBorderColor = cipher.muted,
                    ),
                )
            }
            CipherTextField(value = serverUrl, onValueChange = onServerUrl, label = "SERVER URL")
            CipherTextField(value = username, onValueChange = onUsername, label = "USERNAME")
            CipherTextField(
                value = password,
                onValueChange = onPassword,
                // Write-only field: prefs hold only the md5 key, so the stored
                // password can never be shown back — the label hints a key exists.
                label = if (password.isEmpty() && settings.userKey.isNotEmpty()) "PASSWORD ********" else "PASSWORD",
                isPassword = true,
            )
            CipherTextField(value = deviceName, onValueChange = onDeviceName, label = "DEVICE NAME")
            val busy = connection is ConnectionState.Testing
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CipherButton(
                    text = "LOGIN",
                    onClick = onLogin,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
                CipherButton(
                    text = "REGISTER",
                    onClick = onRegister,
                    accent = cipher.magenta,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
            }
            val (statusText, statusColor) = when (val c = connection) {
                ConnectionState.Idle -> "STANDBY" to cipher.muted
                ConnectionState.Testing -> "TESTING..." to cipher.muted
                is ConnectionState.Ok -> c.message to cipher.cyan
                is ConnectionState.Error -> c.message.uppercase() to cipher.magenta
            }
            CipherCaption(statusText, color = statusColor)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CipherButton("SYNC NOW", onClick = onSyncNow, enabled = settings.syncEnabled)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    syncStatus?.let { CipherCaption(it, color = cipher.cyan) }
                    CipherCaption("LAST SYNC // ${formatLastSync(settings.lastSyncAt)}")
                }
            }
        }
    }
}

@Composable
private fun WebDavPanel(
    settings: Settings,
    url: String,
    user: String,
    pass: String,
    connection: ConnectionState,
    running: Boolean,
    syncStatus: String?,
    onUrl: (String) -> Unit,
    onUser: (String) -> Unit,
    onPass: (String) -> Unit,
    onTest: () -> Unit,
    onSyncNow: () -> Unit,
) {
    val cipher = LocalCipherColors.current
    CipherPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "CIPHERCODEX SYNC // WEBDAV",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            CipherCaption("BOOKS + LIBRARY DATA VIA WEBDAV")
            CipherTextField(value = url, onValueChange = onUrl, label = "SERVER URL")
            CipherTextField(value = user, onValueChange = onUser, label = "USER")
            CipherTextField(value = pass, onValueChange = onPass, label = "APP PASSWORD", isPassword = true)
            val busy = connection is ConnectionState.Testing
            CipherButton(text = "TEST", onClick = onTest, enabled = !busy, modifier = Modifier.fillMaxWidth())
            val (statusText, statusColor) = when (val c = connection) {
                ConnectionState.Idle -> "STANDBY" to cipher.muted
                ConnectionState.Testing -> "TESTING..." to cipher.muted
                is ConnectionState.Ok -> c.message to cipher.cyan
                is ConnectionState.Error -> c.message.uppercase() to cipher.magenta
            }
            CipherCaption(statusText, color = statusColor)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CipherButton("SYNC NOW", onClick = onSyncNow, enabled = !running)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    syncStatus?.let { CipherCaption(it, color = cipher.cyan) }
                    CipherCaption("LAST SYNC // ${formatLastSync(settings.webdavLastSyncAt)}")
                }
            }
        }
    }
}

/** Short relative last-sync label for the sync panel. */
private fun formatLastSync(millis: Long): String {
    if (millis <= 0L) return "NEVER"
    val diff = System.currentTimeMillis() - millis
    return when {
        diff < 60_000L -> "JUST NOW"
        diff < 3_600_000L -> "${diff / 60_000L}M AGO"
        diff < 86_400_000L -> "${diff / 3_600_000L}H AGO"
        else -> "${diff / 86_400_000L}D AGO"
    }
}

private const val LINE_STEP = 0.1f
private const val LINE_MIN = 0.8f
private const val LINE_MAX = 1.8f

@Composable
private fun ReadingPanel(
    settings: Settings,
    onEink: (Boolean) -> Unit,
    onTheme: (ReadingTheme) -> Unit,
    onAdjustFontScale: (Float) -> Unit,
    onAdjustLineSpacing: (Float) -> Unit,
    onReaderMargin: (ReaderMargin) -> Unit,
    onJustify: (Boolean) -> Unit,
    onReadingFont: (ReadingFontChoice) -> Unit,
    onBrightnessOverride: (Boolean) -> Unit,
    onBrightness: (Float) -> Unit,
    onWarmth: (Float) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onVolumeKeyTurn: (Boolean) -> Unit,
    onDailyGoal: (Int) -> Unit,
) {
    CipherPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "READING",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SwitchRow(
                title = "E-INK MODE",
                subtitle = "High-contrast ink-on-paper chrome for color e-ink",
                checked = settings.einkMode,
                onChange = onEink,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeSwatch("NIGHT", ReadingNightBackground, ReadingNightText,
                    settings.readingTheme == ReadingTheme.NIGHT, { onTheme(ReadingTheme.NIGHT) }, Modifier.weight(1f))
                ThemeSwatch("SEPIA", ReadingSepiaBackground, ReadingSepiaText,
                    settings.readingTheme == ReadingTheme.SEPIA, { onTheme(ReadingTheme.SEPIA) }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeSwatch("BLACK", ReadingBlackBackground, ReadingBlackText,
                    settings.readingTheme == ReadingTheme.BLACK, { onTheme(ReadingTheme.BLACK) }, Modifier.weight(1f))
                ThemeSwatch("PAPER", ReadingPaperBackground, ReadingPaperText,
                    settings.readingTheme == ReadingTheme.PAPER, { onTheme(ReadingTheme.PAPER) }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeSwatch("E-INK", ReadingContrastBackground, ReadingContrastText,
                    settings.readingTheme == ReadingTheme.CONTRAST, { onTheme(ReadingTheme.CONTRAST) }, Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }
            StepperRow(
                label = "SIZE",
                value = "${(settings.fontScale * 100).roundToInt()}%",
                onMinus = { onAdjustFontScale(-FONT_STEP) },
                minusEnabled = settings.fontScale > FONT_MIN,
                onPlus = { onAdjustFontScale(FONT_STEP) },
                plusEnabled = settings.fontScale < FONT_MAX,
            )
            StepperRow(
                label = "LINE SPACING",
                value = "${(settings.lineSpacing * 100).roundToInt()}%",
                onMinus = { onAdjustLineSpacing(-LINE_STEP) },
                minusEnabled = settings.lineSpacing > LINE_MIN,
                onPlus = { onAdjustLineSpacing(LINE_STEP) },
                plusEnabled = settings.lineSpacing < LINE_MAX,
            )
            LabeledChips("MARGIN") {
                ReaderMargin.entries.forEach { m ->
                    SettingChip(m.name, settings.readerMargin == m) { onReaderMargin(m) }
                }
            }
            LabeledChips("FONT") {
                ReadingFontChoice.entries.forEach { f ->
                    SettingChip(f.name, settings.readingFont == f) { onReadingFont(f) }
                }
            }
            SwitchRow("JUSTIFY TEXT", "ALIGN BOTH EDGES", settings.justify, onJustify)
            SwitchRow(
                "ADJUST BRIGHTNESS", "OVERRIDE SYSTEM WHILE READING",
                settings.brightnessOverride, onBrightnessOverride,
            )
            if (settings.brightnessOverride) {
                SliderRow("BRIGHTNESS", settings.brightness, 0.01f..1f, onBrightness)
            }
            SliderRow("WARMTH", settings.warmth, 0f..1f, onWarmth)
            SwitchRow("KEEP SCREEN ON", "STAY AWAKE WHILE READING", settings.keepScreenOn, onKeepScreenOn)
            SwitchRow("VOLUME KEY TURNS", "VOL-DOWN NEXT · VOL-UP PREVIOUS", settings.volumeKeyTurn, onVolumeKeyTurn)
            StepperRow(
                label = "DAILY GOAL",
                value = if (settings.dailyGoalMinutes > 0) "${settings.dailyGoalMinutes}M" else "OFF",
                onMinus = { onDailyGoal(settings.dailyGoalMinutes - 5) },
                minusEnabled = settings.dailyGoalMinutes > 0,
                onPlus = { onDailyGoal(settings.dailyGoalMinutes + 5) },
                plusEnabled = settings.dailyGoalMinutes < 600,
            )
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    val c = LocalCipherColors.current
    val pct = ((value - range.start) / (range.endInclusive - range.start) * 100).roundToInt()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            CipherCaption("$pct%")
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = c.cyan,
                activeTrackColor = c.cyan,
                inactiveTrackColor = c.static,
            ),
        )
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    onMinus: () -> Unit,
    minusEnabled: Boolean,
    onPlus: () -> Unit,
    plusEnabled: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        CipherButton("-", onClick = onMinus, enabled = minusEnabled)
        Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
            CipherCaption(value)
        }
        CipherButton("+", onClick = onPlus, enabled = plusEnabled)
    }
}

@Composable
private fun LabeledChips(label: String, chips: @Composable RowScope.() -> Unit) {
    Column {
        CipherCaption(label)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = chips,
        )
    }
}

@Composable
private fun SettingChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalCipherColors.current
    val color = if (selected) c.cyan else c.muted
    Box(
        modifier = Modifier
            .clip(CipherShapeSmall)
            .border(1.dp, color, CipherShapeSmall)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalCipherColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            CipherCaption(subtitle)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.cyan,
                checkedTrackColor = c.static,
                checkedBorderColor = c.cyan,
                uncheckedThumbColor = c.muted,
                uncheckedTrackColor = c.static,
                uncheckedBorderColor = c.muted,
            ),
        )
    }
}

/** Preview chip for the reading surface — deliberately painted in the reading
 *  palette (the one non-neon exception inside chrome), framed by the kit's
 *  cut-corner shape with a cyan border when selected. */
@Composable
private fun ThemeSwatch(
    label: String,
    background: Color,
    textColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalCipherColors.current
    Box(
        modifier = modifier
            .clip(CipherShapeSmall)
            .background(background)
            .border(1.dp, if (selected) c.cyan else c.muted, CipherShapeSmall)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
        )
    }
}

@Composable
private fun AboutPanel(deviceId: String) {
    val c = LocalCipherColors.current
    CipherPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "ABOUT",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "CIPHERCODEX",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                CipherCaption("v0.4.14", color = c.cyan)
            }
            CipherCaption("DEVICE ID // ${deviceId.ifEmpty { "GENERATING..." }}")
            Text(
                text = "KOSYNC COMPATIBLE // POSITION SYNC VIA KOREADER PROTOCOL",
                style = MaterialTheme.typography.labelSmall,
                color = c.muted,
            )
        }
    }
}
