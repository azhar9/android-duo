package com.therealsoftware.duo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry is what makes two phones read as one screen, so it is the part
 * worth pinning down. These run on the JVM — World has no Android imports.
 */
class WorldTest {

    private fun pair(
        aw: Float = 437f,
        ah: Float = 915f,
        bw: Float = 442f,
        bh: Float = 905f,
        axis: Axis = Axis.Horizontal,
    ) = World().apply { layout(aw, ah, bw, bh, first = true, axis) } to
        World().apply { layout(aw, ah, bw, bh, first = false, axis) }

    // ------------------------------------------------------------- side by side

    @Test
    fun `slices are adjacent and together cover the whole canvas`() {
        val (first, second) = pair()
        assertEquals(879f, first.totalW, 0.001f)
        assertEquals(0f, first.sliceX, 0.001f)
        assertEquals(437f, first.sliceW, 0.001f)
        assertEquals(437f, second.sliceX, 0.001f)
        assertEquals(442f, second.sliceW, 0.001f)
        // No gap, no overlap — this is the seam.
        assertEquals(first.sliceX + first.sliceW, second.sliceX, 0.001f)
        assertEquals(first.totalW, second.sliceX + second.sliceW, 0.001f)
    }

    @Test
    fun `both devices derive identical geometry from the same four numbers`() {
        val (a, b) = pair()
        assertEquals(a.totalW, b.totalW, 0f)
        assertEquals(a.h, b.h, 0f)
    }

    @Test
    fun `side by side, the canvas height is the shorter phone`() {
        val (a, _) = pair(ah = 915f, bh = 905f)
        assertEquals(905f, a.h, 0.001f)
        assertEquals("each phone spans the full height", 905f, a.sliceH, 0.001f)
    }

    // ------------------------------------------------------------- stacked

    @Test
    fun `stacked, the canvas width is the narrower phone`() {
        val (a, b) = pair(axis = Axis.Vertical)
        assertEquals(437f, a.totalW, 0.001f)
        assertEquals(437f, b.totalW, 0.001f)
        assertEquals("each phone spans the full width", 437f, a.sliceW, 0.001f)
        assertEquals("heights add up", 1820f, a.h, 0.001f)
    }

    @Test
    fun `stacked, the slices run from top to bottom`() {
        val (top, bottom) = pair(axis = Axis.Vertical)
        assertEquals(0f, top.sliceY, 0.001f)
        assertEquals(915f, top.sliceH, 0.001f)
        assertEquals(915f, bottom.sliceY, 0.001f)
        assertEquals(905f, bottom.sliceH, 0.001f)
        assertEquals(top.sliceY + top.sliceH, bottom.sliceY, 0.001f)
        assertEquals(0f, top.sliceX, 0.001f)
        assertEquals(0f, bottom.sliceX, 0.001f)
    }

    @Test
    fun `stacked, the ball crosses the horizontal seam`() {
        val (top, _) = pair(axis = Axis.Vertical)
        top.applyRemote(200f, 900f, 0f, 1000f)   // heading down, just short of y=915
        top.step(0.05f)
        assertTrue("must enter the gap band", top.by > 915f)
        assertTrue("must not turn around at the gap", top.vy > 0f)
    }

    @Test
    fun `stacked, the ball bounces off the bottom of the combined canvas`() {
        val (top, _) = pair(axis = Axis.Vertical)
        top.applyRemote(200f, top.h - BALL_R - 1f, 0f, 5000f)
        top.step(0.01f)
        assertTrue(top.by <= top.h - BALL_R + 0.001f)
        assertTrue("must turn around", top.vy < 0f)
    }

