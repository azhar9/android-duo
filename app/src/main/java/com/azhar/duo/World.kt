package com.azhar.duo

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Ball radius, in logical units. */
const val BALL_R = 26f

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
 * Deliberately free of Android imports so the geometry and physics can be tested
 * on the JVM (see WorldTest).
 */
class World {

    var totalW = 0f; private set
    var h = 0f; private set

    /** This device's horizontal slice of the logical canvas. */
    var sliceX = 0f; private set
    var sliceW = 0f; private set

    var bx = 0f; private set
    var by = 0f; private set
    var vx = 0f; private set
    var vy = 0f; private set

    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L

    /**
     * Both devices call this with the same four numbers and their own side, so
     * both derive identical geometry without trading a layout.
     */
    fun layout(aW: Float, aH: Float, bW: Float, bH: Float, amLeft: Boolean) {
        totalW = aW + bW
        h = min(aH, bH)
        if (amLeft) { sliceX = 0f; sliceW = aW } else { sliceX = aW; sliceW = bW }
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
