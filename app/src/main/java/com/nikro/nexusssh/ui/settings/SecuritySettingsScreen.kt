package com.nikro.nexusssh.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nikro.nexusssh.ui.components.SectionHeader

/**
 * Security settings.
 *
 * Everything here narrows the window in which a secret can leak: the lock, the agent confirmation
 * prompt, the clipboard timer, and the screenshot block. The wording states plainly what each one
 * does not protect against, because a security screen that overpromises is worse than none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Security") },
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
            Text(
                text = "Private keys and saved passwords are encrypted with a key held in the " +
                    "device keystore. It never leaves the device and cannot be exported, so an " +
                    "attacker with a copy of the database still has nothing usable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )

            SectionHeader("App lock")
            SwitchRow(
                title = "Require biometrics or PIN",
                subtitle = "Ask on launch and after the auto-lock delay",
                checked = settings.biometricLock,
                onCheckedChange = viewModel::setBiometricLock,
            )
            SliderRow(
                title = "Auto-lock after",
                value = settings.autoLockMinutes.toFloat(),
                range = 0f..60f,
                steps = 11,
                valueLabel = if (settings.autoLockMinutes == 0) {
                    "Immediately"
                } else {
                    "${settings.autoLockMinutes} min"
                },
                onValueChange = { viewModel.setAutoLockMinutes(it.toInt()) },
            )
            SwitchRow(
                title = "Lock when leaving the app",
                subtitle = "Ignore the delay when the app goes to the background",
                checked = settings.lockOnBackground,
                onCheckedChange = viewModel::setLockOnBackground,
            )

            HorizontalDivider()
            SectionHeader("Agent")
            SwitchRow(
                title = "Enable the key agent",
                subtitle = "Serve keys to the server for agent forwarding",
                checked = settings.agentEnabled,
                onCheckedChange = viewModel::setAgentEnabled,
            )
            SwitchRow(
                title = "Confirm every use",
                subtitle = "Ask before each signature the remote host requests",
                checked = settings.agentConfirmEachUse,
                onCheckedChange = viewModel::setAgentConfirm,
            )

            HorizontalDivider()
            SectionHeader("Leak prevention")
            SwitchRow(
                title = "Block screenshots",
                subtitle = "Also hides the app in the recents preview",
                checked = settings.hideSecretsInScreenshots,
                onCheckedChange = viewModel::setSecureWindow,
            )
            SliderRow(
                title = "Clear clipboard after",
                value = settings.clipboardClearSeconds.toFloat(),
                range = 0f..300f,
                steps = 19,
                valueLabel = if (settings.clipboardClearSeconds == 0) {
                    "Never"
                } else {
                    "${settings.clipboardClearSeconds} s"
                },
                onValueChange = { viewModel.setClipboardClearSeconds(it.toInt()) },
            )

            Text(
                text = "Host keys are pinned on first use. A changed key stops the connection and " +
                    "asks you explicitly - it is never accepted silently.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
    }
}
