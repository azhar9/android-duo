package com.azhar.duo

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

enum class Phase { Menu, Waiting, Live, Dead }

/** What the two phones put on the canvas. */
enum class Mode { Canvas, Video, Web }

/** The clip both phones play. Push the same file to both phones. */
const val VIDEO_NAME = "duo.mp4"

/** Jump to the host position when the two players drift further apart than this. */
private const val SYNC_TOLERANCE_MS = 200L

/** How often the host tells the client where it is. */
private const val SYNC_INTERVAL_MS = 400L

const val DEFAULT_URL = "https://en.wikipedia.org/wiki/Foldable_smartphone"

/** A vertical scroll order from the other phone. The counter forces a fresh apply. */
data class ScrollCmd(val fraction: Float, val seq: Int)

fun videoFile(context: Context): File? =
    context.getExternalFilesDir(null)?.let { File(it, VIDEO_NAME) }?.takeIf { it.isFile }

/**
 * Wires the transport to the shared canvas.
 *
 * The host is authoritative: it owns the ball, runs the physics, holds the
 * playback clock, and picks the mode. The client forwards its raw touches and
 * renders whatever the host says. One round trip on a hotspot is a few ms, so
 * the client's ball tracks its own finger closely enough that nobody notices.
 */
class Session(private val scope: CoroutineScope, context: Context) {

    private val appContext = context.applicationContext

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

    /** Physical gap between the two panels, in millimetres. The host owns this. */
    var gapMm by mutableFloatStateOf(3f)

    /** The clip on this phone, if the file is present. */
    val video: File? = videoFile(appContext)

    // --- menu choices, host only. liveMode is what actually runs. ---
    var mode by mutableStateOf(Mode.Canvas)
    var url by mutableStateOf(DEFAULT_URL)

    var liveMode by mutableStateOf(Mode.Canvas); private set
    val videoMode: Boolean get() = liveMode == Mode.Video
    val webMode: Boolean get() = liveMode == Mode.Web

    var player: ExoPlayer? = null
        private set

    /** Width divided by height, from the player once the format is known. */
    var videoAspect by mutableFloatStateOf(16f / 9f); private set

    /** Mirrors the player so the play button can follow it. */
    var playing by mutableStateOf(false); private set

    /** The URL both phones load. The host owns it. */
    var liveUrl by mutableStateOf(""); private set

    /** A vertical scroll order from the other phone. */
    var scrollCmd by mutableStateOf<ScrollCmd?>(null); private set

