package com.nikro.nexusssh.ssh.forwarding

import com.nikro.nexusssh.core.log.AppLogger
import com.nikro.nexusssh.domain.model.ForwardType
import com.nikro.nexusssh.domain.model.PortForwardRule
import com.nikro.nexusssh.ssh.SshConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Runs local (-L), remote (-R) and dynamic (-D, SOCKS5) forwards on top of a live
 * [SshConnection].
 *
 * Each forward owns a coroutine plus, for local/dynamic, a listening [ServerSocket]. Stopping a
 * forward closes the socket which makes the blocking `accept()` throw, which ends the coroutine.
 */
class PortForwardManager(
    private val connection: SshConnection,
    parentScope: CoroutineScope? = null,
) {

    private val scope = parentScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _active = MutableStateFlow<List<ActiveForward>>(emptyList())
    val active: StateFlow<List<ActiveForward>> = _active.asStateFlow()

    private val jobs = HashMap<Long, RunningForward>()

    data class ActiveForward(
        val ruleId: Long,
        val rule: PortForwardRule,
        val state: State,
        val error: String? = null,
        val connections: Int = 0,
    ) {
        enum class State { STARTING, LISTENING, STOPPED, FAILED }

        val summary: String
            get() = when (rule.type) {
                ForwardType.LOCAL -> "${rule.bindAddress}:${rule.localPort} -> ${rule.remoteHost}:${rule.remotePort}"
                ForwardType.REMOTE -> "remote ${rule.bindAddress}:${rule.remotePort} -> ${rule.remoteHost}:${rule.localPort}"
                ForwardType.DYNAMIC -> "socks5://${rule.bindAddress}:${rule.localPort}"
            }
    }

    private class RunningForward(
        val job: Job,
        val serverSocket: ServerSocket?,
        val remoteForward: RemotePortForwarder.Forward?,
    )

    /** Starts [rule]; a rule that is already running is left alone. */
    fun start(rule: PortForwardRule) {
        if (jobs.containsKey(rule.id)) return
        publish(rule, ActiveForward.State.STARTING)

        when (rule.type) {
            ForwardType.LOCAL -> startLocal(rule)
            ForwardType.DYNAMIC -> startDynamic(rule)
            ForwardType.REMOTE -> startRemote(rule)
        }
    }

    private fun startLocal(rule: PortForwardRule) {
        val serverSocket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(rule.bindAddress, rule.localPort))
            }
        }.getOrElse { error ->
            publish(rule, ActiveForward.State.FAILED, error.message ?: "Cannot bind port")
            return
        }

        val job = scope.launch {
            try {
                publish(rule, ActiveForward.State.LISTENING)
                val parameters = Parameters(
                    rule.bindAddress,
                    rule.localPort,
                    rule.remoteHost,
                    rule.remotePort,
                )
                connection.requireClient()
                    .newLocalPortForwarder(parameters, serverSocket)
                    .listen()
            } catch (error: Throwable) {
                if (error !is IOException || serverSocket.isClosed.not()) {
                    AppLogger.w(TAG, "Local forward ${rule.label} ended: ${error.message}")
                }
                publish(rule, ActiveForward.State.STOPPED, error.message)
            } finally {
                runCatching { serverSocket.close() }
            }
        }
        jobs[rule.id] = RunningForward(job, serverSocket, null)
    }

    private fun startDynamic(rule: PortForwardRule) {
        val server = SocksProxyServer(
            bindAddress = rule.bindAddress,
            port = rule.localPort,
            channelFactory = { host, port -> connection.openDirectChannel(host, port) },
        )
        val job = scope.launch {
            try {
                server.bind()
                publish(rule, ActiveForward.State.LISTENING)
                server.serve { count -> publish(rule, ActiveForward.State.LISTENING, connections = count) }
            } catch (error: Throwable) {
                publish(rule, ActiveForward.State.FAILED, error.message)
            } finally {
                server.close()
            }
        }
        jobs[rule.id] = RunningForward(job, server.serverSocket, null)
    }

    private fun startRemote(rule: PortForwardRule) {
        val job = scope.launch {
            val forward = RemotePortForwarder.Forward(rule.bindAddress, rule.remotePort)
            try {
                connection.requireClient().remotePortForwarder.bind(
                    forward,
                    SocketForwardingConnectListener(
                        InetSocketAddress(rule.remoteHost, rule.localPort),
                    ),
                )
                publish(rule, ActiveForward.State.LISTENING)
                // The forwarder runs on the transport thread; this coroutine only tracks state.
                connection.requireClient().transport.join()
            } catch (error: Throwable) {
                publish(rule, ActiveForward.State.FAILED, error.message)
            }
        }
        jobs[rule.id] = RunningForward(job, null, RemotePortForwarder.Forward(rule.bindAddress, rule.remotePort))
    }

    fun stop(ruleId: Long) {
        val running = jobs.remove(ruleId) ?: return
        running.remoteForward?.let { forward ->
            runCatching { connection.requireClient().remotePortForwarder.cancel(forward) }
        }
        runCatching { running.serverSocket?.close() }
        running.job.cancel()
        _active.value = _active.value.map {
            if (it.ruleId == ruleId) it.copy(state = ActiveForward.State.STOPPED) else it
        }
    }

    fun stopAll() {
        jobs.keys.toList().forEach(::stop)
    }

    fun isRunning(ruleId: Long): Boolean =
        jobs[ruleId]?.job?.isActive == true

    private fun publish(
        rule: PortForwardRule,
        state: ActiveForward.State,
        error: String? = null,
        connections: Int? = null,
    ) {
        val existing = _active.value.firstOrNull { it.ruleId == rule.id }
        val updated = ActiveForward(
            ruleId = rule.id,
            rule = rule,
            state = state,
            error = error,
            connections = connections ?: existing?.connections ?: 0,
        )
        _active.value = if (existing == null) {
            _active.value + updated
        } else {
            _active.value.map { if (it.ruleId == rule.id) updated else it }
        }
    }

    private companion object {
        const val TAG = "PortForwardManager"
    }
}
