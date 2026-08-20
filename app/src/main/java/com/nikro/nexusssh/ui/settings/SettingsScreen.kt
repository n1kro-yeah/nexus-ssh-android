package com.nikro.nexusssh.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nikro.nexusssh.data.prefs.ThemeMode
import com.nikro.nexusssh.ui.components.SectionHeader

/**
 * Main settings.
 *
 * Appearance and connection defaults live here; terminal, security and backup get their own
 * screens because each has enough switches to bury the others.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenTerminalSettings: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var editingUsername by remember { mutableStateOf(false) }
    var editingPort by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Appearance")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> "System"
                                    ThemeMode.LIGHT -> "Light"
                                    ThemeMode.DARK -> "Dark"
                                },
                            )
                        },
                    )
                }
            }
            SwitchRow(
                title = "Dynamic colour",
                subtitle = "Follow the wallpaper palette on Android 12 and later",
                checked = settings.dynamicColor,
                onCheckedChange = viewModel::setDynamicColor,
            )
            SwitchRow(
                title = "True black",
                subtitle = "Pure black surfaces in dark mode, easier on OLED panels",
                checked = settings.amoledBlack,
                onCheckedChange = viewModel::setAmoledBlack,
            )

            HorizontalDivider()
            SectionHeader("Connection defaults")
            ListItem(
                headlineContent = { Text("Default username") },
                supportingContent = {
                    Text(settings.defaultUsername.ifBlank { "Not set" })
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editingUsername = true },
            )
            ListItem(
                headlineContent = { Text("Default port") },
                supportingContent = { Text(settings.defaultPort.toString()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editingPort = true },
            )
            SliderRow(
                title = "Keep-alive interval",
                value = settings.defaultKeepAliveSeconds.toFloat(),
                range = 0f..300f,
                steps = 29,
                valueLabel = if (settings.defaultKeepAliveSeconds == 0) {
                    "Off"
                } else {
                    "${settings.defaultKeepAliveSeconds} s"
                },
                onValueChange = { viewModel.setKeepAlive(it.toInt()) },
            )
            SliderRow(
                title = "Connect timeout",
                value = (settings.defaultConnectTimeoutMs / 1000).toFloat(),
                range = 1f..60f,
                steps = 58,
                valueLabel = "${settings.defaultConnectTimeoutMs / 1000} s",
                onValueChange = { viewModel.setConnectTimeout(it.toInt() * 1000) },
            )
            SwitchRow(
                title = "Reconnect automatically",
                subtitle = "Retry a dropped session while the app is open",
                checked = settings.autoReconnect,
                onCheckedChange = viewModel::setAutoReconnect,
            )
            SwitchRow(
                title = "Keep sessions alive in the background",
                subtitle = "Runs a foreground service so Android cannot freeze the connection",
                checked = settings.keepSessionsAlive,
                onCheckedChange = viewModel::setKeepSessionsAlive,
            )
            SwitchRow(
                title = "Confirm before disconnecting",
                subtitle = "Ask before closing a session with a running command",
                checked = settings.confirmBeforeDisconnect,
                onCheckedChange = viewModel::setConfirmDisconnect,
            )

            HorizontalDivider()
            SectionHeader("Files")
            SwitchRow(
                title = "Show hidden files",
                subtitle = "Include dotfiles in SFTP listings",
                checked = settings.sftpShowHidden,
                onCheckedChange = viewModel::setSftpShowHidden,
            )
            SwitchRow(
                title = "Preserve timestamps",
                subtitle = "Keep modification times on transferred files",
                checked = settings.sftpPreserveTimestamps,
                onCheckedChange = viewModel::setSftpPreserveTimestamps,
            )
            SwitchRow(
                title = "Confirm overwrite",
                subtitle = "Ask before replacing an existing file",
                checked = settings.sftpConfirmOverwrite,
                onCheckedChange = viewModel::setSftpConfirmOverwrite,
            )
            SliderRow(
                title = "Parallel transfers",
                value = settings.sftpParallelTransfers.toFloat(),
                range = 1f..6f,
                steps = 4,
                valueLabel = settings.sftpParallelTransfers.toString(),
                onValueChange = { viewModel.setSftpParallelTransfers(it.toInt()) },
            )

            HorizontalDivider()
            SectionHeader("More")
            NavigationRow(
                icon = Icons.Rounded.PlayArrow,
                title = "Terminal",
                subtitle = "Theme, font, cursor, scrollback, keys",
                onClick = onOpenTerminalSettings,
            )
            NavigationRow(
                icon = Icons.Rounded.Lock,
                title = "Security",
                subtitle = "App lock, agent, clipboard, screenshots",
                onClick = onOpenSecuritySettings,
            )
            NavigationRow(
                icon = Icons.Rounded.Share,
                title = "Backup",
                subtitle = "Export or import an encrypted archive",
                onClick = onOpenBackup,
            )
            NavigationRow(
                icon = Icons.Rounded.Info,
                title = "About",
                subtitle = "Version, licences, protocol support",
                onClick = onOpenAbout,
            )
        }
    }

    if (editingUsername) {
        var value by remember { mutableStateOf(settings.defaultUsername) }
        AlertDialog(
            onDismissRequest = { editingUsername = false },
            title = { Text("Default username") },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text("Username") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editingUsername = false
                    viewModel.setDefaultUsername(value.trim())
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingUsername = false }) { Text("Cancel") }
            },
        )
    }

    if (editingPort) {
        var value by remember { mutableStateOf(settings.defaultPort.toString()) }
        AlertDialog(
            onDismissRequest = { editingPort = false },
            title = { Text("Default port") },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.filter(Char::isDigit).take(5) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("Port") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = value.toIntOrNull() != null,
                    onClick = {
                        editingPort = false
                        value.toIntOrNull()?.let(viewModel::setDefaultPort)
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingPort = false }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
    )
}

@Composable
internal fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
internal fun NavigationRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}
