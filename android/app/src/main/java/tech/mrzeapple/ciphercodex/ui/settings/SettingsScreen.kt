package tech.mrzeapple.ciphercodex.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import tech.mrzeapple.ciphercodex.ui.theme.ReadingNightBackground
import tech.mrzeapple.ciphercodex.ui.theme.ReadingNightText
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
            )
            ReadingPanel(
                theme = settings.readingTheme,
                fontScale = settings.fontScale,
                onTheme = vm::setReadingTheme,
                onAdjustFontScale = vm::adjustFontScale,
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
        }
    }
}

@Composable
private fun ReadingPanel(
    theme: ReadingTheme,
    fontScale: Float,
    onTheme: (ReadingTheme) -> Unit,
    onAdjustFontScale: (Float) -> Unit,
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
                ThemeSwatch(
                    label = "NIGHT",
                    background = ReadingNightBackground,
                    textColor = ReadingNightText,
                    selected = theme == ReadingTheme.NIGHT,
                    onClick = { onTheme(ReadingTheme.NIGHT) },
                    modifier = Modifier.weight(1f),
                )
                ThemeSwatch(
                    label = "SEPIA",
                    background = ReadingSepiaBackground,
                    textColor = ReadingSepiaText,
                    selected = theme == ReadingTheme.SEPIA,
                    onClick = { onTheme(ReadingTheme.SEPIA) },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CipherButton(
                    text = "A-",
                    onClick = { onAdjustFontScale(-FONT_STEP) },
                    enabled = fontScale > FONT_MIN,
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    CipherCaption("${(fontScale * 100).roundToInt()}%")
                }
                CipherButton(
                    text = "A+",
                    onClick = { onAdjustFontScale(FONT_STEP) },
                    enabled = fontScale < FONT_MAX,
                )
            }
        }
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
                CipherCaption("v0.2.1", color = CipherCyan)
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