    @Test
    fun `stacked, the gap separates the slices and lengthens the canvas`() {
        val (top, bottom) = pair(axis = Axis.Vertical)
        val gapDp = 4f * DP_PER_MM
        top.setGapMm(4f)
        bottom.setGapMm(4f)
        assertEquals(1820f + gapDp, top.h, 0.01f)
        assertEquals("both phones must agree", top.h, bottom.h, 0.001f)
        val hole = bottom.sliceY - (top.sliceY + top.sliceH)
        assertEquals(gapDp, hole, 0.01f)
        assertTrue("the bezels must hide a real band", hole > 0f)
        assertEquals("width must not move", 437f, top.totalW, 0.001f)
    }

    @Test
    fun `stacked, the ball stays inside the canvas`() {
        val (top, _) = pair(axis = Axis.Vertical)
        top.setGapMm(5f)
        top.applyRemote(100f, 100f, 9000f, 9000f)
        repeat(600) { top.step(1f / 60f) }
        assertTrue(top.bx in BALL_R..(top.totalW - BALL_R))
        assertTrue(top.by in BALL_R..(top.h - BALL_R))
    }

    @Test
    fun `switching axis keeps the ball inside`() {
        val (a, _) = pair()
        a.setGapMm(4f)
        a.applyRemote(800f, 800f, 500f, 500f)
        repeat(120) { a.step(1f / 60f) }
        a.layout(437f, 915f, 442f, 905f, first = true, Axis.Vertical)
        a.setGapMm(4f)
        assertTrue(a.bx in BALL_R..(a.totalW - BALL_R))
        assertTrue(a.by in BALL_R..(a.h - BALL_R))
    }

    // ------------------------------------------------------------- the gap

    @Test
    fun `the gap converts from millimetres`() {
        val (a, _) = pair()
        a.setGapMm(10f)
        assertEquals(10f * 160f / 25.4f, a.gap, 0.01f)   // about 63 dp
    }

    @Test
    fun `a negative gap is clamped to nothing`() {
        val (a, _) = pair()
        a.setGapMm(-5f)
        assertEquals(0f, a.gap, 0.001f)
    }

    @Test
    fun `the gap separates the two slices and widens the canvas`() {
        val (first, second) = pair()                     // 437 + 442 dp, no gap
        val gapDp = 4f * DP_PER_MM
        first.setGapMm(4f)
        second.setGapMm(4f)

        assertEquals(879f + gapDp, first.totalW, 0.01f)
        assertEquals("both phones must agree on the canvas", first.totalW, second.totalW, 0.001f)

        val hole = second.sliceX - (first.sliceX + first.sliceW)
        assertEquals(gapDp, hole, 0.01f)
        assertTrue("the bezels must hide a real band", hole > 0f)
    }

    @Test
    fun `the gap changes only the separation`() {
        val (first, second) = pair()
        val h0 = first.h
        val lw0 = first.sliceW
        val rw0 = second.sliceW
        first.setGapMm(5f)
        second.setGapMm(5f)
        assertEquals("height must not move", h0, first.h, 0.001f)
        assertEquals("the first screen is still 437dp", lw0, first.sliceW, 0.001f)
        assertEquals("the second screen is still 442dp", rw0, second.sliceW, 0.001f)
    }

    @Test
    fun `the ball crosses the gap without bouncing`() {
        val (a, _) = pair()
        a.setGapMm(4f)
        a.applyRemote(430f, 450f, 1000f, 0f)   // heading right, just short of x=437
        a.step(0.05f)
        assertTrue("must enter the gap band", a.bx > 437f)
        assertTrue("must not turn around at the gap", a.vx > 0f)
    }

    @Test
    fun `the ball stays inside the canvas with a gap set`() {
        val (a, _) = pair()
        a.setGapMm(6f)
        a.applyRemote(100f, 100f, 9000f, -9000f)
        repeat(600) { a.step(1f / 60f) }
        assertTrue(a.bx in BALL_R..(a.totalW - BALL_R))
        assertTrue(a.by in BALL_R..(a.h - BALL_R))
    }

    // ------------------------------------------------------------- the ball

