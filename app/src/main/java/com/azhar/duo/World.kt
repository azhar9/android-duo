package com.azhar.duo

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Ball radius, in logical units. */
const val BALL_R = 26f

/** Logical units for each millimetre. The gap between the phones is measured in mm. */
const val DP_PER_MM = 160f / 25.4f

private const val FRICTION = 1.1f      // velocity decay per second
private const val BOUNCE = 0.93f       // energy kept off a wall
private const val MAX_SPEED = 3000f    // logical units per second

/**
 * The single logical canvas that both phones are halves of.
 *
 * Units are dp, so a shape comes out the same physical size on both phones with
 * no calibration maths — that is the whole trick. Origin is the top-left of the
 * combined screen; the vertical band is centred on the shorter phone.
 *
 * The two screens do not touch. A real gap sits between them, and the bezels
 * hide that band of the canvas. [setGapMm] records the width of that band, so
 * the picture continues correctly across the physical gap instead of jumping.
 *
 * Deliberately free of Android imports so the geometry and physics can be tested
 * on the JVM (see WorldTest).
 */
class World {

    var totalW = 0f; private set
    var h = 0f; private set

    /** This device's horizontal slice of the logical canvas. */
    var sliceX = 0f; private set
    var sliceW = 0f; private set

    /** The band between the two screens. No phone draws this part. */
    var gap = 0f; private set

    var bx = 0f; private set
    var by = 0f; private set
    var vx = 0f; private set
    var vy = 0f; private set

    // The raw layout, kept so setGapMm can recompute without another handshake.
    private var aw = 0f
    private var ah = 0f
    private var bw = 0f
    private var bh = 0f
    private var amLeft = true
    private var laid = false

    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L

    /**
     * Both devices call this with the same four numbers and their own side, so
     * both derive identical geometry without trading a layout.
     */
    fun layout(aW: Float, aH: Float, bW: Float, bH: Float, amLeft: Boolean) {
        aw = aW; ah = aH; bw = bW; bh = bH; this.amLeft = amLeft; laid = true
        recompute()
    }

    /** Set the physical gap between the panels. Both phones must use one value. */
    fun setGapMm(mm: Float) {
        gap = (mm * DP_PER_MM).coerceAtLeast(0f)
        recompute()
    }

    private fun recompute() {
        if (!laid) return
        h = min(ah, bh)
        totalW = aw + gap + bw
        sliceX = if (amLeft) 0f else aw + gap
        sliceW = if (amLeft) aw else bw
        bx = clampX(bx)
        by = clampY(by)
    }

    fun place(x: Float, y: Float) { bx = clampX(x); by = clampY(y) }

    /** Client-side: adopt the host's authoritative ball. */
    fun applyRemote(x: Float, y: Float, nvx: Float, nvy: Float) {
        bx = x; by = y; vx = nvx; vy = nvy
    }

    /** Host-side: advance the simulation. */
    fun step(dt: Float) {
        if (dragging) return
        val damp = (1f - FRICTION * dt).coerceIn(0f, 1f)
        vx *= damp
        vy *= damp
        bx += vx * dt
        by += vy * dt
        bounce()
    }

    /**
     * Host-side. [x],[y] are logical coords of a touch on *either* phone — the
     * client forwards its raw touches here over the wire.
     *
     * The ball is pinned to the finger while down. That means dragging past your
     * own screen edge just pins it at the seam while velocity keeps building, so
     * releasing flings it across onto the other phone.
     */
    fun touch(x: Float, y: Float, down: Boolean) {
        val now = System.nanoTime()
        val dt = ((now - lastT) / 1e9f).coerceIn(0.004f, 0.1f)
        if (down) {
            if (dragging) {
                // Smoothed finite difference — raw touch deltas are far too jittery to fling with.
                vx = vx * 0.4f + (x - lastX) / dt * 0.6f
                vy = vy * 0.4f + (y - lastY) / dt * 0.6f
            }
            bx = clampX(x)
            by = clampY(y)
            dragging = true
        } else {
            dragging = false
            val sp = hypot(vx, vy)
            if (sp > MAX_SPEED) { vx *= MAX_SPEED / sp; vy *= MAX_SPEED / sp }
        }
        lastX = x; lastY = y; lastT = now
    }

    /** The ball crosses the gap without help. Only the outer edges are walls. */
    private fun bounce() {
        if (bx < BALL_R) { bx = BALL_R; vx = abs(vx) * BOUNCE }
        if (bx > maxX()) { bx = maxX(); vx = -abs(vx) * BOUNCE }
        if (by < BALL_R) { by = BALL_R; vy = abs(vy) * BOUNCE }
        if (by > maxY()) { by = maxY(); vy = -abs(vy) * BOUNCE }
    }

    private fun maxX() = (totalW - BALL_R).coerceAtLeast(BALL_R)
    private fun maxY() = (h - BALL_R).coerceAtLeast(BALL_R)
    private fun clampX(x: Float) = x.coerceIn(BALL_R, maxX())
    private fun clampY(y: Float) = y.coerceIn(BALL_R, maxY())
}

/**
 * How to place a video inside a full-screen surface, so that this phone shows
 * its own slice of the canvas and nothing else.
 *
 * The video always fills the full logical width. That is what makes the picture
 * continuous across the join, and it is why the band behind the bezels comes
 * out of the video rather than out of the geometry.
 */
data class VideoTransform(
    val scaleX: Float,
    val scaleY: Float,
    val tx: Float,
    val ty: Float,
)

/**
 * [viewW],[viewH] is the surface size in px. [u] is px for each logical unit.
 * [padY] is the top of the logical band inside the surface. [videoAspect] is
 * width divided by height.
 *
 * A video that fills the width without filling the height is centred, so it is
 * letterboxed. A tall video overflows top and bottom, so it is cropped.
 */
fun videoTransform(
    viewW: Float,
    viewH: Float,
    u: Float,
    totalW: Float,
    h: Float,
    sliceX: Float,
    padY: Float,
    videoAspect: Float,
): VideoTransform {
    val tw = totalW * u
    val th = tw / videoAspect
    return VideoTransform(
        scaleX = tw / viewW,
        scaleY = th / viewH,
        tx = -sliceX * u,
        ty = padY + (h * u - th) / 2f,
    )
}
