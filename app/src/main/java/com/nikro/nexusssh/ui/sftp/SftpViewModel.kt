package com.nikro.nexusssh.ui.sftp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikro.nexusssh.data.prefs.SettingsRepository
import com.nikro.nexusssh.data.repository.HostRepository
import com.nikro.nexusssh.ssh.SshSessionManager
import com.nikro.nexusssh.ssh.sftp.SftpManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Remote file browsing over the session that is already open.
 *
 * Transfers run on the same connection as the terminal, which is the whole point of SFTP here: no
 * second authentication, no second host key check. Progress is reported per file because that is
 * what people watch when a transfer looks stuck.
 */
@HiltViewModel
class SftpViewModel @Inject constructor(
    private val sessions: SshSessionManager,
    private val hosts: HostRepository,
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    data class UiState(
        val sessionId: String? = null,
        val hostLabel: String = "",
        val path: String = "",
        val entries: List<SftpManager.RemoteEntry> = emptyList(),
        val loading: Boolean = false,
        val showHidden: Boolean = false,
        val transfer: TransferState? = null,
        val message: String? = null,
        val diskUsage: Pair<Long, Long>? = null,
    ) {
        val canGoUp: Boolean get() = path.isNotEmpty() && path != "/"
    }

    data class TransferState(
        val label: String,
        val fraction: Float,
        val bytesPerSecond: Long,
        val upload: Boolean,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private fun manager(): SftpManager? = _state.value.sessionId?.let(sessions::sftpManager)

    /** Attaches to a live session for the host, opening one if needed. */
    fun open(hostId: Long) {
        if (_state.value.sessionId != null && _state.value.entries.isNotEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val showHidden = settings.current().sftpShowHidden
            val existing = sessions.sessionsFor(hostId).firstOrNull { it.isAlive }
            val sessionId = existing?.id ?: run {
                val host = hosts.host(hostId)
                if (host == null) {
                    _state.update { it.copy(loading = false, message = "Host not found") }
                    return@launch
                }
                sessions.openSftpOnly(host).fold(
                    onSuccess = { it.first },
                    onFailure = { failure ->
                        _state.update {
                            it.copy(loading = false, message = failure.message ?: "Could not connect")
                        }
                        return@launch
                    },
                )
            }
            val label = sessions.entry(sessionId)?.label ?: ""
            _state.update {
                it.copy(sessionId = sessionId, hostLabel = label, showHidden = showHidden)
            }
            val home = runCatching { manager()?.home() }.getOrNull() ?: "."
            navigateTo(home)
        }
    }

    fun navigateTo(path: String) {
        viewModelScope.launch {
            val sftp = manager() ?: return@launch
            _state.update { it.copy(loading = true) }
            val result = runCatching { sftp.list(path, _state.value.showHidden) }
            val usage = runCatching { sftp.diskUsage(path) }.getOrNull()
            result.fold(
                onSuccess = { entries ->
                    _state.update {
                        it.copy(
                            path = path,
                            entries = entries.sortedWith(
                                compareByDescending<SftpManager.RemoteEntry> { entry ->
                                    entry.isDirectory
                                }.thenBy { entry -> entry.name.lowercase() },
                            ),
                            loading = false,
                            diskUsage = usage,
                        )
                    }
                },
                onFailure = { failure ->
                    _state.update {
                        it.copy(loading = false, message = failure.message ?: "Could not list $path")
                    }
                },
            )
        }
    }

    fun refresh() = navigateTo(_state.value.path)

    fun goUp() {
        val current = _state.value.path.trimEnd('/')
        if (current.isEmpty() || current == "/") return
        val parent = current.substringBeforeLast('/', "").ifEmpty { "/" }
        navigateTo(parent)
    }

    fun openEntry(entry: SftpManager.RemoteEntry) {
        if (entry.isDirectory) navigateTo(entry.path) else download(entry)
    }

    fun toggleHidden() {
        _state.update { it.copy(showHidden = !it.showHidden) }
        refresh()
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    fun createDirectory(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val sftp = manager() ?: return@launch
            runCatching { sftp.mkdir(joinPath(_state.value.path, name.trim())) }
                .onFailure { failure ->
                    _state.update { it.copy(message = failure.message ?: "Could not create folder") }
                }
            refresh()
        }
    }

    fun rename(entry: SftpManager.RemoteEntry, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            val sftp = manager() ?: return@launch
            runCatching { sftp.rename(entry.path, joinPath(_state.value.path, newName.trim())) }
                .onFailure { failure ->
                    _state.update { it.copy(message = failure.message ?: "Could not rename") }
                }
            refresh()
        }
    }

    fun delete(entry: SftpManager.RemoteEntry) {
        viewModelScope.launch {
            val sftp = manager() ?: return@launch
            runCatching { sftp.delete(entry.path, recursive = entry.isDirectory) }
                .onFailure { failure ->
                    _state.update { it.copy(message = failure.message ?: "Could not delete") }
                }
            refresh()
        }
    }

    fun chmod(entry: SftpManager.RemoteEntry, octal: String) {
        viewModelScope.launch {
            val sftp = manager() ?: return@launch
            runCatching { sftp.chmod(entry.path, octal) }
                .onFailure { failure ->
                    _state.update { it.copy(message = failure.message ?: "Could not change mode") }
                }
            refresh()
        }
    }

    /** Downloads into the app's external files directory, which needs no storage permission. */
    fun download(entry: SftpManager.RemoteEntry) {
        if (entry.isDirectory) return
        viewModelScope.launch {
            val sftp = manager() ?: return@launch
            val target = File(downloadDirectory(), entry.name)
            _state.update {
                it.copy(transfer = TransferState(entry.name, 0f, 0, upload = false))
            }
            val result = runCatching {
                target.outputStream().use { output ->
                    sftp.download(entry.path, output, entry.size) { progress ->
                        _state.update {
                            it.copy(
                                transfer = TransferState(
                                    entry.name,
                                    progress.fraction,
                                    progress.bytesPerSecond,
                                    upload = false,
                                ),
                            )
                        }
                    }
                }
            }
            _state.update {
                it.copy(
                    transfer = null,
                    message = result.fold(
                        onSuccess = { "Saved to ${target.absolutePath}" },
                        onFailure = { failure -> failure.message ?: "Download failed" },
                    ),
                )
            }
        }
    }

    /** Uploads a document picked through the system picker. */
    fun upload(uri: Uri) {
        viewModelScope.launch {
            val sftp = manager() ?: return@launch
            val (name, size) = documentInfo(uri)
            val remotePath = joinPath(_state.value.path, name)
            _state.update { it.copy(transfer = TransferState(name, 0f, 0, upload = true)) }
            val result = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    sftp.upload(input, remotePath, size) { progress ->
                        _state.update {
                            it.copy(
                                transfer = TransferState(
                                    name,
                                    progress.fraction,
                                    progress.bytesPerSecond,
                                    upload = true,
                                ),
                            )
                        }
                    }
                } ?: error("Could not read the selected file")
            }
            _state.update {
                it.copy(
                    transfer = null,
                    message = result.fold(
                        onSuccess = { "Uploaded $name" },
                        onFailure = { failure -> failure.message ?: "Upload failed" },
                    ),
                )
            }
            refresh()
        }
    }

    private fun downloadDirectory(): File =
        File(context.getExternalFilesDir(null), "downloads").apply { mkdirs() }

    private fun documentInfo(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "upload.bin"
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        return name to size
    }

    private fun joinPath(directory: String, name: String): String = when {
        directory.isEmpty() || directory == "." -> name
        directory.endsWith("/") -> directory + name
        else -> "$directory/$name"
    }
}
