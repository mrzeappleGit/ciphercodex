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
import tech.mrzeapple.ciphercodex.ui.theme.CipherCyan
import tech.mrzeapple.ciphercodex.ui.theme.CipherMagenta
import tech.mrzeapple.ciphercodex.ui.theme.CipherMuted
import tech.mrzeapple.ciphercodex.ui.theme.CipherStatic
import tech.mrzeapple.ciphercodex.ui.theme.ReadingBlackBackground
import tech.mrzeapple.ciphercodex.ui.theme.ReadingBlackText
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
fun SettingsScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel()
    val settings by vm.settings.collectAsState()
    val connection by vm.connection.collectAsState()
    val serverUrl by vm.serverUrl.collectAsState()
    val username by vm.username.collectAsState()
    val password by vm.password.collectAsState()
    val deviceName by vm.deviceName.collectAsState()
    val syncStatus by vm.syncStatus.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 8.dp, end = 16.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = CipherCyan,
                )
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
            ReadingPanel(
                settings = settings,
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
                        checkedThumbColor = CipherCyan,
                        checkedTrackColor = CipherStatic,
                        checkedBorderColor = CipherCyan,
                        uncheckedThumbColor = CipherMuted,
                        uncheckedTrackColor = CipherStatic,
                        uncheckedBorderColor = CipherMuted,
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
                    accent = CipherMagenta,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
            }
            val (statusText, statusColor) = when (val c = connection) {
                ConnectionState.Idle -> "STANDBY" to CipherMuted
                ConnectionState.Testing -> "TESTING..." to CipherMuted
                is ConnectionState.Ok -> c.message to CipherCyan
                is ConnectionState.Error -> c.message.uppercase() to CipherMagenta
            }
            CipherCaption(statusText, color = statusColor)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CipherButton("SYNC NOW", onClick = onSyncNow, enabled = settings.syncEnabled)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    syncStatus?.let { CipherCaption(it, color = CipherCyan) }
                    CipherCaption("LAST SYNC // ${formatLastSync(settings.lastSyncAt)}")
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
                thumbColor = CipherCyan,
                activeTrackColor = CipherCyan,
                inactiveTrackColor = CipherStatic,
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
    val color = if (selected) CipherCyan else CipherMuted
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
                checkedThumbColor = CipherCyan,
                checkedTrackColor = CipherStatic,
                checkedBorderColor = CipherCyan,
                uncheckedThumbColor = CipherMuted,
                uncheckedTrackColor = CipherStatic,
                uncheckedBorderColor = CipherMuted,
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
    Box(
        modifier = modifier
            .clip(CipherShapeSmall)
            .background(background)
            .border(1.dp, if (selected) CipherCyan else CipherMuted, CipherShapeSmall)
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
                CipherCaption("v0.3.4", color = CipherCyan)
            }
            CipherCaption("DEVICE ID // ${deviceId.ifEmpty { "GENERATING..." }}")
            Text(
                text = "KOSYNC COMPATIBLE // POSITION SYNC VIA KOREADER PROTOCOL",
                style = MaterialTheme.typography.labelSmall,
                color = CipherMuted,
            )
        }
    }
}
