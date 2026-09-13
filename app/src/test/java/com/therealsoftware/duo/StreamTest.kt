package com.therealsoftware.duo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * A socket hands over bytes in arbitrary pieces. The length header is the only
 * thing that lets the reader tell a whole frame from part of one, so it is worth
 * pinning down.
 */
class StreamTest {

    private fun frame(size: Int, seed: Int = 0) =
        ByteArray(size) { ((it * 31 + seed) % 251).toByte() }

    private fun roundTrip(vararg frames: ByteArray): List<ByteArray?> {
        val buf = ByteArrayOutputStream()
        frames.forEach { writeFrame(buf, it) }
        val input = ByteArrayInputStream(buf.toByteArray())
        return frames.map { readFrame(input) }
    }

    @Test
    fun `a frame survives a round trip`() {
        val jpeg = frame(5000)
        assertArrayEquals(jpeg, roundTrip(jpeg)[0])
    }

    @Test
    fun `several frames arrive in order`() {
        val a = frame(1200, 1)
        val b = frame(37, 2)
        val c = frame(200000, 3)
        val back = roundTrip(a, b, c)
        assertArrayEquals(a, back[0])
        assertArrayEquals(b, back[1])
        assertArrayEquals(c, back[2])
    }

    @Test
    fun `a one byte frame works`() {
        val tiny = byteArrayOf(7)
        assertArrayEquals(tiny, roundTrip(tiny)[0])
    }

    @Test
    fun `the stream ends cleanly after the last frame`() {
        val buf = ByteArrayOutputStream()
        writeFrame(buf, frame(100))
        val input = ByteArrayInputStream(buf.toByteArray())
        assertEquals(100, readFrame(input)!!.size)
        assertNull("a second read must report the end", readFrame(input))
    }

    @Test
    fun `a truncated header gives up`() {
        assertNull(readFrame(ByteArrayInputStream(byteArrayOf(0, 0))))
    }

    @Test
    fun `a truncated body gives up instead of hanging`() {
        val buf = ByteArrayOutputStream()
        writeFrame(buf, frame(4000))
        val cut = buf.toByteArray().copyOf(1500)
        assertNull(readFrame(ByteArrayInputStream(cut)))
    }

    @Test
    fun `an absurd length is rejected`() {
        // Claims 2 GB. Must not try to allocate it.
        val bad = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertNull(readFrame(ByteArrayInputStream(bad)))
    }

    @Test
    fun `a zero length is rejected`() {
        assertNull(readFrame(ByteArrayInputStream(byteArrayOf(0, 0, 0, 0))))
    }

    @Test
    fun `a negative length is rejected`() {
        assertNull(readFrame(ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0, 0, 0))))
    }

    @Test
    fun `a drip fed stream still reassembles the frame`() {
        // One byte at a time: the worst case a real socket can hand us.
        val jpeg = frame(3000, 9)
        val buf = ByteArrayOutputStream()
        writeFrame(buf, jpeg)
        val raw = buf.toByteArray()
        var at = 0
        val drip = object : InputStream() {
            override fun read(): Int = if (at < raw.size) raw[at++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (at >= raw.size) return -1
                b[off] = raw[at++]
                return 1
            }
        }
        assertArrayEquals(jpeg, readFrame(drip))
    }
}
