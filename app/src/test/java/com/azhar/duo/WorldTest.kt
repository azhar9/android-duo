package com.azhar.duo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry is what makes two phones read as one screen, so it is the part
 * worth pinning down. These run on the JVM — World has no Android imports.
 */
class WorldTest {

    private fun pair(aw: Float = 437f, ah: Float = 915f, bw: Float = 442f, bh: Float = 905f) =
        World().apply { layout(aw, ah, bw, bh, amLeft = true) } to
            World().apply { layout(aw, ah, bw, bh, amLeft = false) }

    @Test
    fun `slices are adjacent and together cover the whole canvas`() {
        val (left, right) = pair()
        assertEquals(879f, left.totalW, 0.001f)
        assertEquals(0f, left.sliceX, 0.001f)
        assertEquals(437f, left.sliceW, 0.001f)
        assertEquals(437f, right.sliceX, 0.001f)
        assertEquals(442f, right.sliceW, 0.001f)
        // No gap, no overlap — this is the seam.
        assertEquals(left.sliceX + left.sliceW, right.sliceX, 0.001f)
        assertEquals(left.totalW, right.sliceX + right.sliceW, 0.001f)
    }

    @Test
    fun `both devices derive identical geometry from the same four numbers`() {
        val (left, right) = pair()
        assertEquals(left.totalW, right.totalW, 0f)
        assertEquals(left.h, right.h, 0f)
    }

    @Test
    fun `canvas height is the shorter phone`() {
        val (left, _) = pair(ah = 915f, bh = 905f)
        assertEquals(905f, left.h, 0.001f)
    }

    @Test
    fun `ball crosses the seam without bouncing`() {
        val (left, _) = pair()
        left.applyRemote(390f, 450f, 1000f, 0f)   // just left of the seam, heading right
        left.step(0.05f)
        assertTrue("should be past the seam at x=437", left.bx > 437f)
        assertTrue("should still be travelling right", left.vx > 0f)
    }

    @Test
    fun `ball bounces off the far edge of the combined canvas`() {
        val (left, _) = pair()
        left.applyRemote(left.totalW - BALL_R - 1f, 450f, 5000f, 0f)
        left.step(0.01f)
        assertTrue("must stay inside the canvas", left.bx <= left.totalW - BALL_R + 0.001f)
        assertTrue("must turn around, not sail off", left.vx < 0f)
    }

    @Test
    fun `ball never leaves the canvas`() {
        val (left, _) = pair()
        left.applyRemote(100f, 100f, 9000f, -9000f)
        repeat(600) { left.step(1f / 60f) }
        assertTrue(left.bx in BALL_R..(left.totalW - BALL_R))
        assertTrue(left.by in BALL_R..(left.h - BALL_R))
    }

    @Test
    fun `dragging past the edge pins the ball at the seam`() {
        val (left, _) = pair()
        left.touch(380f, 450f, true)
        left.touch(5000f, 450f, true)   // finger way past this phone's right edge
        assertEquals(left.totalW - BALL_R, left.bx, 0.001f)
    }

    @Test
    fun `releasing a drag flings the ball and the fling is speed capped`() {
        val (left, _) = pair()
        left.touch(300f, 450f, true)
        left.touch(340f, 450f, true)
        left.touch(380f, 450f, true)
        assertEquals(380f, left.bx, 0.001f)
        left.touch(380f, 450f, false)
        assertTrue("fling should carry right", left.vx > 0f)
        assertTrue("fling should be capped", left.vx <= 3000f + 0.001f)
    }
}
