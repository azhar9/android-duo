package com.therealsoftware.duo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

private const val TCP_PORT = 8899
private const val UDP_PORT = 8898
private const val MAGIC = "ANDROID_DUO_V1"
private const val DISCOVER_TIMEOUT_MS = 6000L

sealed interface NetEvent {
    /** Socket is up. [isHost] decides which half of the canvas this device gets. */
    data class Up(val isHost: Boolean) : NetEvent
    data class Msg(val json: JSONObject) : NetEvent
    data class Down(val why: String) : NetEvent
}

/**
 * Two-phone link over the local network.
 *
 * Host runs a UDP responder for discovery plus a TCP server. Client broadcasts a
 * probe, gets the host's address back, connects. After that everything is
 * newline-delimited JSON down one TCP socket.
 *
 * Why WiFi and not USB-C: a phone is a display *source*, never a sink, and USB-C
 * between two phones gives you file transfer at best. Put both on one hotspot and
 * the RTT is a few ms, which is well under what this needs.
 */
class Link(private val scope: CoroutineScope) {

    // replay=1 so tryEmit never silently drops an event if collection starts late.
    private val _events = MutableSharedFlow<NetEvent>(
        replay = 1,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<NetEvent> = _events

    // One writer coroutine draining a FIFO — ball updates must arrive in order,
    // so we cannot just fire a coroutine per send().
    private val outbox = Channel<JSONObject>(Channel.UNLIMITED)
    private var socket: Socket? = null
    private var job: Job? = null
    private var writerJob: Job? = null

    /** The address of the other phone. The streamer needs it to fetch the clip. */
    var peerIp: String? = null
        private set

    fun host() {
        job = scope.launch(Dispatchers.IO) {
            try {
                val responder = launch { udpResponder() }
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(TCP_PORT))
                }.use { server ->
                    val s = server.accept()
                    responder.cancel()
                    attach(s, isHost = true)
                }
            } catch (e: Exception) {
                _events.tryEmit(NetEvent.Down(e.message ?: "host failed"))
            }
        }
    }

    fun join() {
        job = scope.launch(Dispatchers.IO) {
            try {
                val addr = discover()
                if (addr == null) {
                    _events.tryEmit(NetEvent.Down("no host found — same hotspot?"))
                    return@launch
                }
                val s = Socket()
                s.connect(InetSocketAddress(addr, TCP_PORT), 5000)
                attach(s, isHost = false)
            } catch (e: Exception) {
                _events.tryEmit(NetEvent.Down(e.message ?: "join failed"))
            }
        }
    }

    fun send(o: JSONObject) {
        if (socket == null) return
        outbox.trySend(o)
    }

    fun close() {
        job?.cancel()
        writerJob?.cancel()
        runCatching { socket?.close() }
        socket = null
        peerIp = null
    }

    private suspend fun attach(s: Socket, isHost: Boolean) {
        socket = s
        peerIp = s.inetAddress?.hostAddress
        s.tcpNoDelay = true            // 60Hz of small frames — Nagle would add 40ms for nothing

        val w = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
        writerJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val o = outbox.receive()
                    w.write(o.toString())
                    w.write("\n")
                    w.flush()
                }
            } catch (_: Exception) {
            }
        }

        _events.tryEmit(NetEvent.Up(isHost))

        try {
            val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (currentCoroutineContext().isActive) {
                val line = r.readLine() ?: break
                runCatching { JSONObject(line) }.getOrNull()?.let { _events.tryEmit(NetEvent.Msg(it)) }
            }
        } catch (_: Exception) {
        } finally {
            _events.tryEmit(NetEvent.Down("peer disconnected"))
        }
    }

    /** Host only. Answers discovery probes until a client actually connects. */
    private suspend fun udpResponder() {
        val sock = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(UDP_PORT))
        }
        try {
            val buf = ByteArray(64)
            while (currentCoroutineContext().isActive) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(p)
                } catch (_: Exception) {
                    continue
                }
                if (String(p.data, 0, p.length) == MAGIC) {
                    val reply = MAGIC.toByteArray()
                    runCatching { sock.send(DatagramPacket(reply, reply.size, p.address, p.port)) }
                }
            }
        } finally {
            sock.close()
        }
    }

    /** Client only. Broadcast until a host answers, then stop. */
    private fun discover(): InetAddress? {
        val sock = DatagramSocket()
        try {
            sock.broadcast = true
            sock.soTimeout = 600
            val probe = MAGIC.toByteArray()
            val target = broadcastAddress()
            val deadline = System.currentTimeMillis() + DISCOVER_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                runCatching { sock.send(DatagramPacket(probe, probe.size, target, UDP_PORT)) }
                val buf = ByteArray(64)
                val p = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(p)
                    if (String(p.data, 0, p.length) == MAGIC) return p.address
                } catch (_: Exception) {
                    // timeout — probe again
                }
            }
        } finally {
            sock.close()
        }
        return null
    }

    private fun broadcastAddress(): InetAddress {
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val b = ia.broadcast
                    if (b != null && ia.address is Inet4Address) return b
                }
            }
        } catch (_: Exception) {
        }
        return InetAddress.getByName("255.255.255.255")
    }

    companion object {
        /** Shown on the host screen as a fallback if discovery is blocked by the hotspot. */
        fun localIp(): String {
            try {
                for (nif in NetworkInterface.getNetworkInterfaces()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (ia in nif.interfaceAddresses) {
                        val a = ia.address
                        if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress ?: "?"
                    }
                }
            } catch (_: Exception) {
            }
            return "?.?.?.?"
        }
    }
}
