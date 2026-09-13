package com.therealsoftware.duo

import android.util.Log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "DuoStream"

/** Frames each second for the remote display. */
const val REMOTE_FPS = 10L

/** A frame larger than this is a protocol error, not a picture. */
private const val MAX_FRAME = 8 * 1024 * 1024

/**
 * Write one frame: a four-byte big-endian length, then the picture itself.
 *
 * The length comes first so the reader always knows how much to wait for. A
 * socket delivers bytes in arbitrary pieces, and without a length the reader
 * cannot tell a whole frame from half of one.
 */
fun writeFrame(out: OutputStream, jpeg: ByteArray) {
    val size = jpeg.size
    out.write(byteArrayOf(
        (size ushr 24).toByte(),
        (size ushr 16).toByte(),
        (size ushr 8).toByte(),
        size.toByte(),
    ))
    out.write(jpeg)
    out.flush()
}

/** Read one frame. Returns null at the end of the stream, or on a broken frame. */
fun readFrame(input: InputStream): ByteArray? {
    val header = ByteArray(4)
    var got = 0
    while (got < 4) {
        val n = input.read(header, got, 4 - got)
        if (n < 0) return null
        got += n
    }
    val size = ((header[0].toInt() and 0xFF) shl 24) or
        ((header[1].toInt() and 0xFF) shl 16) or
        ((header[2].toInt() and 0xFF) shl 8) or
        (header[3].toInt() and 0xFF)
    if (size <= 0 || size > MAX_FRAME) return null

    val body = ByteArray(size)
    var read = 0
    while (read < size) {
        val n = input.read(body, read, size - read)
        if (n < 0) return null
        read += n
    }
    return body
}

/**
 * Host side. Accepts one viewer and pushes frames to it.
 *
 * A dead viewer is not fatal. The host drops the connection, waits for the next
 * one, and keeps drawing its own half in the meantime.
 */
class FrameServer(private val scope: CoroutineScope, private val ports: Ports) {

    private var job: Job? = null

    @Volatile
    private var out: OutputStream? = null

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    var connected: Boolean = false
        private set

    fun start() {
        stop()
        job = scope.launch(Dispatchers.IO) {
            // One server socket for the whole session. Binding again between
            // viewers would leave a window where a reconnect is refused.
            val listening = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(ports.frames))
                }
            } catch (e: Exception) {
                Log.i(TAG, "cannot listen on ${ports.frames}: ${e.message}")
                return@launch
            }
            server = listening
            try {
                while (isActive) {
                    val s = try {
                        listening.accept()
                    } catch (_: Exception) {
                        break
                    }
                    s.tcpNoDelay = true
                    val stream = BufferedOutputStream(s.getOutputStream())
                    out = stream
                    connected = true
                    try {
                        // Hold the connection until a send fails or we stop.
                        while (isActive && out === stream) delay(100)
                    } finally {
                        out = null
                        connected = false
                        runCatching { s.close() }
                    }
                }
            } finally {
                server = null
                runCatching { listening.close() }
            }
        }
    }

    /** Push one frame. A failure drops the viewer; the next one reconnects. */
    fun send(frame: ByteArray) {
        val stream = out ?: return
        try {
            writeFrame(stream, frame)
        } catch (_: Exception) {
            // The viewer went away. The next one reconnects on its own.
            out = null
            connected = false
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        // Close the listener here rather than leaving it to the coroutine's
        // finally. Cancelling does not take effect straight away, and a new
        // session can reach bind() before the old socket has let go of the
        // port — which is the EADDRINUSE. Closing it from here is immediate
        // and cannot lose the race.
        runCatching { server?.close() }
        server = null
        runCatching { out?.close() }
        out = null
        connected = false
    }
}

/**
 * Viewer side. Reads frames until the host stops or the link drops.
 *
 * [onFrame] runs on a background thread, so it must not touch the user
 * interface directly.
 */
class FrameClient(private val scope: CoroutineScope, private val ports: Ports) {

    private var job: Job? = null

    fun connect(host: String, onFrame: (ByteArray) -> Unit, onLost: () -> Unit) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, ports.frames), 5000)
                    s.tcpNoDelay = true
                    val input = BufferedInputStream(s.getInputStream())
                    while (isActive) {
                        val frame = readFrame(input) ?: break
                        onFrame(frame)
                    }
                }
            } catch (_: Exception) {
            } finally {
                onLost()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
