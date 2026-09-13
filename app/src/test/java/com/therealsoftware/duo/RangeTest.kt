package com.therealsoftware.duo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The other phone seeks by asking for byte ranges. A wrong answer here shows up
 * as a video that will not seek, or one that seeks to the wrong place.
 */
class RangeTest {

    private val total = 1000L

    @Test
    fun `no header means the whole file`() {
        val r = parseRange(null, total)!!
        assertEquals(0L, r.start)
        assertEquals(999L, r.endInclusive)
        assertEquals(1000L, r.length)
    }

    @Test
    fun `a closed range is honoured`() {
        val r = parseRange("bytes=200-499", total)!!
        assertEquals(200L, r.start)
        assertEquals(499L, r.endInclusive)
        assertEquals(300L, r.length)
    }

    @Test
    fun `an open range runs to the end`() {
        val r = parseRange("bytes=900-", total)!!
        assertEquals(900L, r.start)
        assertEquals(999L, r.endInclusive)
        assertEquals(100L, r.length)
    }

    @Test
    fun `a suffix range counts back from the end`() {
        val r = parseRange("bytes=-250", total)!!
        assertEquals(750L, r.start)
        assertEquals(999L, r.endInclusive)
        assertEquals(250L, r.length)
    }

    @Test
    fun `a suffix longer than the file gives the whole file`() {
        val r = parseRange("bytes=-5000", total)!!
        assertEquals(0L, r.start)
        assertEquals(999L, r.endInclusive)
    }

    @Test
    fun `an end past the file is clipped`() {
        val r = parseRange("bytes=900-99999", total)!!
        assertEquals(900L, r.start)
        assertEquals(999L, r.endInclusive)
    }

    @Test
    fun `a start past the file cannot be satisfied`() {
        assertNull(parseRange("bytes=1000-1200", total))
        assertNull(parseRange("bytes=5000-", total))
    }

    @Test
    fun `a reversed range cannot be satisfied`() {
        assertNull(parseRange("bytes=500-200", total))
    }

    @Test
    fun `rubbish cannot be satisfied`() {
        assertNull(parseRange("items=0-10", total))
        assertNull(parseRange("bytes=", total))
        assertNull(parseRange("bytes=-", total))
        assertNull(parseRange("bytes=abc-def", total))
        assertNull(parseRange("bytes=0-10,20-30", total))
        assertNull(parseRange("bytes=0", total))
    }

    @Test
    fun `an empty file cannot be satisfied`() {
        assertNull(parseRange(null, 0L))
        assertNull(parseRange("bytes=0-", 0L))
    }

    @Test
    fun `every range stays inside the file`() {
        val headers = listOf(
            null, "bytes=0-0", "bytes=0-", "bytes=-1", "bytes=-1000",
            "bytes=999-999", "bytes=1-2", "bytes=500-1500",
        )
        for (h in headers) {
            val r = parseRange(h, total)
            assertTrue("$h should parse", r != null)
            assertTrue("$h start", r!!.start >= 0L)
            assertTrue("$h end", r.endInclusive <= total - 1L)
            assertTrue("$h non-empty", r.length > 0L)
        }
    }

    @Test
    fun `a partial response carries its content range`() {
        val h = rangeHeaders(ByteRange(200L, 499L), total, partial = true)
        assertTrue(h.startsWith("HTTP/1.1 206 Partial Content"))
        assertTrue(h.contains("Content-Range: bytes 200-499/1000"))
        assertTrue(h.contains("Content-Length: 300"))
        assertTrue(h.contains("Accept-Ranges: bytes"))
    }

    @Test
    fun `a whole response carries no content range`() {
        val h = rangeHeaders(ByteRange(0L, 999L), total, partial = false)
        assertTrue(h.startsWith("HTTP/1.1 200 OK"))
        assertTrue(!h.contains("Content-Range"))
        assertTrue(h.contains("Content-Length: 1000"))
    }
}
