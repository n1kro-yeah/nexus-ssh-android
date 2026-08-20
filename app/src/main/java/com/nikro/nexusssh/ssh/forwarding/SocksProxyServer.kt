package com.nikro.nexusssh.ssh.forwarding

import com.nikro.nexusssh.core.log.AppLogger
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A SOCKS4/4a/5 proxy that tunnels every accepted connection through the SSH transport, which is
 * what `ssh -D` does. Pointing a browser or `curl --socks5` at the bound port routes traffic
 * through the remote host.
 *
 * Only the CONNECT command is implemented - BIND and UDP ASSOCIATE are not meaningful over a
 * `direct-tcpip` channel.
 */
class SocksProxyServer(
    private val bindAddress: String,
    private val port: Int,
    private val channelFactory: (host: String, port: Int) -> DirectConnection,
) {

    var serverSocket: ServerSocket? = null
        private set

    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "socks-worker").apply { isDaemon = true }
    }

    private val liveConnections = AtomicInteger()

    fun bind() {
        serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, port))
        }
        AppLogger.i(TAG, "SOCKS proxy listening on $bindAddress:$port")
    }

    /** Blocks accepting clients until the socket is closed. */
    fun serve(onConnectionCountChanged: (Int) -> Unit = {}) {
        val socket = serverSocket ?: error("bind() first")
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (error: IOException) {
                if (socket.isClosed) return
                throw error
            }
            onConnectionCountChanged(liveConnections.incrementAndGet())
            workers.execute {
                try {
                    client.tcpNoDelay = true
                    handle(client)
                } catch (error: Throwable) {
                    AppLogger.d(TAG, "SOCKS session ended: ${error.message}")
                } finally {
                    runCatching { client.close() }
                    onConnectionCountChanged(liveConnections.decrementAndGet())
                }
            }
        }
    }

    fun close() {
        runCatching { serverSocket?.close() }
        workers.shutdownNow()
    }

    // ---------------------------------------------------------------------------------------
    // Protocol
    // ---------------------------------------------------------------------------------------

    private fun handle(client: Socket) {
        val input = DataInputStream(client.getInputStream().buffered())
        val output = client.getOutputStream()

        when (val version = input.read()) {
            0x05 -> handleSocks5(input, output, client)
            0x04 -> handleSocks4(input, output, client)
            -1 -> Unit
            else -> AppLogger.d(TAG, "Unsupported SOCKS version $version")
        }
    }

    private fun handleSocks5(input: DataInputStream, output: OutputStream, client: Socket) {
        // Greeting: NMETHODS then the method list. We only offer "no authentication".
        val methodCount = input.read()
        if (methodCount <= 0) return
        val methods = ByteArray(methodCount)
        input.readFully(methods)
        if (methods.none { it == 0x00.toByte() }) {
            output.write(byteArrayOf(0x05, 0xFF.toByte()))
            output.flush()
            return
        }
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()

        // Request: VER CMD RSV ATYP DST.ADDR DST.PORT
        if (input.read() != 0x05) return
        val command = input.read()
        input.read() // reserved
        val addressType = input.read()

        val host = when (addressType) {
            0x01 -> {
                val raw = ByteArray(4)
                input.readFully(raw)
                InetAddress.getByAddress(raw).hostAddress ?: return
            }

            0x03 -> {
                val length = input.read()
                val raw = ByteArray(length)
                input.readFully(raw)
                String(raw, Charsets.US_ASCII)
            }

            0x04 -> {
                val raw = ByteArray(16)
                input.readFully(raw)
                InetAddress.getByAddress(raw).hostAddress ?: return
            }

            else -> {
                reply5(output, 0x08) // address type not supported
                return
            }
        }
        val targetPort = (input.read() shl 8) or input.read()

        if (command != 0x01) {
            reply5(output, 0x07) // command not supported
            return
        }

        val channel = try {
            channelFactory(host, targetPort)
        } catch (error: Throwable) {
            AppLogger.d(TAG, "SOCKS5 connect to $host:$targetPort failed: ${error.message}")
            reply5(output, 0x05) // connection refused
            return
        }

        reply5(output, 0x00)
        pump(client, channel)
    }

    private fun reply5(output: OutputStream, status: Int) {
        // BND.ADDR/BND.PORT are meaningless for a tunnelled channel; zeros are accepted.
        output.write(
            byteArrayOf(
                0x05, status.toByte(), 0x00, 0x01,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00,
            ),
        )
        output.flush()
    }

    private fun handleSocks4(input: DataInputStream, output: OutputStream, client: Socket) {
        val command = input.read()
        val targetPort = (input.read() shl 8) or input.read()
        val addressBytes = ByteArray(4)
        input.readFully(addressBytes)

        // USERID, NUL terminated
        val userId = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte <= 0) break
            userId.append(byte.toChar())
        }

        // SOCKS4a signals "resolve on the proxy" with 0.0.0.x followed by a hostname.
        val isSocks4a = addressBytes[0] == 0.toByte() && addressBytes[1] == 0.toByte() &&
            addressBytes[2] == 0.toByte() && addressBytes[3] != 0.toByte()
        val host = if (isSocks4a) {
            val name = StringBuilder()
            while (true) {
                val byte = input.read()
                if (byte <= 0) break
                name.append(byte.toChar())
            }
            name.toString()
        } else {
            InetAddress.getByAddress(addressBytes).hostAddress ?: return
        }

        if (command != 0x01) {
            reply4(output, 0x5B)
            return
        }

        val channel = try {
            channelFactory(host, targetPort)
        } catch (error: Throwable) {
            AppLogger.d(TAG, "SOCKS4 connect to $host:$targetPort failed: ${error.message}")
            reply4(output, 0x5B)
            return
        }

        reply4(output, 0x5A)
        pump(client, channel)
    }

    private fun reply4(output: OutputStream, status: Int) {
        output.write(byteArrayOf(0x00, status.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        output.flush()
    }

    /** Copies bytes both ways until either side closes. */
    private fun pump(client: Socket, channel: DirectConnection) {
        val channelInput: InputStream = channel.inputStream
        val channelOutput: OutputStream = channel.outputStream
        val clientInput = client.getInputStream()
        val clientOutput = client.getOutputStream()

        val upstream = Thread({
            copy(clientInput, channelOutput)
        }, "socks-up").apply { isDaemon = true }
        upstream.start()

        copy(channelInput, clientOutput)
        runCatching { upstream.join(500) }
        runCatching { channel.close() }
    }

    private fun copy(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val read = from.read(buffer)
                if (read < 0) break
                to.write(buffer, 0, read)
                to.flush()
            }
        } catch (_: IOException) {
            // Normal when either end closes.
        } finally {
            runCatching { to.flush() }
        }
    }

    private companion object {
        const val TAG = "SocksProxy"
        const val BUFFER_SIZE = 32 * 1024
    }
}
