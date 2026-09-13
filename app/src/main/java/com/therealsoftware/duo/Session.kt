package com.therealsoftware.duo

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs

enum class Phase { Menu, Waiting, Live, Dead }

/** What the two phones put on the canvas. */
enum class Mode { Canvas, Video, Web, Picture }

/**
 * One disk cache for streamed media, shared by the whole app.
 *
 * SimpleCache refuses to open the same directory twice, so there has to be
 * exactly one of these. The limit is generous because the point is to hold a
 * whole film: once it is here, playback no longer touches the network.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private object MediaCache {
    private const val LIMIT = 1024L * 1024 * 1024

    @Volatile
    private var instance: SimpleCache? = null

    fun of(context: Context): SimpleCache = instance ?: synchronized(this) {
        instance ?: SimpleCache(
            java.io.File(context.cacheDir, "streamed"),
            LeastRecentlyUsedCacheEvictor(LIMIT),
        ).also { instance = it }
    }
}

/** Builds data sources that read over the network and keep what they read. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun cachingSource(context: Context): CacheDataSource.Factory {
    val upstream = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(8000)
        .setReadTimeoutMs(8000)
        .setAllowCrossProtocolRedirects(true)
    return CacheDataSource.Factory()
        .setCache(MediaCache.of(context))
        .setUpstreamDataSourceFactory(upstream)
        // A cache that cannot be written is not a reason to stop playing.
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}

/** Which phone holds the clip. The geometry role and the media role are separate. */
enum class Source { None, Me, Peer }

/**
 * How far the client may drift before it is pulled back.
 *
 * This was 200 ms, and it made the picture stutter. A client that is buffering
 * a little sits permanently outside 200 ms, so it was yanked back to the host
 * twice a second. Half a second is invisible to a viewer and gives the player
 * room to breathe.
 */
private const val SYNC_TOLERANCE_MS = 500L

/** How often the host tells the client where it is. */
private const val SYNC_INTERVAL_MS = 500L

/** Drift has to be seen this many times running before the player jumps. */
private const val SYNC_STRIKES = 2

const val DEFAULT_URL = "https://www.google.com"

/** How far down a page a phone has scrolled, in page pixels. */
data class Scroll(val x: Int, val y: Int)

/**
 * Wires the transport to the shared canvas.
 *
 * Two roles are separate and must not be confused:
 *
 * - **Geometry.** The host owns the left half and the clock. The client mirrors.
 * - **Media.** Whichever phone picked a clip serves it over HTTP. Either phone
 *   can be the media source, whatever its geometry role.
 *
 * One round trip on a hotspot is a few ms, so the client's ball tracks its own
 * finger closely enough that nobody notices.
 */
// The player and the cache are media3's, and media3 marks those parts unstable.
// The version is pinned in build.gradle.kts, so the note here says the use is
// deliberate rather than accidental.
@androidx.annotation.OptIn(UnstableApi::class)
class Session(private val scope: CoroutineScope, context: Context) {

    private val appContext = context.applicationContext

    val world = World()

    /** What this pair worked out last time. Read once, written as it changes. */
    private val settings = Settings(appContext)

    /** Which set of ports this pair uses. Lets several pairs share one network. */
    var channel by mutableIntStateOf(settings.channel)
    var ports = Ports(settings.channel)
        private set

    private var media = MediaServer(appContext, scope, ports)
    var streamer = FrameServer(scope, ports)
        private set
    private var link = Link(scope, ports)
    private var collectJob: Job? = null

    var phase by mutableStateOf(Phase.Menu); private set
    var isHost by mutableStateOf(false); private set
    var note by mutableStateOf(""); private set

    /**
     * Calibration knob. Above 1.0 this phone's slice covers fewer logical units, so
     * content renders larger. Both phones must agree by eye — OEMs report dp
     * densities that don't match the real panel, and MIUI lets the user change it.
     */
    var calib by mutableFloatStateOf(settings.calib)
        private set

