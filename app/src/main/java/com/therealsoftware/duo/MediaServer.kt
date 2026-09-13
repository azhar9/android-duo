package com.therealsoftware.duo

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** The path the other phone asks for. */
const val MEDIA_PATH = "/video"

/**
 * The address the other phone streams from.
 *
 * [token] names the clip, and it has to change when the clip changes. The
 * receiving phone caches what it downloads under this address, so a fixed
 * address means it replays the first clip it ever fetched and never the one
 * being served now.
 */
fun mediaUrl(host: String, port: Int, token: String): String =
    "http://$host:$port$MEDIA_PATH?c=$token"

/** The request line's path, with any query taken off. */
fun requestPath(target: String): String = target.substringBefore('?')

private class Request(val method: String, val path: String, val headers: Map<String, String>)

/**
 * Serves one picked video to the other phone over HTTP.
 *
 * The other phone does not copy the file first. Its player asks for byte ranges
 * as it needs them, so playback starts at once and seeking works — the same
 * thing any streaming service does, on the local network.
 *
 * The picker hands back a `content://` address, not a path. Most providers give
 * a real file descriptor we can seek in. When one does not, the file is copied
 * into the cache once and served from there.
 */
class MediaServer(context: Context, private val scope: CoroutineScope, private val ports: Ports) {

    private val appContext = context.applicationContext

    private var job: Job? = null
    private var server: ServerSocket? = null
    private var uri: Uri? = null
    private var cached: File? = null
    private var length = 0L

    /** The display name of the clip being served, or empty. */
    var name: String = ""
        private set

    /** True when a clip is ready to serve. */
    val ready: Boolean get() = length > 0L

    /**
     * Start serving [u]. Returns true when the clip is ready. A false result
     * means the other phone cannot stream from this one.
     */
    suspend fun start(u: Uri, displayName: String): Boolean {
        stop()
        if (!prepare(u)) return false
        name = displayName
        job = scope.launch(Dispatchers.IO) {
            val listening = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(ports.media))
                }
            } catch (_: Exception) {
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
                    launch(Dispatchers.IO) { runCatching { serve(s) } }
                }
            } finally {
                server = null
                runCatching { listening.close() }
            }
        }
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
        // Closed here, not in the coroutine, for the same reason as the frame
        // server: a new session must not reach bind() while this one still
        // holds the port.
        runCatching { server?.close() }
        server = null
        uri = null
        cached = null
        length = 0L
        name = ""
    }

    /** Work out how to read the file, and how long it is. */
    private suspend fun prepare(u: Uri): Boolean = withContext(Dispatchers.IO) {
        val size = runCatching {
            appContext.contentResolver.openFileDescriptor(u, "r")?.use { it.statSize }
        }.getOrNull() ?: -1L

        if (size > 0L) {
            uri = u
            cached = null
            length = size
            return@withContext true
        }

        // This provider does not report a size. Copy once so ranges still work.
        val f = File(appContext.cacheDir, "stream.bin")
        runCatching {
            appContext.contentResolver.openInputStream(u)?.use { input ->
                f.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
            }
            if (f.length() > 0L) {
                uri = null
                cached = f
                length = f.length()
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    private fun serve(sock: Socket) {
        sock.use { s ->
            val input = BufferedInputStream(s.getInputStream())
            val out = BufferedOutputStream(s.getOutputStream())

            val req = readRequest(input)
            if (req == null || req.path != MEDIA_PATH || length <= 0L) {
                out.write(
                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray()
                )
                out.flush()
                return
            }

            val range = parseRange(req.headers["range"], length)
            if (range == null) {
                // Tell the player how long the file really is. It asks again.
                out.write(
                    ("HTTP/1.1 416 Range Not Satisfiable\r\n" +
                        "Content-Range: bytes */" + length + "\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()
                )
                out.flush()
                return
            }

            out.write(rangeHeaders(range, length, partial = req.headers["range"] != null).toByteArray())
            if (req.method != "HEAD") send(range, out)
            out.flush()
        }
    }

    private fun send(range: ByteRange, out: OutputStream) {
        val cachedFile = cached
        if (cachedFile != null) {
            FileInputStream(cachedFile).use { pipe(it, range, out) }
            return
        }
        val u = uri ?: return
        val pfd = appContext.contentResolver.openFileDescriptor(u, "r") ?: return
        try {
            // Closing the descriptor closes the stream with it.
            pipe(FileInputStream(pfd.fileDescriptor), range, out)
        } finally {
            runCatching { pfd.close() }
        }
    }

    private fun pipe(ins: InputStream, range: ByteRange, out: OutputStream) {
        var skipped = 0L
        while (skipped < range.start) {
            val n = ins.skip(range.start - skipped)
            if (n <= 0L) break
            skipped += n
        }
        val buf = ByteArray(256 * 1024)
        var remaining = range.length
        while (remaining > 0L) {
            val want = minOf(buf.size.toLong(), remaining).toInt()
            val n = ins.read(buf, 0, want)
            if (n <= 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        return Request(parts[0].trim().uppercase(), requestPath(parts[1].trim()), headers)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }
}
