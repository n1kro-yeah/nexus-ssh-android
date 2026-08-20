package com.nikro.nexusssh.ssh.auth

import com.nikro.nexusssh.core.log.AppLogger
import com.nikro.nexusssh.ssh.SshPrompt
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.userauth.method.ChallengeResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource

/**
 * Bridges SSHJ's synchronous callback interfaces to the app's coroutine-driven prompt flow.
 *
 * Every implementation here is invoked on the SSHJ transport thread, never on the main thread.
 */

/** Supplies a stored password first, then falls back to asking the user (up to [maxRetries]). */
class PromptingPasswordFinder(
    private val username: String,
    private val stored: String?,
    private val maxRetries: Int = 2,
    private val onPrompt: suspend (SshPrompt) -> String?,
) : PasswordFinder {

    private var attempt = 0

    override fun reqPassword(resource: Resource<*>?): CharArray? {
        attempt++
        if (attempt == 1 && !stored.isNullOrEmpty()) return stored.toCharArray()
        val answer = runBlocking { onPrompt(SshPrompt.Password(username, attempt)) }
        return answer?.toCharArray()
    }

    override fun shouldRetry(resource: Resource<*>?): Boolean = attempt <= maxRetries
}

/** Unlocks a private key: tries the remembered passphrase, then prompts. */
class PromptingPassphraseFinder(
    private val keyLabel: String,
    private val stored: CharArray?,
    private val maxRetries: Int = 2,
    private val onPrompt: suspend (SshPrompt) -> String?,
) : PasswordFinder {

    private var attempt = 0

    override fun reqPassword(resource: Resource<*>?): CharArray? {
        attempt++
        if (attempt == 1 && stored != null && stored.isNotEmpty()) return stored.copyOf()
        val answer = runBlocking { onPrompt(SshPrompt.Passphrase(keyLabel, attempt)) }
        return answer?.toCharArray()
    }

    override fun shouldRetry(resource: Resource<*>?): Boolean = attempt <= maxRetries
}

/**
 * Handles `keyboard-interactive`, which is how virtually every 2FA/OTP setup challenges a client.
 *
 * A single-prompt, non-echo challenge whose text looks like a password is answered from the
 * stored password on the first round; anything else ("Verification code:", "Duo passcode:") is
 * always shown to the user.
 */
class PromptingChallengeResponder(
    private val storedPassword: String?,
    private val onPrompt: suspend (SshPrompt) -> String?,
) : ChallengeResponseProvider {

    private var name: String = ""
    private var instruction: String = ""
    private var round = 0
    private var usedStoredPassword = false

    override fun getSubmethods(): List<String> = emptyList()

    override fun init(resource: Resource<*>?, name: String?, instruction: String?) {
        this.name = name.orEmpty()
        this.instruction = instruction.orEmpty()
        round++
        if (this.instruction.isNotBlank()) {
            AppLogger.i(TAG, "keyboard-interactive: ${this.instruction.take(200)}")
        }
    }

    override fun getResponse(prompt: String?, echo: Boolean): CharArray {
        val text = prompt.orEmpty()
        if (!echo && !usedStoredPassword && !storedPassword.isNullOrEmpty() && looksLikePassword(text)) {
            usedStoredPassword = true
            return storedPassword.toCharArray()
        }
        val answer = runBlocking {
            onPrompt(
                SshPrompt.KeyboardInteractive(
                    name = name,
                    instruction = instruction,
                    prompt = text,
                    echo = echo,
                ),
            )
        }
        return answer?.toCharArray() ?: CharArray(0)
    }

    override fun shouldRetry(): Boolean = round <= MAX_ROUNDS

    private fun looksLikePassword(prompt: String): Boolean {
        val lower = prompt.lowercase()
        return lower.contains("password") && !lower.contains("one-time") && !lower.contains("otp")
    }

    private companion object {
        const val TAG = "KeyboardInteractive"
        const val MAX_ROUNDS = 4
    }
}

/** Wraps a fixed passphrase without any prompting; used for background reconnects. */
class StaticPasswordFinder(private val secret: CharArray?) : PasswordFinder {
    override fun reqPassword(resource: Resource<*>?): CharArray? = secret?.copyOf()
    override fun shouldRetry(resource: Resource<*>?): Boolean = false
}