    /** Physical gap between the two panels, in millimetres. The host owns this. */
    var gapMm by mutableFloatStateOf(settings.gapMm)
        private set

    /** Side by side, or one above the other. The host owns this. */
    var axis by mutableStateOf(settings.axis); private set

    // --- menu choices, host only. liveMode is what actually runs. ---
    var mode by mutableStateOf(Mode.Video)
    var url by mutableStateOf(DEFAULT_URL)

    var liveMode by mutableStateOf(Mode.Canvas); private set
    val videoMode: Boolean get() = liveMode == Mode.Video
    val webMode: Boolean get() = liveMode == Mode.Web
    val pictureMode: Boolean get() = liveMode == Mode.Picture

    /**
     * The phone holding the picture renders it and sends the other half. For a
     * web page that is always the host, because the host owns the browser. For a
     * picture it is whichever phone picked it, so either phone can serve.
     */
    val servesFrames: Boolean
        get() = when (liveMode) {
            // A page is rendered by both phones. Nobody sends pictures of it.
            Mode.Web -> false
            Mode.Picture -> source == Source.Me
            else -> false
        }

    // --- the media role ---

    /** Which phone holds the clip now. */
    var source by mutableStateOf(Source.None); private set

    /** The display name of the clip both phones are playing. */
    var clipName by mutableStateOf(""); private set

    /** The clip this phone picked, if any. */
    var myClip by mutableStateOf<Uri?>(null); private set

    // --- a still picture or a document, shown across both screens ---

    var pictureUri by mutableStateOf<Uri?>(null); private set
    var picture by mutableStateOf<Bitmap?>(null); private set
    var picturePage by mutableIntStateOf(0); private set
    var picturePages by mutableIntStateOf(1); private set

    private var myClipName = ""

    /** The moment this build was compiled. Two phones must carry the same one. */
    val buildStamp: Long = BuildConfig.BUILD_TIME

    /** True when this phone can start serving a clip the other one picked. */
    val peerAddress: String? get() = link.peerIp

    var player: ExoPlayer? = null
        private set

    /** Width divided by height, from the player once the format is known. */
    var videoAspect by mutableFloatStateOf(16f / 9f); private set

    /** Mirrors the player so the play button can follow it. */
    var playing by mutableStateOf(false); private set

    /** The address both phones load. Either phone can change it. */
    var liveUrl by mutableStateOf(""); private set

    /** Where the other phone has scrolled to. The page here follows it. */
    var peerScroll by mutableStateOf<Scroll?>(null); private set

    /** Names the clip this phone holds, so the other phone caches it apart. */
    private fun myClipToken(): String = myClip?.toString()?.hashCode()?.toString() ?: ""

    /** Names the clip the other phone is serving, learned from its messages. */
    private var peerClipKey = ""

    private var myW = 0f
    private var myH = 0f

    /** True when this phone is being held tall rather than wide. */
    private val isPortrait: Boolean get() = myH >= myW

    /** Where the film has reached, and how long it is. Updated a few times a second. */
    var positionMs by mutableLongStateOf(0L); private set
    var durationMs by mutableLongStateOf(0L); private set
    private var lastPositionTick = 0L
    private var lastSync = 0L
    private var driftStrikes = 0
    private var prefetchJob: Job? = null

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

    fun startJoin(w: Float, h: Float, host: String? = null) {
        myW = w; myH = h
        isHost = false
        note = if (host.isNullOrBlank()) "looking for host…" else "reaching $host…"
        phase = Phase.Waiting
        link.join(host)
    }

    /**
     * Rebuild every socket on a new channel. Both phones must use the same one.
     * Only possible between sessions; the ports cannot move under a live link.
     */
    fun chooseChannel(n: Int) {
        if (phase == Phase.Live || phase == Phase.Waiting) return
        channel = n.coerceIn(0, MAX_CHANNEL)
        settings.channel = channel
        ports = Ports(channel)
        media = MediaServer(appContext, scope, ports)
        streamer = FrameServer(scope, ports)
        link = Link(scope, ports)
        wire()
    }

