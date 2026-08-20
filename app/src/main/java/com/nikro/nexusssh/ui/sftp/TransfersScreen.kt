package com.nikro.nexusssh.ui.sftp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nikro.nexusssh.data.local.TransferDao
import com.nikro.nexusssh.data.local.TransferEntity
import com.nikro.nexusssh.ui.components.EmptyState
import com.nikro.nexusssh.ui.components.MetaChip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Transfer queue and log.
 *
 * Reads straight from the database, so it shows the same truth as the notification even if the
 * upload was started from a different screen or survived a process restart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TransfersViewModel = hiltViewModel(),
) {
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Transfers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::clearFinished) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Clear finished")
                    }
                },
            )
        },
    ) { padding ->
        if (transfers.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Refresh,
                title = "Nothing transferred yet",
                description = "Downloads and uploads started from the file browser appear here.",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(transfers, key = { it.id }) { transfer ->
                    ListItem(
                        headlineContent = { Text(transfer.fileName) },
                        supportingContent = {
                            Column {
                                Text(
                                    text = "${transfer.hostLabel} \u00b7 ${transfer.remotePath}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (transfer.state == "running" && transfer.totalBytes > 0) {
                                    LinearProgressIndicator(
                                        progress = {
                                            (
                                                transfer.transferredBytes.toFloat() /
                                                    transfer.totalBytes.toFloat()
                                                ).coerceIn(0f, 1f)
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp),
                                    )
                                }
                                Row(
                                    modifier = Modifier.padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    MetaChip(if (transfer.upload) "upload" else "download")
                                    MetaChip(transfer.state)
                                    if (transfer.totalBytes > 0) {
                                        MetaChip(formatSize(transfer.totalBytes))
                                    }
                                }
                                transfer.errorMessage?.let { message ->
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format(java.util.Locale.US, "%.1f %s", value, units[index])
}

/** Recent transfers, newest first. */
@HiltViewModel
class TransfersViewModel @Inject constructor(
    private val dao: TransferDao,
) : ViewModel() {

    val transfers: StateFlow<List<TransferEntity>> = dao.observeRecent(limit = 100)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearFinished() {
        viewModelScope.launch { dao.clearFinished() }
    }
}
