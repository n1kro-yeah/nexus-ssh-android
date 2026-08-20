package com.nikro.nexusssh.ui.sftp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nikro.nexusssh.ssh.sftp.SftpManager
import com.nikro.nexusssh.ui.components.EmptyState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One-pane remote file browser for an active SFTP session. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpScreen(
    hostId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SftpViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var newFolderOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SftpManager.RemoteEntry?>(null) }
    var chmodTarget by remember { mutableStateOf<SftpManager.RemoteEntry?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::upload)
    }

    LaunchedEffect(hostId) { viewModel.open(hostId) }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.hostLabel.ifBlank { "Files" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        Text(
                            text = state.path.ifBlank { "\u2026" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (state.canGoUp) viewModel.goUp() else onBack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Up")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleHidden) {
                        Icon(Icons.Rounded.List, contentDescription = "Toggle hidden files")
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch("*/*") },
                icon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                text = { Text("Upload") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            state.transfer?.let { transfer ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = (if (transfer.upload) "Uploading " else "Downloading ") +
                            transfer.label + "  " + formatRate(transfer.bytesPerSecond),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(
                        progress = { transfer.fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }

            if (state.loading && state.entries.isEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            if (state.entries.isEmpty() && !state.loading) {
                EmptyState(
                    icon = Icons.Rounded.List,
                    title = "Empty folder",
                    description = "Nothing here. Upload a file, or create a folder.",
                    actionLabel = "New folder",
                    onAction = { newFolderOpen = true },
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.entries, key = { it.path }) { entry ->
                        EntryRow(
                            entry = entry,
                            onOpen = { viewModel.openEntry(entry) },
                            onDownload = { viewModel.download(entry) },
                            onRename = { renaming = entry },
                            onChmod = { chmodTarget = entry },
                            onDelete = { viewModel.delete(entry) },
                        )
                    }
                    item {
                        DropdownMenuItem(
                            text = { Text("New folder") },
                            onClick = { newFolderOpen = true },
                            leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                        )
                    }
                }
            }
        }
    }

    if (newFolderOpen) {
        TextPromptDialog(
            title = "New folder",
            label = "Name",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { newFolderOpen = false },
            onConfirm = { value -> newFolderOpen = false; viewModel.createDirectory(value) },
        )
    }

    renaming?.let { entry ->
        TextPromptDialog(
            title = "Rename",
            label = "Name",
            initial = entry.name,
            confirmLabel = "Save",
            onDismiss = { renaming = null },
            onConfirm = { value -> renaming = null; viewModel.rename(entry, value) },
        )
    }

    chmodTarget?.let { entry ->
        TextPromptDialog(
            title = "Permissions",
            label = "Octal mode",
            initial = entry.octalPermissions,
            confirmLabel = "Apply",
            onDismiss = { chmodTarget = null },
            onConfirm = { value -> chmodTarget = null; viewModel.chmod(entry, value) },
        )
    }
}

@Composable
private fun EntryRow(
    entry: SftpManager.RemoteEntry,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onChmod: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                text = buildString {
                    append(entry.permissionString)
                    append("  ")
                    if (!entry.isDirectory) {
                        append(formatBytes(entry.size))
                        append("  ")
                    }
                    append(formatTimestamp(entry.modifiedAt))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        },
        leadingContent = {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Rounded.KeyboardArrowRight else Icons.Rounded.Share,
                contentDescription = null,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (!entry.isDirectory) {
                        DropdownMenuItem(
                            text = { Text("Download") },
                            onClick = { menuOpen = false; onDownload() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { menuOpen = false; onRename() },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Permissions") },
                        onClick = { menuOpen = false; onChmod() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { menuOpen = false; onDelete() },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    )
}

@Composable
private fun TextPromptDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

private fun formatRate(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0) "" else formatBytes(bytesPerSecond) + "/s"

private fun formatTimestamp(epochSeconds: Long): String {
    if (epochSeconds <= 0) return ""
    val millis = if (epochSeconds > 100_000_000_000L) epochSeconds else epochSeconds * 1000
    return SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(millis))
}
