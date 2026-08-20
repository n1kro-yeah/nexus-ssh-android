package com.nikro.nexusssh.ui.terminal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.nikro.nexusssh.ssh.SshPrompt
import com.nikro.nexusssh.ssh.SshSessionManager
import com.nikro.nexusssh.ui.components.FingerprintBlock

/**
 * Renders whatever the SSH layer is waiting for.
 *
 * The handshake is blocked while one of these is on screen, so each dialog has exactly one way
 * forward and one way out - cancelling always fails the connection rather than silently retrying.
 */
@Composable
fun SshPromptDialog(
    pending: SshSessionManager.PendingPrompt,
    modifier: Modifier = Modifier,
) {
    when (val prompt = pending.prompt) {
        is SshPrompt.Password -> SecretDialog(
            title = "Password",
            message = "Enter the password for ${prompt.username}" +
                if (prompt.attempt > 1) " (attempt ${prompt.attempt})" else "",
            label = "Password",
            onSubmit = pending::submit,
            onCancel = pending::cancel,
            modifier = modifier,
        )

        is SshPrompt.Passphrase -> SecretDialog(
            title = "Key passphrase",
            message = "Unlock ${prompt.keyLabel}" +
                if (prompt.attempt > 1) " (attempt ${prompt.attempt})" else "",
            label = "Passphrase",
            onSubmit = pending::submit,
            onCancel = pending::cancel,
            modifier = modifier,
        )

        is SshPrompt.KeyboardInteractive -> SecretDialog(
            title = prompt.name.ifBlank { "Two-factor authentication" },
            message = listOf(prompt.instruction, prompt.prompt)
                .filter { it.isNotBlank() }
                .joinToString("\n\n"),
            label = prompt.prompt.ifBlank { "Response" },
            hideInput = !prompt.echo,
            numeric = prompt.echo,
            onSubmit = pending::submit,
            onCancel = pending::cancel,
            modifier = modifier,
        )

        is SshPrompt.UnknownHostKey -> AlertDialog(
            onDismissRequest = { pending.accept(false) },
            icon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
            title = { Text("Unknown host") },
            text = {
                Column {
                    Text(
                        text = "${prompt.hostname}:${prompt.port} has not been seen before. " +
                            "Its ${prompt.keyType} key fingerprint is:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    FingerprintBlock(prompt.fingerprint, randomArt = prompt.randomArt)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Continue only if this matches the fingerprint you expect. " +
                            "It will be remembered for future connections.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { pending.accept(true) }) { Text("Trust and connect") }
            },
            dismissButton = {
                TextButton(onClick = { pending.accept(false) }) { Text("Cancel") }
            },
            modifier = modifier,
        )

        is SshPrompt.ChangedHostKey -> AlertDialog(
            onDismissRequest = { pending.accept(false) },
            icon = {
                Icon(
                    Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Host key changed") },
            text = {
                Column {
                    Text(
                        text = "The ${prompt.keyType} key for ${prompt.hostname}:${prompt.port} is not " +
                            "the one stored. This happens after a server rebuild - but it is also what " +
                            "an interception looks like.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Stored", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    FingerprintBlock(prompt.storedFingerprint)
                    Spacer(Modifier.height(12.dp))
                    Text("Presented now", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    FingerprintBlock(prompt.presentedFingerprint, randomArt = prompt.randomArt)
                }
            },
            confirmButton = {
                TextButton(onClick = { pending.accept(true) }) {
                    Text("Replace key", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pending.accept(false) }) { Text("Cancel") }
            },
            modifier = modifier,
        )

        is SshPrompt.AgentUse -> AlertDialog(
            onDismissRequest = { pending.accept(false) },
            icon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
            title = { Text("Use forwarded key?") },
            text = {
                Column {
                    Text(
                        text = "${prompt.hostname} is asking the agent to sign with:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    FingerprintBlock(prompt.keyFingerprint)
                }
            },
            confirmButton = { TextButton(onClick = { pending.accept(true) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { pending.accept(false) }) { Text("Deny") } },
            modifier = modifier,
        )
    }
}

/** Shared secret-entry dialog used for passwords, passphrases and one-time codes. */
@Composable
private fun SecretDialog(
    title: String,
    message: String,
    label: String,
    onSubmit: (String?) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    hideInput: Boolean = true,
    numeric: Boolean = false,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column {
                if (message.isNotBlank()) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = true,
                    visualTransformation = if (hideInput) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when {
                            numeric -> KeyboardType.NumberPassword
                            hideInput -> KeyboardType.Password
                            else -> KeyboardType.Text
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(value) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        modifier = modifier,
    )
}
