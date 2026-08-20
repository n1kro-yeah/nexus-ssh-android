package com.nikro.nexusssh.ui.keychain

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nikro.nexusssh.data.repository.KeychainRepository
import com.nikro.nexusssh.domain.model.SshKey
import com.nikro.nexusssh.ui.components.ConfirmDialog
import com.nikro.nexusssh.ui.components.DetailRow
import com.nikro.nexusssh.ui.components.FingerprintBlock
import com.nikro.nexusssh.ui.components.LoadingState
import com.nikro.nexusssh.ui.components.SectionHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/**
 * Details of one key.
 *
 * The private half is never shown. Exporting produces a passphrase-protected file, so a key that
 * leaves the device is useless without the passphrase the user just typed.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun KeyDetailScreen(
    keyId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: KeyDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    var renaming by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    LaunchedEffect(keyId) { viewModel.load(keyId) }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    LaunchedEffect(state.clipboardText) {
        state.clipboardText?.let {
            clipboard.setText(AnnotatedString(it))
            viewModel.clipboardConsumed()
        }
    }
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
                title = { Text(state.key?.label ?: "Key") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { deleting = true }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete key")
                    }
                },
            )
        },
    ) { padding ->
        val key = state.key
        if (key == null) {
            LoadingState(modifier = Modifier.padding(padding), label = "Loading key")
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            ) {
                SectionHeader("Fingerprints")
                FingerprintBlock(
                    label = "SHA256",
                    fingerprint = key.fingerprintSha256,
                )
                FingerprintBlock(
                    label = "MD5",
                    fingerprint = key.fingerprintMd5,
                )

                SectionHeader("Key")
                DetailRow(label = "Type", value = key.type.displayName)
                DetailRow(
                    label = "Size",
                    value = if (key.bits > 0) "${key.bits} bits" else "n/a",
                )
                DetailRow(label = "Comment", value = key.comment.ifBlank { "none" })
                DetailRow(
                    label = "Passphrase",
                    value = if (key.isPassphraseProtected) "protected" else "none",
                )
                DetailRow(label = "Source", value = key.source)
                DetailRow(
                    label = "Added",
                    value = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(key.createdAt)),
                )
                DetailRow(label = "Used by", value = "${state.usageCount} hosts")

                SectionHeader("Public key")
                Text(
                    text = state.publicKeyLine ?: key.publicKeyLine,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(
                        onClick = viewModel::copyPublicKey,
                        label = { Text("Copy public key") },
                    )
                    AssistChip(
                        onClick = { renaming = true },
                        label = { Text("Rename") },
                    )
                    AssistChip(
                        onClick = { exporting = true },
                        label = { Text("Export encrypted") },
                    )
                }

                Text(
                    text = "Add the public key to ~/.ssh/authorized_keys on the server. The " +
                        "private key stays sealed in the device keystore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (renaming) {
        var label by remember { mutableStateOf(state.key?.label.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename key") },
            text = {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text("Name") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = label.isNotBlank(),
                    onClick = {
                        renaming = false
                        viewModel.rename(label.trim())
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    if (exporting) {
        var passphrase by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { exporting = false },
            title = { Text("Export encrypted") },
            text = {
                Column {
                    Text(
                        text = "The exported blob is encrypted with this passphrase. Without it " +
                            "the file is worthless, so pick something you will remember.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        singleLine = true,
                        visualTransformation =
                            androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        label = { Text("Passphrase") },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = passphrase.length >= 6,
                    onClick = {
                        exporting = false
                        viewModel.export(passphrase)
                    },
                ) { Text("Copy to clipboard") }
            },
            dismissButton = { TextButton(onClick = { exporting = false }) { Text("Cancel") } },
        )
    }

    if (deleting) {
        ConfirmDialog(
            title = "Delete key?",
            message = if (state.usageCount > 0) {
                "${state.usageCount} hosts use this key and will fall back to password " +
                    "authentication."
            } else {
                "The private key is destroyed and cannot be recovered."
            },
            confirmLabel = "Delete",
            onConfirm = {
                deleting = false
                viewModel.delete()
            },
            onDismiss = { deleting = false },
        )
    }
}

/** Loads one key and exposes copy, rename, export and delete. */
@HiltViewModel
class KeyDetailViewModel @Inject constructor(
    private val keychain: KeychainRepository,
) : ViewModel() {

    data class UiState(
        val key: SshKey? = null,
        val usageCount: Int = 0,
        val publicKeyLine: String? = null,
        val clipboardText: String? = null,
        val message: String? = null,
        val deleted: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var keyId: Long = 0

    fun load(id: Long) {
        if (keyId == id) return
        keyId = id
        viewModelScope.launch {
            _state.update {
                it.copy(
                    key = keychain.key(id),
                    usageCount = keychain.usageCount(id),
                    publicKeyLine = keychain.publicKeyLine(id),
                )
            }
        }
    }

    fun copyPublicKey() {
        viewModelScope.launch {
            val line = keychain.publicKeyLine(keyId)
            _state.update {
                it.copy(clipboardText = line, message = "Public key copied")
            }
        }
    }

    fun rename(label: String) {
        viewModelScope.launch {
            keychain.rename(keyId, label)
            _state.update { it.copy(key = keychain.key(keyId)) }
        }
    }

    fun export(passphrase: String) {
        viewModelScope.launch {
            val result = runCatching {
                keychain.exportPortable(keyId, passphrase.toCharArray())
            }
            result.fold(
                onSuccess = { blob ->
                    _state.update {
                        it.copy(clipboardText = blob, message = "Encrypted key copied")
                    }
                },
                onFailure = { failure ->
                    _state.update {
                        it.copy(message = failure.message ?: "Could not export this key")
                    }
                },
            )
        }
    }

    fun delete() {
        viewModelScope.launch {
            keychain.delete(keyId)
            _state.update { it.copy(deleted = true) }
        }
    }

    fun clipboardConsumed() = _state.update { it.copy(clipboardText = null) }

    fun dismissMessage() = _state.update { it.copy(message = null) }
}
