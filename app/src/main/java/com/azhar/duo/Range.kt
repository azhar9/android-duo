package com.azhar.duo

/**
 * A byte range for an HTTP request. Both ends are inclusive, as the header
 * specifies them.
 */
data class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1
}

/**
 * Read a Range header against a resource of [total] bytes.
 *
 * Returns null when the header is malformed or the range cannot be satisfied.
 * The caller answers null with 416, which tells the player to start again.
 *
 * We serve one file, so a multi-range request is not worth honouring. The caller
 * treats it as an unsatisfiable range and the player asks again without one.
 */
fun parseRange(header: String?, total: Long): ByteRange? {
    if (total <= 0L) return null
    if (header == null) return ByteRange(0L, total - 1L)

    val h = header.trim()
    if (!h.startsWith("bytes=")) return null
    val spec = h.removePrefix("bytes=").trim()
    if (spec.isEmpty() || spec.contains(',')) return null

    val dash = spec.indexOf('-')
    if (dash < 0) return null
    val first = spec.substring(0, dash).trim()
    val last = spec.substring(dash + 1).trim()

    if (first.isEmpty()) {
        // "bytes=-N" means the last N bytes.
        val n = last.toLongOrNull() ?: return null
        if (n <= 0L) return null
        return ByteRange((total - n).coerceAtLeast(0L), total - 1L)
    }

    val start = first.toLongOrNull() ?: return null
    if (start < 0L || start >= total) return null

    val end = if (last.isEmpty()) total - 1L
    else (last.toLongOrNull() ?: return null).coerceAtMost(total - 1L)

    return if (end < start) null else ByteRange(start, end)
}

/**
 * The response headers for a range, or for the whole file when the player asked
 * for no range.
 */
fun rangeHeaders(range: ByteRange, total: Long, partial: Boolean): String = buildString {
    append("HTTP/1.1 ").append(if (partial) "206 Partial Content" else "200 OK").append("\r\n")
    append("Content-Type: video/mp4\r\n")
    append("Accept-Ranges: bytes\r\n")
    append("Content-Length: ").append(range.length).append("\r\n")
    if (partial) {
        append("Content-Range: bytes ")
        append(range.start).append('-').append(range.endInclusive)
        append('/').append(total).append("\r\n")
    }
    append("Connection: close\r\n\r\n")
}
