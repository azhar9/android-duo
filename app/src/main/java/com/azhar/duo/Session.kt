package com.azhar.duo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class Phase { Menu, Waiting, Live, Dead }

/**
 * Wires the transport to the shared canvas.
 *
 * The host is authoritative: it owns the ball, runs the physics, and broadcasts
 * position at frame rate. The client forwards its raw touches and renders whatever
 * the host says. One round trip on a hotspot is a few ms, so the client's ball
 * tracks its own finger closely enough that nobody notices.
 */
class Session(private val scope: CoroutineScope) {

    val world = World()
    private var link = Link(scope)
    private var collectJob: Job? = null

    var phase by mutableStateOf(Phase.Menu); private set
    var isHost by mutableStateOf(false); private set
    var note by mutableStateOf(""); private set

    /**
     * Calibration knob. Above 1.0 this phone's slice covers fewer logical units, so
     * content renders larger. Both phones must agree by eye — OEMs report dp
     * densities that don't match the real panel, and MIUI lets the user change it.
     */
    var calib by mutableFloatStateOf(1f)

    private var myW = 0f
    private var myH = 0f

    init {
        wire()
    }

    fun startHost(w: Float, h: Float) {
        myW = w; myH = h
        isHost = true
        note = "waiting for the other phone…"
        phase = Phase.Waiting
        link.host()
    }

    fun startJoin(w: Float, h: Float) {
        myW = w; myH = h
        isHost = false
        note = "looking for host…"
        phase = Phase.Waiting
        link.join()
    }

    fun reset() {
        link.close()
        link = Link(scope)
        wire()
        note = ""
        phase = Phase.Menu
    }

    fun touch(x: Float, y: Float, down: Boolean) {
        if (phase != Phase.Live) return
        if (isHost) {
            world.touch(x, y, down)
        } else {
            link.send(
                JSONObject().put("t", "touch")
                    .put("x", x.toDouble()).put("y", y.toDouble()).put("d", down)
            )
        }
    }

    /** Called once per frame. Host simulates and broadcasts; client is a pure mirror. */
    fun tick(dt: Float) {
        if (phase != Phase.Live || !isHost) return
        world.step(dt)
        link.send(
            JSONObject().put("t", "ball")
                .put("x", world.bx.toDouble()).put("y", world.by.toDouble())
                .put("vx", world.vx.toDouble()).put("vy", world.vy.toDouble())
        )
    }

    private fun wire() {
        collectJob?.cancel()
        val l = link
        collectJob = scope.launch {
            l.events.collect { ev ->
                // A closed socket emits Down from its own thread, which can land
                // after reset() has already moved on. Ignore anything from a
                // link we have since replaced.
                if (l !== link) return@collect
                when (ev) {
                    is NetEvent.Up -> onUp(ev.isHost)
                    is NetEvent.Msg -> onMsg(ev.json)
                    is NetEvent.Down -> {
                        note = ev.why
                        phase = Phase.Dead
                    }
                }
            }
        }
    }

    private fun onUp(host: Boolean) {
        // Host waits for the client's hello; the client announces itself immediately.
        if (!host) {
            link.send(
                JSONObject().put("t", "hello")
                    .put("w", (myW / calib).toDouble())
                    .put("h", (myH / calib).toDouble())
            )
        }
    }

    private fun onMsg(j: JSONObject) {
        when (j.optString("t")) {
            "hello" -> if (isHost) {
                val aw = myW / calib
                val ah = myH / calib
                val bw = j.optDouble("w").toFloat()
                val bh = j.optDouble("h").toFloat()
                begin(aw, ah, bw, bh, amLeft = true)
                link.send(
                    JSONObject().put("t", "layout")
                        .put("aw", aw.toDouble()).put("ah", ah.toDouble())
                        .put("bw", bw.toDouble()).put("bh", bh.toDouble())
                )
            }

            "layout" -> if (!isHost) {
                begin(
                    j.optDouble("aw").toFloat(), j.optDouble("ah").toFloat(),
                    j.optDouble("bw").toFloat(), j.optDouble("bh").toFloat(),
                    amLeft = false,
                )
            }

            "ball" -> if (!isHost) {
                world.applyRemote(
                    j.optDouble("x").toFloat(), j.optDouble("y").toFloat(),
                    j.optDouble("vx").toFloat(), j.optDouble("vy").toFloat(),
                )
            }

            "touch" -> if (isHost) {
                world.touch(
                    j.optDouble("x").toFloat(), j.optDouble("y").toFloat(),
                    j.optBoolean("d"),
                )
            }
        }
    }

    private fun begin(aw: Float, ah: Float, bw: Float, bh: Float, amLeft: Boolean) {
        world.layout(aw, ah, bw, bh, amLeft)
        world.place(world.totalW / 2f, world.h / 2f)
        note = ""
        phase = Phase.Live
    }
}
