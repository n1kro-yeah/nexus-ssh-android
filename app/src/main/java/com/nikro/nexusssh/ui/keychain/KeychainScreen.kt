package com.nikro.nexusssh.ui.keychain

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nikro.nexusssh.domain.model.SshKey
import com.nikro.nexusssh.domain.model.SshKeyType
import com.nikro.nexusssh.ui.components.EmptyState
import com.nikro.nexusssh.ui.components.MetaChip

/**
 * The keychain: every private key the app holds, with the operations people actually need.
 *
 * Private key material never appears on this screen - only labels, fingerprints and the public
 * line, which is the part that is meant to be copied to a server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeychainScreen(
    onOpenKey: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: KeychainViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    var fabMenuOpen by remember { mutableStateOf(false) }
    var generateOpen by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SshKey?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }
    LaunchedEffect(state.clipboardText) {
        state.clipboardText?.let {
            clipboard.setText(AnnotatedString(it))
            viewModel.clipboardConsumed()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text("Keychain") }) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                DropdownMenu(
                    expanded = fabMenuOpen,
                    onDismissRequest = { fabMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Generate key pair") },
                        onClick = {
                            fabMenuOpen = false
                            generateOpen = true
                        },
                        leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Import existing key") },
                        onClick = {
                            fabMenuOpen = false
                            importOpen = true
                        },
                        leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                    )
                }
                ExtendedFloatingActionButton(
                    onClick = { fabMenuOpen = true },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("Add key") },
                )
            }
        },
    ) { padding ->
        if (state.keys.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Lock,
                title = "No keys yet",
                description = "Generate an Ed25519 key pair, or import one you already use. " +
                    "Keys are sealed with the device keystore and never leave the app unencrypted.",
                actionLabel = "Generate key",
                onAction = { generateOpen = true },
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(state.keys, key = { it.id }) { key ->
                    KeyRow(
                        key = key,
                        onOpen = { onOpenKey(key.id) },
                        onCopyPublic = { viewModel.copyPublicKey(key.id) },
                        onRename = { renaming = key },
                        onDelete = { viewModel.delete(key.id) },
                    )
                }
            }
        }
    }

    if (generateOpen) {
        GenerateKeyDialog(
            onDismiss = { generateOpen = false },
            onGenerate = { label, type, comment, passphrase ->
                generateOpen = false
                viewModel.generate(label, type, comment, passphrase)
            },
        )
    }

    if (importOpen) {
        ImportKeyDialog(
            onDismiss = { importOpen = false },
            onImport = { label, pem, passphrase ->
                importOpen = false
                viewModel.importKey(label, pem, passphrase)
            },
        )
    }

    renaming?.let { key ->
        var label by remember(key.id) { mutableStateOf(key.label) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename key") },
            text = {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(key.id, label)
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun KeyRow(
    key: SshKey,
    onOpen: () -> Unit,
    onCopyPublic: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = {
            Text(key.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text(
                    text = key.fingerprintSha256,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row {
                    MetaChip(key.type.displayName)
                    Spacer(Modifier.height(0.dp))
                    if (key.isPassphraseProtected) {
                        Spacer(Modifier.fillMaxWidth(0.02f))
                        MetaChip("passphrase")
                    }
                }
            }
        },
        leadingContent = { Icon(Icons.Rounded.Lock, contentDescription = null) },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Copy public key") },
                        onClick = {
                            menuOpen = false
                            onCopyPublic()
                        },
                        leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    )
}

@Composable
private fun GenerateKeyDialog(
    onDismiss: () -> Unit,
    onGenerate: (String, SshKeyType, String, String) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(SshKeyType.ED25519) }
    var typeMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generate key pair") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Box {
                    OutlinedTextField(
                        value = type.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Algorithm") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { typeMenu = true },
                    )
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        SshKeyType.entries
                            .filter { it != SshKeyType.UNKNOWN }
                            .forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.displayName) },
                                    onClick = {
                                        type = option
                                        typeMenu = false
                                    },
                                )
                            }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Comment") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank(),
                onClick = { onGenerate(label.trim(), type, comment.trim(), passphrase) },
            ) { Text("Generate") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImportKeyDialog(
    onDismiss: () -> Unit,
    onImport: (String, String, String) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var pem by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import key") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pem,
                    onValueChange = { pem = it },
                    label = { Text("Private key (OpenSSH, PEM or PuTTY)") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase (if encrypted)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank() && pem.isNotBlank(),
                onClick = { onImport(label.trim(), pem, passphrase) },
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
