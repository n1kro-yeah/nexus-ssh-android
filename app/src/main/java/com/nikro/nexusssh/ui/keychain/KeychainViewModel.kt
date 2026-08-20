package com.nikro.nexusssh.ui.keychain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikro.nexusssh.data.repository.KeychainRepository
import com.nikro.nexusssh.domain.model.SshKey
import com.nikro.nexusssh.domain.model.SshKeyType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keychain state and the key operations the list offers.
 *
 * Passphrases arrive as Strings from Compose text fields, are converted to char arrays for the
 * repository (which wipes them), and are never kept in this view model.
 */
@HiltViewModel
class KeychainViewModel @Inject constructor(
    private val keychain: KeychainRepository,
) : ViewModel() {

    data class UiState(
        val keys: List<SshKey> = emptyList(),
        val busy: Boolean = false,
        val message: String? = null,
        val clipboardText: String? = null,
    )

    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val clipboardText = MutableStateFlow<String?>(null)

    val state: StateFlow<UiState> = combine(
        keychain.observeKeys(),
        busy,
        message,
        clipboardText,
    ) { keys, isBusy, text, clip ->
        UiState(keys = keys, busy = isBusy, message = text, clipboardText = clip)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState())

    fun dismissMessage() {
        message.value = null
    }

    fun clipboardConsumed() {
        clipboardText.value = null
    }

    fun generate(label: String, type: SshKeyType, comment: String, passphrase: String) {
        viewModelScope.launch {
            busy.value = true
            val result = runCatching {
                keychain.generate(
                    label,
                    type,
                    comment.ifBlank { "nexus-ssh" },
                    passphrase.takeIf { it.isNotEmpty() }?.toCharArray(),
                )
            }
            busy.value = false
            message.value = result.fold(
                onSuccess = { "Generated ${it.type.displayName} key" },
                onFailure = { it.message ?: "Could not generate the key" },
            )
        }
    }

    fun importKey(label: String, pem: String, passphrase: String) {
        viewModelScope.launch {
            busy.value = true
            val outcome = runCatching {
                keychain.import(
                    label,
                    pem,
                    passphrase.takeIf { it.isNotEmpty() }?.toCharArray(),
                )
            }
            busy.value = false
            message.value = outcome.fold(
                onSuccess = { result ->
                    when (result) {
                        is KeychainRepository.ImportOutcome.Saved ->
                            "Imported ${result.key.type.displayName} key"

                        is KeychainRepository.ImportOutcome.NeedsPassphrase ->
                            if (result.wrongPassphrase) {
                                "That passphrase did not unlock the ${result.format} key"
                            } else {
                                "This ${result.format} key is encrypted - enter its passphrase"
                            }

                        is KeychainRepository.ImportOutcome.Error -> result.message
                    }
                },
                onFailure = { it.message ?: "Could not import the key" },
            )
        }
    }

    fun rename(id: Long, label: String) {
        if (label.isBlank()) return
        viewModelScope.launch { keychain.rename(id, label.trim()) }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            val usages = keychain.usageCount(id)
            keychain.delete(id)
            message.value = if (usages > 0) {
                "Key deleted and detached from $usages host(s)"
            } else {
                "Key deleted"
            }
        }
    }

    /** Puts the public line on the clipboard so it can be pasted into authorized_keys. */
    fun copyPublicKey(id: Long) {
        viewModelScope.launch {
            val line = keychain.publicKeyLine(id)
            if (line.isNullOrBlank()) {
                message.value = "No public key stored for this entry"
            } else {
                clipboardText.value = line
                message.value = "Public key copied"
            }
        }
    }

    /** Exports the key in a passphrase-protected portable form for another device. */
    fun exportPortable(id: Long, passphrase: String) {
        viewModelScope.launch {
            val blob = runCatching {
                keychain.exportPortable(id, passphrase.toCharArray())
            }.getOrNull()
            if (blob == null) {
                message.value = "Could not export the key"
            } else {
                clipboardText.value = blob
                message.value = "Encrypted key copied to the clipboard"
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