    /** The address of the host this phone reached, once it is connected. */
    val hostAddress: String? get() = if (isHost) null else link.peerIp

    fun reset() {
        link.close()
        stopPlayer()
        media.stop()
        streamer.stop()
        liveMode = Mode.Canvas
        source = Source.None
        clipName = ""
        link = Link(scope, ports)
        wire()
        note = ""
        phase = Phase.Menu
    }

    /**
     * Take a clip from the picker. This phone becomes the media source, whatever
     * its geometry role, and the other phone starts streaming from it.
     */
    fun pickClip(uri: Uri, displayName: String) {
        scope.launch {
            if (!media.start(uri, displayName)) {
                note = "cannot read that clip"
                return@launch
            }
            myClip = uri
            myClipName = displayName
            source = Source.Me
            clipName = displayName
            if (phase == Phase.Live) {
                link.send(
                    JSONObject().put("t", "source").put("n", displayName).put("k", myClipToken())
                )
                if (liveMode == Mode.Video) restartPlayer()
            }
        }
    }

    /**
     * Take a picture or a document from the picker. This phone renders it and
     * sends the other phone its half, exactly as the host does for a web page.
     */
    fun pickPicture(uri: Uri, displayName: String) {
        scope.launch {
            pictureUri = uri
            picturePage = 0
            picturePages = pageCount(appContext, uri)
            source = Source.Me
            clipName = displayName.ifEmpty { "picture" }
            renderPicture()
            if (phase == Phase.Live) {
                link.send(JSONObject().put("t", "picture").put("p", picturePage))
            }
        }
    }

    /**
     * Take something another app handed over: a film to play, a picture to
     * show, or an address to open. The setup screen then shows it, and whoever
     * is holding the phones decides which one hosts.
     *
     * Nothing here starts a session. Only the people holding the two phones
     * know which of them should be the host.
     */
    fun accept(incoming: Incoming) {
        when {
            incoming.mime.startsWith("video/") -> {
                mode = Mode.Video
                pickClip(incoming.uri, incoming.name)
            }

            incoming.mime.startsWith("image/") || incoming.mime == "application/pdf" -> {
                mode = Mode.Picture
                pickPicture(incoming.uri, incoming.name)
            }

            else -> {
                mode = Mode.Web
                url = incoming.uri.toString()
                // A session may already be running on a page. Take it there too.
                if (phase == Phase.Live) sendUrl(url)
            }
        }
    }

    /** Turn the page. Only the phone showing the document can do this. */
    fun stepPage(delta: Int) {
        if (pictureUri == null || picturePages <= 1) return
        val next = (picturePage + delta).coerceIn(0, picturePages - 1)
        if (next == picturePage) return
        picturePage = next
        scope.launch {
            renderPicture()
            if (phase == Phase.Live && isHost) {
                link.send(JSONObject().put("t", "picture").put("p", picturePage))
            }
        }
    }

    /** Render the picked file onto a canvas the size of both screens together. */
    private suspend fun renderPicture() {
        val uri = pictureUri ?: return
        val w = world.totalW
        val h = world.h
        if (w <= 0f || h <= 0f) return
        val u = appContext.resources.displayMetrics.density * calib
        val shot = withContext(Dispatchers.Default) {
            renderPicture(appContext, uri, picturePage, (w * u).toInt(), (h * u).toInt())
        } ?: return
        picture?.recycle()
        picture = shot.canvas
        picturePages = shot.pages
    }

    /** Set the gap. The host tells the client; both then use one value. */
    fun setGap(mm: Float) {
        gapMm = mm.coerceIn(0f, 20f)
        settings.gapMm = gapMm
        world.setGapMm(gapMm)
        if (isHost && phase == Phase.Live) {
            link.send(JSONObject().put("t", "gap").put("mm", gapMm.toDouble()))
        }
    }

