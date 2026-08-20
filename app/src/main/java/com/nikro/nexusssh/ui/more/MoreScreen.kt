package com.nikro.nexusssh.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.nikro.nexusssh.ui.components.SectionHeader
import com.nikro.nexusssh.ui.navigation.Routes

/**
 * The catch-all tab.
 *
 * Everything that is used regularly has its own tab; this is where the rest lives, grouped so the
 * connection plumbing is not mixed with app preferences.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("More") }) },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item { SectionHeader("Connections") }
            item {
                MoreRow(
                    icon = Icons.Rounded.Send,
                    title = "Port forwarding",
                    subtitle = "Local, remote and dynamic tunnels",
                    onClick = { onNavigate(Routes.PORT_FORWARDS) },
                )
            }
            item {
                MoreRow(
                    icon = Icons.Rounded.List,
                    title = "Snippets",
                    subtitle = "Reusable commands with variables",
                    onClick = { onNavigate(Routes.SNIPPETS) },
                )
            }
            item {
                MoreRow(
                    icon = Icons.Rounded.Person,
                    title = "Identities",
                    subtitle = "Usernames, passwords and keys to reuse",
                    onClick = { onNavigate(Routes.IDENTITY_LIST) },
                )
            }
            item {
                MoreRow(
                    icon = Icons.Rounded.DateRange,
                    title = "History",
                    subtitle = "Past sessions, duration and transfer volume",
                    onClick = { onNavigate(Routes.HISTORY) },
                )
            }

            item { HorizontalDivider() }
            item { SectionHeader("Security") }
            item {
                MoreRow(
                    icon = Icons.Rounded.Lock,
                    title = "Known hosts",
                    subtitle = "Fingerprints this device trusts",
                    onClick = { onNavigate(Routes.KNOWN_HOSTS) },
                )
            }
            item {
                MoreRow(
                    icon = Icons.Rounded.Lock,
                    title = "App lock",
                    subtitle = "Biometric unlock and auto-lock timing",
                    onClick = { onNavigate(Routes.SECURITY_SETTINGS) },
                )
            }
            item {
                MoreRow(
                    icon = Icons.Rounded.Share,
                    title = "Backup and restore",
                    subtitle = "Encrypted export of hosts, keys and snippets",
                    onClick = { onNavigate(Routes.BACKUP) },
                )
            }

            item { HorizontalDivider() }
            item { SectionHeader("App") }
            item {
                MoreRow(
                    icon = Icons.Rounded.Settings,
                    title = "Settings",
                    subtitle = "Appearance, terminal and defaults",
                    onClick = { onNavigate(Routes.SETTINGS) },
                )
            }
            item {
                MoreRow(
                    icon = Icons.Rounded.Info,
                    title = "About",
                    subtitle = "Version, licences and credits",
                    onClick = { onNavigate(Routes.ABOUT) },
                )
            }
        }
    }
}

@Composable
private fun MoreRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
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