    @Test
    fun `ball crosses the seam without bouncing`() {
        val (a, _) = pair()
        a.applyRemote(390f, 450f, 1000f, 0f)
        a.step(0.05f)
        assertTrue("should be past the seam at x=437", a.bx > 437f)
        assertTrue("should still be travelling right", a.vx > 0f)
    }

    @Test
    fun `ball bounces off the far edge of the combined canvas`() {
        val (a, _) = pair()
        a.applyRemote(a.totalW - BALL_R - 1f, 450f, 5000f, 0f)
        a.step(0.01f)
        assertTrue("must stay inside the canvas", a.bx <= a.totalW - BALL_R + 0.001f)
        assertTrue("must turn around, not sail off", a.vx < 0f)
    }

    @Test
    fun `ball never leaves the canvas`() {
        val (a, _) = pair()
        a.applyRemote(100f, 100f, 9000f, -9000f)
        repeat(600) { a.step(1f / 60f) }
        assertTrue(a.bx in BALL_R..(a.totalW - BALL_R))
        assertTrue(a.by in BALL_R..(a.h - BALL_R))
    }

    @Test
    fun `dragging past the edge pins the ball at the seam`() {
        val (a, _) = pair()
        a.touch(380f, 450f, true)
        a.touch(5000f, 450f, true)   // finger way past this phone's right edge
        assertEquals(a.totalW - BALL_R, a.bx, 0.001f)
    }

    @Test
    fun `releasing a drag flings the ball and the fling is speed capped`() {
        val (a, _) = pair()
        a.touch(300f, 450f, true)
        a.touch(340f, 450f, true)
        a.touch(380f, 450f, true)
        assertEquals(380f, a.bx, 0.001f)
        a.touch(380f, 450f, false)
        assertTrue("fling should carry right", a.vx > 0f)
        assertTrue("fling should be capped", a.vx <= 3000f + 0.001f)
    }

    // ------------------------------------------------------------- the picture

    @Test
    fun `side by side, the hidden band is exactly the gap`() {
        val (first, second) = pair()
        first.setGapMm(4f)
        second.setGapMm(4f)

        val u = 2.75f
        val viewW = first.sliceW * u
        val viewH = first.h * u
        val a = videoTransform(viewW, viewH, u, first.totalW, first.h, first.sliceX, 0f, 0f, 0f, 16f / 9f, Axis.Horizontal)
        val b = videoTransform(viewW, viewH, u, first.totalW, first.h, second.sliceX, 0f, 0f, 0f, 16f / 9f, Axis.Horizontal)

        val hidden = (0f - b.tx) - (first.sliceW * u - a.tx)
        assertEquals("the hidden band must equal the physical gap", 4f * DP_PER_MM * u, hidden, 0.01f)
    }

    @Test
    fun `stacked, the hidden band is exactly the gap`() {
        val (top, bottom) = pair(axis = Axis.Vertical)
        top.setGapMm(4f)
        bottom.setGapMm(4f)

        val u = 2.75f
        val viewW = top.totalW * u
        val a = videoTransform(viewW, top.sliceH * u, u, top.totalW, top.h, 0f, top.sliceY, 0f, 0f, 16f / 9f, Axis.Vertical)
        val b = videoTransform(viewW, bottom.sliceH * u, u, top.totalW, top.h, 0f, bottom.sliceY, 0f, 0f, 16f / 9f, Axis.Vertical)

        val hidden = (0f - b.ty) - (top.sliceH * u - a.ty)
        assertEquals("the hidden band must equal the physical gap", 4f * DP_PER_MM * u, hidden, 0.01f)
    }

    @Test
    fun `each phone shows its own window onto the picture`() {
        val (first, second) = pair()
        first.setGapMm(4f)
        second.setGapMm(4f)
        val u = 2.25f
        val a = videoTransform(first.sliceW * u, first.h * u, u, first.totalW, first.h, first.sliceX, 0f, 0f, 0f, 16f / 9f, Axis.Horizontal)
        val b = videoTransform(second.sliceW * u, first.h * u, u, first.totalW, first.h, second.sliceX, 0f, 0f, 0f, 16f / 9f, Axis.Horizontal)

        assertEquals("the first phone starts at the origin", 0f, (0f - a.tx) / u, 0.01f)
        assertEquals("the second starts at its own slice", second.sliceX, (0f - b.tx) / u, 0.01f)
    }