    /** How large this phone draws its half of the canvas.
     *
     * This phone's own setting, and the other phone is not told: the size is
     * what each phone has to correct for its own panel. It is worked out again
     * when a session starts, so a change here shows at the next start.
     */
    fun changeCalib(v: Float) {
        calib = v.coerceIn(0.70f, 1.40f)
        settings.calib = calib
    }

    /** Side by side, or stacked. The host tells the client. */
    fun chooseAxis(a: Axis) {
        axis = a
        settings.axis = a
        if (phase != Phase.Live) return
        world.setAxis(a)
        world.place(world.totalW / 2f, world.h / 2f)
        if (isHost) link.send(JSONObject().put("t", "axis").put("a", a.name))
    }

    /**
     * Either phone can drive. The host owns the film, so a command from the
     * client is a request, and the host's answer travels back on the clock.
     */
    fun videoCommand(play: Boolean? = null, seekMs: Long? = null) {
        val p = player ?: return
        play?.let { if (it) p.play() else p.pause() }
        seekMs?.let { p.seekTo(it) }
        if (!isHost) {
            link.send(
                JSONObject().put("t", "cmd")
                    .put("play", play?.let { if (it) 1 else 0 } ?: -1)
                    .put("seek", seekMs ?: -1L)
            )
        }
    }

    /**
     * Where this phone has scrolled to. The other phone moves to match, so the
     * two halves of the page stay on the same lines.
     */
    fun sendScroll(x: Int, y: Int) {
        link.send(JSONObject().put("t", "scroll").put("x", x).put("y", y))
    }

