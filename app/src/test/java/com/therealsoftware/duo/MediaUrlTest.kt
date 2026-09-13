package com.therealsoftware.duo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The address the other phone streams from carries the name of the clip.
 *
 * The receiving phone caches what it downloads under that address. When the
 * address was fixed, it kept the first clip it ever fetched and played that
 * one for every clip after it — with the right name on screen and the wrong
 * pictures. These checks are the whole of that rule.
 */
class MediaUrlTest {

    @Test
    fun aDifferentClipIsADifferentAddress() {
        val one = mediaUrl("10.0.0.5", 8902, "111")
        val two = mediaUrl("10.0.0.5", 8902, "222")

        assertNotEquals(one, two)
    }

    @Test
    fun theSameClipIsTheSameAddress() {
        assertEquals(mediaUrl("10.0.0.5", 8902, "111"), mediaUrl("10.0.0.5", 8902, "111"))
    }

    /** Whatever the address carries, the server still has to recognise it. */
    @Test
    fun theServerStillRecognisesTheAddress() {
        val target = mediaUrl("10.0.0.5", 8902, "111").substringAfter(":8902")

        assertEquals(MEDIA_PATH + "?c=111", target)
        assertEquals(MEDIA_PATH, requestPath(target))
        assertEquals(MEDIA_PATH, requestPath(MEDIA_PATH))
    }
}
