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

    // ---------------------------------------------------------------- the gap

    @Test
    fun `the gap converts from millimetres`() {
        val (left, _) = pair()
        left.setGapMm(10f)
        assertEquals(10f * 160f / 25.4f, left.gap, 0.01f)   // about 63 dp
    }

    @Test
    fun `a negative gap is clamped to nothing`() {
        val (left, _) = pair()
        left.setGapMm(-5f)
        assertEquals(0f, left.gap, 0.001f)
    }

    @Test
    fun `the gap separates the two slices and widens the canvas`() {
        val (left, right) = pair()                     // 437 + 442 dp, no gap
        val gapDp = 4f * DP_PER_MM
        left.setGapMm(4f)
        right.setGapMm(4f)

        assertEquals(879f + gapDp, left.totalW, 0.01f)
        assertEquals("both phones must agree on the canvas", left.totalW, right.totalW, 0.001f)

        // The slices no longer touch. That hole is the physical gap.
        val hole = right.sliceX - (left.sliceX + left.sliceW)
        assertEquals(gapDp, hole, 0.01f)
        assertTrue("the bezels must hide a real band", hole > 0f)
    }

    @Test
    fun `the gap changes only the separation`() {
        val (left, right) = pair()
        val h0 = left.h
        val lw0 = left.sliceW
        val rw0 = right.sliceW
        left.setGapMm(5f)
        right.setGapMm(5f)
        assertEquals("height must not move", h0, left.h, 0.001f)
        assertEquals("the left screen is still 437dp", lw0, left.sliceW, 0.001f)
        assertEquals("the right screen is still 442dp", rw0, right.sliceW, 0.001f)
    }

    @Test
    fun `the ball crosses the gap without bouncing`() {
        val (left, _) = pair()
        left.setGapMm(4f)
        left.applyRemote(430f, 450f, 1000f, 0f)   // heading right, just short of x=437
        left.step(0.05f)
        assertTrue("must enter the gap band", left.bx > 437f)
        assertTrue("must not turn around at the gap", left.vx > 0f)
    }

    @Test
    fun `the ball stays inside the canvas with a gap set`() {
        val (left, _) = pair()
        left.setGapMm(6f)
        left.applyRemote(100f, 100f, 9000f, -9000f)
        repeat(600) { left.step(1f / 60f) }
        assertTrue(left.bx in BALL_R..(left.totalW - BALL_R))
        assertTrue(left.by in BALL_R..(left.h - BALL_R))
    }

    // ---------------------------------------------------------------- video

    @Test
    fun `the video band behind the bezels is exactly the gap`() {
        // A 16:9 clip on the two phones, with a 4mm gap between the panels.
        val (left, right) = pair()
        left.setGapMm(4f)
        right.setGapMm(4f)

        val u = 2.75f          // px for each logical unit, as a real phone would use
        val viewW = left.sliceW * u
        val viewH = left.h * u

        val a = videoTransform(viewW, viewH, u, left.totalW, left.h, left.sliceX, 0f, 16f / 9f)
        val b = videoTransform(viewW, viewH, u, left.totalW, right.h, right.sliceX, 0f, 16f / 9f)

        // In surface coordinates the video starts at tx. A point at surface x
        // shows video pixel (x - tx). Compare the two phones at the seam.
        val hostAtSeam = left.sliceW * u - a.tx
        val clientAtSeam = 0f - b.tx
        val hidden = clientAtSeam - hostAtSeam

        assertEquals("the hidden band must equal the physical gap", gapDp(4f) * u, hidden, 0.01f)
    }

    @Test
    fun `the host draws the video from its own left edge`() {
        val (left, _) = pair()
        val u = 2.75f
        val t = videoTransform(left.sliceW * u, left.h * u, u, left.totalW, left.h, left.sliceX, 0f, 16f / 9f)
        assertEquals("the host slice starts at the video origin", 0f, t.tx, 0.001f)
    }

    @Test
    fun `the video fills the full logical width`() {
        val (left, _) = pair()
        val u = 2.25f
        val t = videoTransform(left.sliceW * u, left.h * u, u, left.totalW, left.h, left.sliceX, 0f, 16f / 9f)
        val tw = left.totalW * u
        // The surface is sliceW wide. After the scale it must hold the whole canvas.
        assertEquals("the video must span the whole canvas", tw, left.sliceW * u * t.scaleX, 0.01f)
    }

    @Test
    fun `each phone shows its own window onto the video`() {
        val (left, right) = pair()
        left.setGapMm(4f)
        right.setGapMm(4f)
        val u = 2.25f
        val a = videoTransform(left.sliceW * u, left.h * u, u, left.totalW, left.h, left.sliceX, 0f, 16f / 9f)
        val b = videoTransform(right.sliceW * u, right.h * u, u, left.totalW, right.h, right.sliceX, 0f, 16f / 9f)

        // The video sits at tx in surface coordinates. Undo the shift and the
        // scale: the left edge of each surface must land on that phone's sliceX.
        assertEquals("the host starts at the video origin", 0f, (0f - a.tx) / u, 0.01f)
        assertEquals("the client starts at its own slice", right.sliceX, (0f - b.tx) / u, 0.01f)
    }

    @Test
    fun `the video is centred in the logical band`() {
        val (left, _) = pair()
        val u = 2.25f
        val padY = 60f
        val t = videoTransform(left.sliceW * u, left.h * u, u, left.totalW, left.h, left.sliceX, padY, 16f / 9f)
        val th = left.totalW * u / (16f / 9f)
        val above = t.ty - padY
        val below = (padY + left.h * u) - (t.ty + th)
        assertTrue("a 16:9 clip fits inside the band", above > 0f)
        assertEquals("equal margins above and below", above, below, 0.01f)
    }

    private fun gapDp(mm: Float) = mm * DP_PER_MM
}
