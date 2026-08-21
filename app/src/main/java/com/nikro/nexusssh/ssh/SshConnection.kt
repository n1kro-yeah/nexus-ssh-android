package com.nikro.nexusssh.ssh

import com.nikro.nexusssh.core.log.AppLogger
import com.nikro.nexusssh.data.repository.KnownHostRepository
import com.nikro.nexusssh.ssh.auth.PromptingChallengeResponder
import com.nikro.nexusssh.ssh.auth.PromptingPassphraseFinder
import com.nikro.nexusssh.ssh.auth.PromptingPasswordFinder
import com.nikro.nexusssh.ssh.hostkey.VaultHostKeyVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthMethod
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** One live SSH transport plus the jump-host chain needed to reach it. */
class SshConnection(
    val config: ConnectionConfig,
    private val knownHosts: KnownHostRepository,
    private val onPrompt: suspend (SshPrompt) -> String?,
    private val onConfirm: suspend (SshPrompt) -> Boolean,
    private val onEvent: (SshEvent) -> Unit = {},
) {
    private var client: SSHClient? = null
    private val hops = mutableListOf<SSHClient>()

    @Volatile
    private var closing = false

    val isConnected: Boolean
        get() = client?.let { it.isConnected && it.isAuthenticated } == true

    var connectionInfo: ConnectionInfo? = null
        private set

    data class ConnectionInfo(
        val serverVersion: String,
        val keyExchange: String,
        val cipher: String,
        val mac: String,
        val compression: String,
        val hostKeyType: String,
        val hostKeyFingerprint: String,
        val viaJumpHosts: List<String>,
    )

    suspend fun connect() = withContext(Dispatchers.IO) {
        SecurityProviderInstaller.install()
        check(client == null) { "already connected" }
        closing = false

        var previous: SSHClient? = null
        config.jumpChain.forEachIndexed { index, hop ->
            onEvent(SshEvent.Status("Jump ${index + 1}/${config.jumpChain.size}: ${hop.hostname}"))
            val hopClient = newClient(hop)
            try {
                if (previous == null) {
                    hopClient.connect(hop.hostname, hop.port)
                } else {
                    hopClient.connectVia(previous!!.newDirectConnection(hop.hostname, hop.port))
                }
                authenticate(hopClient, hop)
            } catch (error: Throwable) {
                runCatching { hopClient.disconnect() }
                closeHops()
                throw SshConnectionException("Jump host ${hop.hostname}: ${error.friendlyMessage()}", error)
            }
            hops += hopClient
            previous = hopClient
        }

        onEvent(SshEvent.Status("Connecting to ${config.hostname}:${config.port}"))
        val target = newClient(config)
        try {
            if (previous == null) {
                target.connect(config.hostname, config.port)
            } else {
                target.connectVia(previous!!.newDirectConnection(config.hostname, config.port))
            }
            onEvent(SshEvent.Status("Authenticating as ${config.username}"))
            authenticate(target, config)
        } catch (error: Throwable) {
            runCatching { target.disconnect() }
            closeHops()
            throw SshConnectionException(error.friendlyMessage(), error)
        }

        if (config.keepAliveSeconds > 0) {
            runCatching { target.connection.keepAlive.keepAliveInterval = config.keepAliveSeconds }
                .onFailure { AppLogger.w(TAG, "Keep-alive unavailable: ${it.message}") }
        }

        client = target
        connectionInfo = describe(target)
        onEvent(SshEvent.Connected)
    }

    private fun newClient(target: ConnectionConfig): SSHClient {
        val sshConfig = DefaultConfig().apply {
            if (target.keepAliveSeconds > 0) keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
            version = CLIENT_VERSION
        }
        return SSHClient(sshConfig).apply {
            connectTimeout = target.connectTimeoutMs
            timeout = if (target.readTimeoutMs > 0) target.readTimeoutMs else 0
            addHostKeyVerifier(
                VaultHostKeyVerifier(
                    repository = knownHosts,
                    strict = target.strictHostKeyChecking,
                    onPrompt = onConfirm,
                ),
            )
            if (target.compression) {
                runCatching { useCompression() }
                    .onFailure { AppLogger.w(TAG, "Compression unavailable: ${it.message}") }
            }
        }
    }

    private fun authenticate(client: SSHClient, target: ConnectionConfig) {
        val methods = mutableListOf<AuthMethod>()
        target.keys.forEach { material ->
            val provider = runCatching { loadKey(client, material) }
                .onFailure { AppLogger.w(TAG, "Key ${material.label} unusable: ${it.message}") }
                .getOrNull()
            if (provider != null) methods += AuthPublickey(provider)
        }
        if (target.allowPasswordAuth) {
            methods += AuthPassword(
                PromptingPasswordFinder(
                    username = target.username,
                    stored = target.password,
                    onPrompt = onPrompt,
                ),
            )
        }
        if (target.allowKeyboardInteractive) {
            methods += AuthKeyboardInteractive(
                PromptingChallengeResponder(
                    storedPassword = target.password,
                    onPrompt = onPrompt,
                ),
            )
        }
        require(methods.isNotEmpty()) { "No authentication method available for ${target.address}" }
        client.auth(target.username, methods)
    }

    private fun loadKey(client: SSHClient, material: PrivateKeyMaterial): KeyProvider =
        client.loadKeys(
            material.pem,
            null,
            PromptingPassphraseFinder(
                keyLabel = material.label,
                stored = material.passphrase,
                onPrompt = onPrompt,
            ),
        )

    private fun describe(client: SSHClient): ConnectionInfo {
        val transport = client.transport
        return ConnectionInfo(
            serverVersion = runCatching { transport.serverVersion.toString() }.getOrDefault(""),
            keyExchange = runCatching { transport.hostKeyAlgorithm.toString() }.getOrDefault(""),
            cipher = runCatching { transport.config.cipherFactories.firstOrNull()?.name.orEmpty() }
                .getOrDefault(""),
            mac = runCatching { transport.config.macFactories.firstOrNull()?.name.orEmpty() }
                .getOrDefault(""),
            compression = if (config.compression) "zlib@openssh.com" else "none",
            hostKeyType = runCatching { transport.hostKeyAlgorithm.toString() }.getOrDefault(""),
            hostKeyFingerprint = "",
            viaJumpHosts = config.jumpChain.map { it.hostname },
        )
    }

    /** Opens an interactive shell with a PTY sized [columns] × [rows]. */
    suspend fun openShell(columns: Int, rows: Int): SshShellChannel = withContext(Dispatchers.IO) {
        val active = client ?: throw SshConnectionException("Not connected")
        val session = active.startSession()
        if (config.agentForwarding) requestAgentForwarding(session)

        config.environment.forEach { (name, value) ->
            runCatching { session.setEnvVar(name, value) }
                .onFailure { AppLogger.w(TAG, "Server rejected env $name") }
        }
        session.allocatePTY(
            config.terminalType,
            columns,
            rows,
            columns * CELL_WIDTH_PX,
            rows * CELL_HEIGHT_PX,
            emptyMap(),
        )
        session.autoExpand = true
        val shell = session.startShell()
        SshShellChannel(session, shell).also { channel ->
            config.startupCommand?.takeIf { it.isNotBlank() }?.let { command ->
                channel.write((command.trimEnd('\n') + "\n").toByteArray(charset(config.charset)))
            }
        }
    }

    /** Runs a one-shot command and collects its output. */
    suspend fun exec(command: String, timeoutSeconds: Long = 60): CommandResult =
        withContext(Dispatchers.IO) {
            val active = client ?: throw SshConnectionException("Not connected")
            active.startSession().use { session ->
                val execution = session.exec(command)
                val output = execution.inputStream.readBytes().toString(Charsets.UTF_8)
                val errors = execution.errorStream.readBytes().toString(Charsets.UTF_8)
                execution.join(timeoutSeconds, TimeUnit.SECONDS)
                CommandResult(execution.exitStatus ?: -1, output, errors)
            }
        }

    data class CommandResult(val exitStatus: Int, val output: String, val errorOutput: String) {
        val isSuccess: Boolean get() = exitStatus == 0
    }

    suspend fun openSftp(): SFTPClient = withContext(Dispatchers.IO) {
        (client ?: throw SshConnectionException("Not connected")).newSFTPClient()
    }

    fun openDirectChannel(host: String, port: Int): DirectConnection =
        (client ?: throw SshConnectionException("Not connected")).newDirectConnection(host, port)

    internal fun requireClient(): SSHClient = client ?: throw SshConnectionException("Not connected")

    private fun requestAgentForwarding(session: Session) {
        runCatching {
            val method = session.javaClass.methods.firstOrNull {
                it.name == "sendChannelRequest" && it.parameterCount == 3
            } ?: error("sendChannelRequest not available")
            method.isAccessible = true
            val bufferClass = Class.forName("net.schmizz.sshj.common.Buffer\$PlainBuffer")
            method.invoke(session, "auth-agent-req@openssh.com", false, bufferClass.newInstance())
        }.onFailure {
            AppLogger.w(TAG, "Agent forwarding request failed: ${it.message}")
            onEvent(SshEvent.Status("Agent forwarding is not supported by this session"))
        }
    }

    fun disconnect(reason: String? = null) {
        if (closing) return
        closing = true
        runCatching { client?.disconnect() }
            .onFailure { AppLogger.d(TAG, "disconnect: ${it.message}") }
        client = null
        closeHops()
        connectionInfo = null
        onEvent(SshEvent.Closed(reason))
    }

    private fun closeHops() {
        hops.asReversed().forEach { hop -> runCatching { hop.disconnect() } }
        hops.clear()
    }

    private companion object {
        const val TAG = "SshConnection"
        const val CLIENT_VERSION = "NexusSSH_1.0"
        const val CELL_WIDTH_PX = 8
        const val CELL_HEIGHT_PX = 16
    }
}