    /**
     * Either phone can follow a link. The one that moved says where it landed,
     * and the other loads the same address.
     */
    fun sendUrl(u: String) {
        if (u.isEmpty() || u == liveUrl) return
        liveUrl = u
        url = u
        link.send(JSONObject().put("t", "url").put("u", u))
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
        if (phase != Phase.Live) return

        player?.let { p ->
            val now = System.currentTimeMillis()
            if (videoMode && now - lastPositionTick > 250) {
                lastPositionTick = now
                positionMs = p.currentPosition
                val d = p.duration
                if (d > 0) durationMs = d
            }
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

    /**
     * The host is the clock, whoever holds the clip. The client corrects itself
     * when it drifts. The client may be the one serving the file — the clock and
     * the file are separate jobs.
     */
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
                    .put("n", myClipName)
                    // The two halves can only line up if the phones are held
                    // the same way. One tall and one wide cannot meet.
                    .put("p", if (isPortrait) 1 else 0)
                    .put("b", buildStamp)
            )
        }
    }

    private fun onMsg(j: JSONObject) {
        when (j.optString("t")) {
            "hello" -> if (isHost) {
                // A phone running older code may not speak the same protocol at
                // all, so this is checked before anything else.
                if (j.optLong("b", -1L) != buildStamp) {
                    refuse(
                        "The two phones are running different versions of Duo. " +
                            "Install the same build on both, then try again."
                    )
                    return@onMsg
                }
                if (j.optInt("p", -1) != (if (isPortrait) 1 else 0)) {
                    refuse(
                        "Both phones must be held the same way. " +
                            "One is tall, the other is wide. Turn one round and try again."
                    )
                    return@onMsg
                }
                val aw = myW / calib
                val ah = myH / calib
                val bw = j.optDouble("w").toFloat()
                val bh = j.optDouble("h").toFloat()
                val peerClip = j.optString("n")

                // The host's own clip wins when both phones have one. The user can
                // always pick again on either phone to take over.
                val use = when (mode) {
                    Mode.Canvas -> Mode.Canvas
                    Mode.Web -> Mode.Web
                    Mode.Picture -> if (myPicturePicked()) {
                        source = Source.Me
                        Mode.Picture
                    } else {
                        Mode.Canvas
                    }
                    Mode.Video -> when {
                        myClip != null -> {
                            source = Source.Me
                            clipName = myClipName
                            Mode.Video
                        }
                        peerClip.isNotEmpty() -> {
                            source = Source.Peer
                            clipName = peerClip
                            Mode.Video
                        }
                        // No clip on either phone. The grid still works.
                        else -> Mode.Canvas
                    }
                }
                begin(aw, ah, bw, bh, first = true, use, gapMm, url, source, clipName, axis)
                link.send(
                    JSONObject().put("t", "layout")
                        .put("aw", aw.toDouble()).put("ah", ah.toDouble())
                        .put("bw", bw.toDouble()).put("bh", bh.toDouble())
                        .put("m", use.name.lowercase())
                        .put("url", url)
                        .put("gap", gapMm.toDouble())
                        .put("ax", axis.name)
                        .put("src", if (source == Source.Me) "host" else if (source == Source.Peer) "client" else "")
                        .put("n", clipName)
                        .put("k", if (source == Source.Me) myClipToken() else peerClipKey)
                )
            }

            "layout" -> if (!isHost) {
                val m = when (j.optString("m")) {
                    "video" -> Mode.Video
                    "web" -> Mode.Web
                    "picture" -> Mode.Picture
                    else -> Mode.Canvas
                }
                when (j.optString("src")) {
                    "client" -> {
                        source = Source.Me
                        clipName = myClipName
                    }
                    "host" -> {
                        source = Source.Peer
                        clipName = j.optString("n")
                        peerClipKey = j.optString("k")
                    }
                    else -> {
                        source = Source.None
                        clipName = ""
                    }
                }
                begin(
                    j.optDouble("aw").toFloat(), j.optDouble("ah").toFloat(),
                    j.optDouble("bw").toFloat(), j.optDouble("bh").toFloat(),
                    first = false, use = if (m == Mode.Video && source == Source.None) Mode.Canvas else m,
                    gap = j.optDouble("gap", 3.0).toFloat(),
                    url = j.optString("url"),
                    src = source, name = clipName,
                    ax = if (j.optString("ax") == "Vertical") Axis.Vertical else Axis.Horizontal,
                )
            }

            // The other phone picked a clip. It now serves, and we stream from it.
            "source" -> {
                val n = j.optString("n")
                if (n.isEmpty()) {
                    source = Source.None
                    clipName = ""
                    stopPlayer()
                } else {
                    source = Source.Peer
                    clipName = n
                    peerClipKey = j.optString("k")
                    if (phase == Phase.Live && liveMode == Mode.Video) restartPlayer()
                }
            }

            "scroll" -> if (phase == Phase.Live) {
                peerScroll = Scroll(j.optInt("x"), j.optInt("y"))
            }

            "url" -> if (phase == Phase.Live) {
                val u = j.optString("u")
                if (u.isNotEmpty()) {
                    liveUrl = u
                    url = u
                }
            }

            "gap" -> if (!isHost) setGap(j.optDouble("mm", 0.0).toFloat())

            "axis" -> if (!isHost) {
                chooseAxis(if (j.optString("a") == "Vertical") Axis.Vertical else Axis.Horizontal)
            }

            "refuse" -> if (!isHost) {
                note = j.optString("why")
                phase = Phase.Dead
            }

            "vid" -> if (!isHost) followHost(j)

            "cmd" -> if (isHost) {
                val play = j.optInt("play", -1)
                val seek = j.optLong("seek", -1L)
                val p = player ?: return@onMsg
                if (play == 1) p.play() else if (play == 0) p.pause()
                if (seek >= 0L) p.seekTo(seek)
            }

            "picture" -> if (!isHost) {
                picturePage = j.optInt("p", 0)
                scope.launch { renderPicture() }
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

    /**
     * The client takes the host's play state, and corrects its own position only
     * when it has drifted for a while. A single bad reading during a buffer
     * refill is not a reason to jump.
     */
    private fun followHost(j: JSONObject) {
        val p = player ?: return
        val pos = j.optLong("p")
        val playing = j.optInt("r") == 1
        if (playing != p.isPlaying) {
            if (playing) p.play() else p.pause()
        }
        if (abs(p.currentPosition - pos) > SYNC_TOLERANCE_MS) {
            driftStrikes++
            if (driftStrikes >= SYNC_STRIKES) {
                driftStrikes = 0
                p.seekTo(pos)
            }
        } else {
            driftStrikes = 0
        }
    }

    private fun myPicturePicked() = pictureUri != null

    /**
     * Say no, and say it to the other phone as well.
     *
     * Refusing silently leaves the other handset waiting for a reply that never
     * comes, so it sits on "looking for the other phone" for ever. A refusal is
     * a message, not an absence of one.
     */
    private fun refuse(why: String) {
        note = why
        phase = Phase.Dead
        link.send(JSONObject().put("t", "refuse").put("why", why))
    }

    private fun begin(
        aw: Float, ah: Float, bw: Float, bh: Float,
        first: Boolean, use: Mode, gap: Float, url: String,
        src: Source, name: String, ax: Axis,
    ) {
        gapMm = gap.coerceIn(0f, 20f)
        liveMode = use
        liveUrl = url
        source = src
        clipName = name
        axis = ax
        world.layout(aw, ah, bw, bh, first, ax)
        world.setGapMm(gapMm)
        world.place(world.totalW / 2f, world.h / 2f)
        if (use == Mode.Video) startPlayer() else stopPlayer()
        // The layout has to be known before the picture can be sized to it.
        if (use == Mode.Picture) scope.launch { renderPicture() }
        // Whoever holds the content renders it and serves the frames.
        if (servesFrames) streamer.start() else streamer.stop()
        note = ""
        phase = Phase.Live
    }

    private fun restartPlayer() {
        stopPlayer()
        if (liveMode == Mode.Video) startPlayer()
    }

    private fun startPlayer() {
        if (player != null) return
        val item = when (source) {
            Source.Me -> myClip?.let { MediaItem.fromUri(it) }
            Source.Peer -> link.peerIp?.let {
                MediaItem.fromUri(mediaUrl(it, ports.media, peerClipKey))
            }
            Source.None -> null
        } ?: return

        // More slack than the default. The client is reading the file over the
        // network rather than from disk, and the default buffer is tuned for a
        // file that is already here.
        val buffer = DefaultLoadControl.Builder()
            .setBufferDurationsMs(8000, 40000, 2000, 5000)
            .build()

        val builder = ExoPlayer.Builder(appContext).setLoadControl(buffer)
        if (source == Source.Peer) {
            // Stream and keep. Playback starts from whatever has arrived, and
            // the player holds on to it, so a rewind or a replay of a part
            // already seen costs no network at all.
            builder.setMediaSourceFactory(DefaultMediaSourceFactory(cachingSource(appContext)))
        }

        player = builder.build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            setMediaItem(item)
            // Only the phone that holds the clip makes sound. Two phones in one
            // room playing the same audio a few ms apart would echo.
            volume = if (source == Source.Me) 1f else 0f
            playWhenReady = isHost
            prepare()
        }
        if (source == Source.Peer) fillCacheInBackground(item)
    }

    /**
     * Pull the whole clip down as fast as the link allows, without waiting for
     * playback to reach it.
     *
     * The player reads at its own pace. Left to itself it caches only what it
     * has played, so it stays a hostage to the network for the whole film and
     * any hiccup reaches the picture. This walks the file once at full speed.
     * When it finishes, playback reads from the device and the network stops
     * mattering.
     *
     * CacheDataSource skips the parts the player has already fetched, so the
     * two do not race over the same bytes.
     */
    private fun fillCacheInBackground(item: MediaItem) {
        val url = item.localConfiguration?.uri?.toString() ?: return
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            runCatching {
                val source = cachingSource(appContext).createDataSource()
                val spec = DataSpec(android.net.Uri.parse(url))
                @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
                CacheWriter(source, spec, null, null).cache()
            }
        }
    }

    private fun stopPlayer() {
        prefetchJob?.cancel()
        prefetchJob = null
        player?.release()
        player = null
        videoAspect = 16f / 9f
    }
}
