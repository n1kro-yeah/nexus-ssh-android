package com.nikro.nexusssh.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikro.nexusssh.data.backup.BackupManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** State and I/O boundary for backup / restore. */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupManager: BackupManager,
) : ViewModel() {

    data class UiState(
        val isWorking: Boolean = false,
        val includeSecrets: Boolean = true,
        val merge: Boolean = true,
        val password: String = "",
        val pendingExport: String? = null,
        val exportFileName: String = "nexus-ssh-backup.json",
        val message: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun setIncludeSecrets(value: Boolean) = _state.update { it.copy(includeSecrets = value) }

    fun setMerge(value: Boolean) = _state.update { it.copy(merge = value) }

    fun setPassword(value: String) = _state.update { it.copy(password = value) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun prepareExport() {
        if (_state.value.isWorking) return
        viewModelScope.launch {
            val snapshot = _state.value
            _state.update { it.copy(isWorking = true) }
            try {
                val password = snapshot.password.takeIf {
                    snapshot.includeSecrets && it.isNotEmpty()
                }?.toCharArray()
                val archive = withContext(Dispatchers.IO) { backupManager.export(password) }
                _state.update {
                    it.copy(
                        isWorking = false,
                        pendingExport = archive,
                        exportFileName = backupManager.suggestedFileName(),
                    )
                }
            } catch (error: Throwable) {
                _state.update {
                    it.copy(isWorking = false, message = error.message ?: "Could not create backup")
                }
            }
        }
    }

    fun finishExport(message: String) {
        _state.update { it.copy(pendingExport = null, message = message) }
    }

    fun importArchive(text: String) {
        if (_state.value.isWorking) return
        viewModelScope.launch {
            val snapshot = _state.value
            _state.update { it.copy(isWorking = true) }
            try {
                val password = snapshot.password.takeIf { it.isNotEmpty() }?.toCharArray()
                val summary = withContext(Dispatchers.IO) {
                    backupManager.import(text, password, snapshot.merge)
                }
                val warningText = summary.warnings.takeIf { it.isNotEmpty() }
                    ?.joinToString(separator = "\n")
                    ?.let { "\n\n$it" }
                    .orEmpty()
                _state.update {
                    it.copy(
                        isWorking = false,
                        message = "Imported ${summary.total} records.$warningText",
                    )
                }
            } catch (error: Throwable) {
                _state.update {
                    it.copy(isWorking = false, message = error.message ?: "Could not import backup")
                }
            }
        }
    }
}

/** Export and import local Nexus SSH data through Android's document picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        val content = state.pendingExport
        if (uri == null || content == null) {
            viewModel.finishExport("Export cancelled")
        } else {
            scope.launch(Dispatchers.IO) {
                val saved = runCatching {
                    val output = context.contentResolver.openOutputStream(uri, "wt")
                        ?: return@runCatching false
                    output.bufferedWriter().use { writer -> writer.write(content) }
                    true
                }.getOrDefault(false)
                viewModel.finishExport(if (saved) "Backup saved" else "Could not write the file")
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { reader -> reader.readText() }
                }.getOrNull()
                if (text.isNullOrBlank()) {
                    viewModel.finishExport("The selected file is empty or unreadable")
                } else {
                    viewModel.importArchive(text)
                }
            }
        }
    }

    LaunchedEffect(state.pendingExport) {
        if (state.pendingExport != null) {
            exportLauncher.launch(state.exportFileName)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Backup & restore") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.isWorking) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                    Text("Working…")
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Export", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Save hosts, groups, identities, snippets, tunnels and known hosts to JSON.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = state.includeSecrets,
                            onCheckedChange = viewModel::setIncludeSecrets,
                        )
                        Text(
                            text = " Include secrets",
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    if (state.includeSecrets) {
                        OutlinedTextField(
                            value = state.password,
                            onValueChange = viewModel::setPassword,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Archive password") },
                            supportingText = {
                                Text("Empty password exports structure only; private values stay out.")
                            },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    }
                    Button(
                        onClick = viewModel::prepareExport,
                        enabled = !state.isWorking,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Export JSON…")
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Import", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Choose a Nexus SSH JSON archive. Password is required only for encrypted archives.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = state.merge, onCheckedChange = viewModel::setMerge)
                        Text(" Merge with current data", modifier = Modifier.padding(start = 8.dp))
                    }
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = viewModel::setPassword,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Archive password, if encrypted") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        enabled = !state.isWorking,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Choose backup…")
                    }
                }
            }
        }
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            title = { Text("Backup") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
            },
        )
    }
}