/** An interactive shell channel plus the input/output streams used by TerminalSession. */
class SshShellChannel(
    private val session: Session,
    private val shell: Session.Shell,
) {
    val input: InputStream = shell.inputStream
    val errorStream: InputStream = shell.errorStream
    private val output: OutputStream = shell.outputStream

    val isOpen: Boolean get() = shell.isOpen

    /** SSHJ exposes exit status only for command channels, not persistent PTY shells. */
    val exitStatus: Int? get() = null

    /** SSHJ exposes no interactive-shell exit signal. */
    val exitSignal: String? get() = null

    @Synchronized
    fun write(bytes: ByteArray, length: Int = bytes.size) {
        output.write(bytes, 0, length)
        output.flush()
    }

    fun resize(columns: Int, rows: Int) {
        runCatching { shell.changeWindowDimensions(columns, rows, columns * 8, rows * 16) }
    }

    fun signal(signal: Signal) {
        runCatching { shell.signal(signal.sshjSignal) }
    }

    enum class Signal(val sshjSignal: net.schmizz.sshj.connection.channel.direct.Signal) {
        INT(net.schmizz.sshj.connection.channel.direct.Signal.INT),
        HUP(net.schmizz.sshj.connection.channel.direct.Signal.HUP),
        TERM(net.schmizz.sshj.connection.channel.direct.Signal.TERM),
        KILL(net.schmizz.sshj.connection.channel.direct.Signal.KILL),
        QUIT(net.schmizz.sshj.connection.channel.direct.Signal.QUIT),
    }

    fun close() {
        runCatching { output.flush() }
        runCatching { shell.close() }
        runCatching { session.close() }
    }
}

class SshConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Turns SSHJ's exception zoo into wording useful in the UI. */
fun Throwable.friendlyMessage(): String {
    val raw = message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
    return when {
        this is java.net.UnknownHostException -> "Host not found: $raw"
        this is java.net.ConnectException -> "Connection refused"
        this is java.net.SocketTimeoutException -> "Connection timed out"
        this is java.net.NoRouteToHostException -> "No route to host"
        raw.contains("Exhausted available authentication methods") ->
            "Authentication failed - check the username, password or key"
        raw.contains("could not verify", ignoreCase = true) -> "Host key rejected"
        raw.contains("Connection reset") -> "The server closed the connection"
        else -> raw
    }
}
