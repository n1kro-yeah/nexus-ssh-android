package com.nikro.nexusssh.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nikro.nexusssh.BuildConfig
import com.nikro.nexusssh.ui.components.MetaChip
import com.nikro.nexusssh.ui.components.SectionHeader

private val supported = listOf(
    "SSH-2 (OpenSSH compatible)",
    "Ed25519, ECDSA P-256/384/521, RSA 2048-4096",
    "chacha20-poly1305, AES-GCM, AES-CTR",
    "curve25519-sha256, ecdh-sha2, diffie-hellman-group14/16/18",
    "Password, public key, keyboard-interactive, agent",
    "SFTP 3, local/remote/dynamic forwarding, jump hosts",
    "xterm-256color, 24-bit colour, alternate screen, mouse SGR",
)

private val libraries = listOf(
    "SSHJ" to "Apache 2.0 - SSH transport, auth, SFTP",
    "Bouncy Castle" to "MIT-style - ciphers, key formats",
    "net.i2p.crypto:eddsa" to "CC0 - Ed25519",
    "AndroidX Compose, Room, DataStore, WorkManager" to "Apache 2.0",
    "Hilt / Dagger" to "Apache 2.0 - dependency injection",
)

/** Version, protocol support and third-party notices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("About") },
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
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Nexus SSH", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "An SSH, SFTP and port-forwarding client for Android. Everything is " +
                            "stored on the device: there is no account, no sync server and no " +
                            "telemetry.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(
                        modifier = Modifier.padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        MetaChip("Material 3")
                        MetaChip("Kotlin")
                        MetaChip("Offline")
                    }
                }
            }

            SectionHeader("Protocol support")
            supported.forEach { line ->
                Text(
                    text = "\u2022  $line",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp),
                )
            }

            SectionHeader("Known limits")
            Text(
                text = "Mosh is not implemented: it needs a UDP roaming protocol and a server-side " +
                    "binary, so the setting is reserved rather than pretended. X11 forwarding " +
                    "opens the channel but needs an X server app to be useful. Agent forwarding " +
                    "is limited to key operations, not arbitrary agent extensions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            SectionHeader("Open source")
            libraries.forEach { (name, licence) ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = licence,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = "Built for the Nikro workspace.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