    @Test
    fun `a wide picture is letterboxed when the phones are side by side`() {
        val (a, _) = pair()
        val u = 2.25f
        val padY = 60f
        val t = videoTransform(a.sliceW * u, a.h * u, u, a.totalW, a.h, a.sliceX, 0f, 0f, padY, 16f / 9f, Axis.Horizontal)
        val th = a.totalW * u / (16f / 9f)
        val above = t.ty - padY
        val below = (padY + a.h * u) - (t.ty + th)
        assertTrue("a 16:9 clip fits inside the band", above > 0f)
        assertEquals("equal margins above and below", above, below, 0.01f)
    }

    @Test
    fun `stacked, the picture fills the height and is pillarboxed`() {
        val (a, _) = pair(axis = Axis.Vertical)
        val u = 2.25f
        val padX = 40f
        val t = videoTransform(a.totalW * u, a.sliceH * u, u, a.totalW, a.h, 0f, a.sliceY, padX, 0f, 16f / 9f, Axis.Vertical)
        val tw = a.h * u * (16f / 9f)
        assertEquals("the picture spans the whole canvas height", tw, a.totalW * u * 0f + tw, 0.01f)
        assertTrue("a 16:9 clip is wider than two stacked phones", tw > a.totalW * u)
        val left = t.tx - padX
        val right = (padX + a.totalW * u) - (t.tx + tw)
        assertTrue("it overflows evenly on both sides", left < 0f && right < 0f)
        assertEquals("equal overflow left and right", left, right, 0.01f)
    }

    // ------------------------------------------------------------- the peer slice

    @Test
    fun `side by side, the peer slice is the other half`() {
        val (a, b) = pair()
        assertEquals(b.sliceX, a.peerX, 0.001f)
        assertEquals(b.sliceW, a.peerW, 0.001f)
        assertEquals(a.sliceX, b.peerX, 0.001f)
        assertEquals(a.sliceW, b.peerW, 0.001f)
        assertEquals("the peer spans the full height", 0f, a.peerY, 0.001f)
        assertEquals(a.h, a.peerH, 0.001f)
    }

    @Test
    fun `stacked, the peer slice is the other half`() {
        val (a, b) = pair(axis = Axis.Vertical)
        assertEquals(b.sliceY, a.peerY, 0.001f)
        assertEquals(b.sliceH, a.peerH, 0.001f)
        assertEquals(a.sliceY, b.peerY, 0.001f)
        assertEquals(a.sliceH, b.peerH, 0.001f)
        assertEquals("the peer spans the full width", 0f, a.peerX, 0.001f)
        assertEquals(a.totalW, a.peerW, 0.001f)
    }

    @Test
    fun `side by side, the peer slice skips the gap`() {
        val (a, b) = pair()
        a.setGapMm(5f)
        b.setGapMm(5f)
        assertEquals(b.sliceX, a.peerX, 0.001f)
        assertEquals(b.sliceW, a.peerW, 0.001f)
        // mine, the gap, and the peer's must cover the canvas exactly once.
        assertEquals(a.totalW, a.sliceW + a.gap + a.peerW, 0.01f)
    }

    @Test
    fun `stacked, the peer slice skips the gap`() {
        val (a, b) = pair(axis = Axis.Vertical)
        a.setGapMm(5f)
        b.setGapMm(5f)
        assertEquals(b.sliceY, a.peerY, 0.001f)
        assertEquals(b.sliceH, a.peerH, 0.001f)
        assertEquals(a.h, a.sliceH + a.gap + a.peerH, 0.01f)
    }

    private fun gapDp(mm: Float) = mm * DP_PER_MM
}