    private var scrollSeq = 0
    private var myW = 0f
    private var myH = 0f
    private var lastSync = 0L

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
        stopPlayer()
        liveMode = Mode.Canvas
        link = Link(scope)
        wire()
        note = ""
        phase = Phase.Menu
    }

    /** Set the gap. The host tells the client; both then use one value. */
    fun setGap(mm: Float) {
        gapMm = mm.coerceIn(0f, 20f)
        world.setGapMm(gapMm)
        if (isHost && phase == Phase.Live) {
            link.send(JSONObject().put("t", "gap").put("mm", gapMm.toDouble()))
        }
    }

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
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

    /** A vertical scroll on this phone, as a fraction of the scrollable range. */
    fun sendScroll(fraction: Float) {
        if (phase != Phase.Live || !webMode) return
        link.send(JSONObject().put("t", "scroll").put("f", fraction.toDouble()))
    }

    private fun applyRemoteScroll(j: JSONObject) {
        val f = j.optDouble("f", 0.0).toFloat().coerceIn(0f, 1f)
        scrollCmd = ScrollCmd(f, ++scrollSeq)
    }

    /** Called once per frame. Host simulates and broadcasts; client is a pure mirror. */
    fun tick(dt: Float) {
        if (phase != Phase.Live) return

        player?.let { p ->
            val vs = p.videoSize
            if (vs.width > 0 && vs.height > 0) {
                val a = vs.width.toFloat() / vs.height
                if (a != videoAspect) videoAspect = a
            }
            if (playing != p.isPlaying) playing = p.isPlaying
        }

        if (!isHost) return
        world.step(dt)
        link.send(
            JSONObject().put("t", "ball")
                .put("x", world.bx.toDouble()).put("y", world.by.toDouble())
                .put("vx", world.vx.toDouble()).put("vy", world.vy.toDouble())
        )
        syncVideoIfDue()
    }

    /** The host is the clock. The client corrects itself when it drifts. */
    private fun syncVideoIfDue() {
        val p = player ?: return
        if (!videoMode) return
        val now = System.currentTimeMillis()
        if (now - lastSync < SYNC_INTERVAL_MS) return
        lastSync = now
        link.send(
            JSONObject().put("t", "vid")
                .put("p", p.currentPosition.toDouble())
                .put("r", if (p.isPlaying) 1 else 0)
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
                    .put("v", if (video != null) 1 else 0)
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
                // Video needs the same clip on both phones. Web always works,
                // because the client loads the page itself.
                val use = when (mode) {
                    Mode.Video -> if (video != null && j.optInt("v") == 1) Mode.Video else Mode.Canvas
                    Mode.Web -> Mode.Web
                    Mode.Canvas -> Mode.Canvas
                }
                begin(aw, ah, bw, bh, amLeft = true, use, gapMm, url)
                link.send(
                    JSONObject().put("t", "layout")
                        .put("aw", aw.toDouble()).put("ah", ah.toDouble())
                        .put("bw", bw.toDouble()).put("bh", bh.toDouble())
                        .put("m", use.name.lowercase())
                        .put("url", url)
                        .put("gap", gapMm.toDouble())
                )
            }

            "layout" -> if (!isHost) {
                val m = when (j.optString("m")) {
                    "video" -> Mode.Video
                    "web" -> Mode.Web
                    else -> Mode.Canvas
                }
                begin(
                    j.optDouble("aw").toFloat(), j.optDouble("ah").toFloat(),
                    j.optDouble("bw").toFloat(), j.optDouble("bh").toFloat(),
                    amLeft = false,
                    use = if (m == Mode.Video && video == null) Mode.Canvas else m,
                    gap = j.optDouble("gap", 3.0).toFloat(),
                    url = j.optString("url"),
                )
            }

            "gap" -> if (!isHost) {
                gapMm = j.optDouble("mm", 0.0).toFloat().coerceIn(0f, 20f)
                world.setGapMm(gapMm)
            }

            "vid" -> if (!isHost) followHost(j)

            "scroll" -> applyRemoteScroll(j)

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

    /** The client takes the host's play state, and seeks only if it has drifted. */
    private fun followHost(j: JSONObject) {
        val p = player ?: return
        val pos = j.optLong("p")
        val playing = j.optInt("r") == 1
        if (playing != p.isPlaying) {
            if (playing) p.play() else p.pause()
        }
        if (abs(p.currentPosition - pos) > SYNC_TOLERANCE_MS) p.seekTo(pos)
    }

    private fun begin(
        aw: Float, ah: Float, bw: Float, bh: Float,
        amLeft: Boolean, use: Mode, gap: Float, url: String,
    ) {
        gapMm = gap.coerceIn(0f, 20f)
        liveMode = use
        liveUrl = url
        world.layout(aw, ah, bw, bh, amLeft)
        world.setGapMm(gapMm)
        world.place(world.totalW / 2f, world.h / 2f)
        if (use == Mode.Video) startPlayer() else stopPlayer()
        note = ""
        phase = Phase.Live
    }

    private fun startPlayer() {
        val f = video ?: return
        if (player != null) return
        player = ExoPlayer.Builder(appContext).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
            // Two phones playing one clip in the same room would echo. Only the
            // host makes sound.
            volume = if (isHost) 1f else 0f
            playWhenReady = isHost
            prepare()
        }
    }

    private fun stopPlayer() {
        player?.release()
        player = null
        videoAspect = 16f / 9f
    }
}
