package com.nikro.nexusssh.ssh.hostkey

import com.nikro.nexusssh.core.log.AppLogger
import com.nikro.nexusssh.data.repository.KnownHostRepository
import com.nikro.nexusssh.ssh.SshPrompt
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey

/**
 * Trust-on-first-use verifier backed by [KnownHostRepository].
 *
 * SSHJ calls [verify] from its transport thread and expects a synchronous answer, so the
 * coroutine-based UI prompt is bridged with [runBlocking]. The transport thread is dedicated to
 * this handshake and is not the main thread, so blocking it is exactly the intended behaviour.
 */
class VaultHostKeyVerifier(
    private val repository: KnownHostRepository,
    private val strict: Boolean,
    /** Returns true when the user accepts the key. Must never be called on the main thread. */
    private val onPrompt: suspend (SshPrompt) -> Boolean,
) : HostKeyVerifier {

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean = runBlocking {
        when (val verdict = repository.verify(hostname, port, key)) {
            is KnownHostRepository.Verdict.Known -> true

            is KnownHostRepository.Verdict.Revoked -> {
                AppLogger.e(TAG, "Refusing revoked host key for $hostname:$port (${verdict.fingerprint})")
                false
            }

            is KnownHostRepository.Verdict.Unknown -> {
                if (!strict) {
                    repository.trust(hostname, port, key)
                    return@runBlocking true
                }
                val accepted = onPrompt(
                    SshPrompt.UnknownHostKey(
                        hostname = hostname,
                        port = port,
                        keyType = verdict.keyType,
                        fingerprint = verdict.fingerprint,
                        randomArt = verdict.randomArt,
                    ),
                )
                if (accepted) repository.trust(hostname, port, key)
                accepted
            }

            is KnownHostRepository.Verdict.Changed -> {
                AppLogger.e(
                    TAG,
                    "HOST KEY CHANGED for $hostname:$port " +
                        "stored=${verdict.storedFingerprint} presented=${verdict.presentedFingerprint}",
                )
                val accepted = onPrompt(
                    SshPrompt.ChangedHostKey(
                        hostname = hostname,
                        port = port,
                        keyType = verdict.keyType,
                        storedFingerprint = verdict.storedFingerprint,
                        presentedFingerprint = verdict.presentedFingerprint,
                        randomArt = verdict.randomArt,
                    ),
                )
                if (accepted) {
                    repository.forgetHost(hostname, port)
                    repository.trust(hostname, port, key)
                }
                accepted
            }
        }
    }

    /**
     * Lets SSHJ prefer the host key algorithm we already trust, which avoids a spurious
     * "key changed" warning when a server offers both Ed25519 and RSA host keys.
     */
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = runBlocking {
        repository.entriesFor(hostname, port).map { it.keyType }
    }

    private companion object {
        const val TAG = "HostKeyVerifier"
    }
}
