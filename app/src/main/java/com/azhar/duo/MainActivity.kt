package com.azhar.duo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.TextureView
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale

private val Night = Color(0xFF07080C)
private val Grid = Color(0xFF161C27)
private val GridBold = Color(0xFF243044)
private val BallColor = Color(0xFFFF4D6D)
private val Seam = Color(0xFF00E5A0)
private val Dim = Color(0xFF8892A6)
private val Faint = Color(0xFF49546A)

private fun mm(v: Float) = String.format(Locale.US, "%.1f", v)

/**
 * WebView keeps its scroll extents protected, and they are the only way to ask
 * how wide the page actually laid out. Widening the visibility is the whole point
 * of this subclass.
 */
private class MeasurableWebView(context: android.content.Context) : WebView(context) {
    public override fun computeHorizontalScrollRange(): Int = super.computeHorizontalScrollRange()
    public override fun computeVerticalScrollRange(): Int = super.computeVerticalScrollRange()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full bleed. A status bar down one edge kills the "one screen" illusion dead.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { DuoApp() }
    }
}

@Composable
fun DuoApp() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val session = remember { Session(scope, context) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Night)) {
        val wDp = maxWidth.value
        val hDp = maxHeight.value
        when (session.phase) {
            Phase.Menu -> MenuScreen(session, wDp, hDp)
            Phase.Waiting, Phase.Dead -> WaitingScreen(session)
            Phase.Live -> LiveCanvas(session)
        }
    }

    BackHandler(enabled = session.phase != Phase.Menu) { session.reset() }
}

// ---------------------------------------------------------------- menu

@Composable
private fun MenuScreen(session: Session, wDp: Float, hDp: Float) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("DUO", fontSize = 52.sp, color = Seam, fontFamily = FontFamily.Monospace)
        Text(
            "two phones · one canvas",
            color = Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        )

        Spacer(Modifier.height(28.dp))
        DuoButton("HOST   ·   left half", primary = true) { session.startHost(wDp, hDp) }
        Spacer(Modifier.height(10.dp))
        DuoButton("JOIN   ·   right half", primary = false) { session.startJoin(wDp, hDp) }

        Spacer(Modifier.height(24.dp))
        val pick = clipPicker { uri, name -> session.pickClip(uri, name) }
        Label("CLIP")
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(if (session.myClip == null) "PICK VIDEO" else "CHANGE") {
                pick.launch(pickerRequest())
            }
            Spacer(Modifier.width(10.dp))
            Text(
                session.myClip?.let { shortName(session.clipName) } ?: "none on this phone",
                color = if (session.myClip == null) Faint else Seam,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Note(
            "Pick on either phone. That phone serves the clip and makes the sound;\n" +
                "the other one streams it. No need to think about which is host."
        )

        Spacer(Modifier.height(22.dp))
        Label("MODE")
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModeButton("CANVAS", session.mode == Mode.Canvas) { session.mode = Mode.Canvas }
            Spacer(Modifier.width(8.dp))
            ModeButton("VIDEO", session.mode == Mode.Video) { session.mode = Mode.Video }
            Spacer(Modifier.width(8.dp))
            ModeButton("WEB", session.mode == Mode.Web) { session.mode = Mode.Web }
        }
        Spacer(Modifier.height(8.dp))
        when {
            session.mode == Mode.Video && session.myClip == null -> Note(
                "no clip picked here — pick one, or let the other phone pick.\n" +
                    "Without a clip the canvas runs instead."
            )
            session.mode == Mode.Video -> Note("ready to play")
            session.mode == Mode.Web -> Note("The host types the address. Both phones load it.")
            else -> Note("the grid is the setup and test screen")
        }

        if (session.mode == Mode.Web) {
            Spacer(Modifier.height(12.dp))
            BasicTextField(
                value = session.url,
                onValueChange = { session.url = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = Seam, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                ),
                cursorBrush = SolidColor(Seam),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, GridBold, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }

        Spacer(Modifier.height(22.dp))
        Label("SIZE   ${String.format(Locale.US, "%.2f", session.calib)}x")
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("−") { session.calib = (session.calib - 0.02f).coerceIn(0.70f, 1.40f) }
            Spacer(Modifier.width(8.dp))
            StepButton("100%") { session.calib = 1f }
            Spacer(Modifier.width(8.dp))
            StepButton("+") { session.calib = (session.calib + 0.02f).coerceIn(0.70f, 1.40f) }
        }

        Spacer(Modifier.height(16.dp))
        Label("PANEL GAP   ${mm(session.gapMm)} mm")
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("−") { session.setGap(session.gapMm - 0.5f) }
            Spacer(Modifier.width(8.dp))
            StepButton("0") { session.setGap(0f) }
            Spacer(Modifier.width(8.dp))
            StepButton("+") { session.setGap(session.gapMm + 0.5f) }
        }

        Spacer(Modifier.height(16.dp))
        Note(
            "Measure the gap between the two lit screens with a ruler.\n" +
                "Size only needs a change if the panels disagree."
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    Spacer(Modifier.height(8.dp))
}

/**
 * The system photo picker. It needs no storage permission, and it gives back an
 * address we can read the clip from.
 */
@Composable
private fun clipPicker(
    onPicked: (Uri, String) -> Unit,
): ManagedActivityResultLauncher<PickVisualMediaRequest, Uri?> {
    val context = LocalContext.current
    return rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPicked(uri, displayName(context, uri))
    }
}

private fun pickerRequest() =
    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)

private fun displayName(context: Context, uri: Uri): String {
    runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) return c.getString(i) ?: ""
                }
            }
    }
    return uri.lastPathSegment ?: "clip"
}

/** A clip name short enough for a one-line label. */
private fun shortName(name: String, max: Int = 26): String =
    if (name.length <= max) name else name.take(max - 1) + "…"

@Composable
private fun Note(text: String) {
    Text(
        text, color = Faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center, lineHeight = 14.sp,
    )
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .border(1.dp, if (selected) Seam else GridBold, RoundedCornerShape(8.dp))
            .background(
                if (selected) Seam.copy(alpha = 0.12f) else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            color = if (selected) Seam else Dim,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .border(1.dp, GridBold, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(label, color = Dim, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

// ---------------------------------------------------------------- waiting

@Composable
private fun WaitingScreen(session: Session) {
    val dead = session.phase == Phase.Dead
    // Walk the interfaces once, not on every recomposition.
    val ip = remember { if (session.isHost) Link.localIp() else "" }

    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!dead) {
            CircularProgressIndicator(color = Seam, strokeWidth = 2.dp)
            Spacer(Modifier.height(28.dp))
        }
        Text(
            if (dead) session.note else "waiting",
            color = if (dead) BallColor else Dim, fontSize = 14.sp,
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
        )
        if (!dead && session.isHost) {
            Spacer(Modifier.height(56.dp))
            Text("host ip", color = Faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
            Text(ip, color = Dim, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(52.dp))
        StepButton("back") { session.reset() }
    }
}

// ---------------------------------------------------------------- live

@Composable
private fun LiveCanvas(session: Session) {
    val world = session.world
    val density = LocalDensity.current.density
    val videoMode = session.videoMode
    val webMode = session.webMode

    var ballX by remember { mutableFloatStateOf(0f) }
    var ballY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    session.tick(((now - last) / 1e9f).coerceIn(0f, 0.05f))
                }
                last = now
                ballX = world.bx
                ballY = world.by
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val padY = (maxHeight.value - world.h * session.calib) * density / 2f
        val unitsPerPx = 1f / (density * session.calib)

        // pointerInput(Unit) is installed once and never restarts, so it would keep
        // the first composition's copy of the lambda. rememberUpdatedState makes the
        // captured geometry track the current values instead of going stale.
        val sendTouch by rememberUpdatedState<(Offset, Boolean) -> Unit> { p, down ->
            session.touch(
                world.sliceX + p.x * unitsPerPx,
                (p.y - padY) * unitsPerPx,
                down,
            )
        }

        Box(Modifier.fillMaxSize()) {
            when {
                webMode -> WebSurface(session)
                videoMode -> VideoSurface(session)
            }

            // The ball rides on top of the video. It does not ride on top of a web
            // page: there the page needs every touch, and the page is the demo.
            val gesture = if (webMode) Modifier
            else Modifier.pointerInput(Unit) {
                // requireUnconsumed is the default, so a touch that lands on the
                // controls below never reaches the ball.
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown()
                        sendTouch(down.position, true)
                        var last = down.position
                        var pressed = true
                        while (pressed) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id }
                            if (ch == null || !ch.pressed) {
                                pressed = false
                            } else {
                                last = ch.position
                                sendTouch(last, true)
                                ch.consume()
                            }
                        }
                        sendTouch(last, false)
                    }
                }
            }

            Canvas(Modifier.fillMaxSize().then(gesture)) {
                val u = size.width / world.sliceW
                val padPx = (size.height - world.h * u) / 2f

                clipRect {
                    translate(left = -world.sliceX * u, top = padPx) {
                        if (!videoMode && !webMode) {
                            var x = 0f
                            while (x <= world.totalW) {
                                val bold = x % 200f < 1f
                                drawLine(
                                    if (bold) GridBold else Grid,
                                    Offset(x * u, 0f), Offset(x * u, world.h * u),
                                    strokeWidth = if (bold) 2f else 1f,
                                )
                                x += 50f
                            }
                            var y = 0f
                            while (y <= world.h) {
                                val bold = y % 200f < 1f
                                drawLine(
                                    if (bold) GridBold else Grid,
                                    Offset(0f, y * u), Offset(world.totalW * u, y * u),
                                    strokeWidth = if (bold) 2f else 1f,
                                )
                                y += 50f
                            }
                        }

                        // Sits on the seam, so it is drawn half here and half on the
                        // neighbour. Turn the gap dial until the halves make one circle.
                        if (!webMode) {
                            val cx = world.totalW / 2f * u
                            val cy = world.h / 2f * u
                            drawCircle(
                                if (videoMode) Color.White.copy(alpha = 0.5f) else GridBold,
                                radius = 60f * u, center = Offset(cx, cy), style = Stroke(2f),
                            )

                            val c = Offset(ballX * u, ballY * u)
                            drawCircle(BallColor.copy(alpha = 0.16f), radius = BALL_R * u * 2.2f, center = c)
                            drawCircle(BallColor, radius = BALL_R * u, center = c)
                        }
                    }
                }

                // Mark this phone's inner edge so the physical bezel is accounted for.
                val onLeft = world.sliceX == 0f
                drawRect(
                    Seam.copy(alpha = if (videoMode || webMode) 0.30f else 0.55f),
                    topLeft = Offset(if (onLeft) size.width - 3f else 0f, padPx),
                    size = Size(3f, world.h * u),
                )
            }

            LiveControls(session)
        }
    }
}

/**
 * The clip fills the logical width, and the surface shows only this phone's
 * slice. That is the whole crop — see [videoTransform] for the maths.
 */
@Composable
private fun VideoSurface(session: Session) {
    val world = session.world
    val aspect = session.videoAspect
    val gap = session.gapMm            // read so the transform follows the dial
    var size by remember { mutableStateOf(IntSize.Zero) }

    // Recompute only when something that moves the picture changes.
    val transform = remember(gap, aspect, size, world.totalW, world.h, world.sliceX) {
        if (size.width == 0 || world.sliceW <= 0f) {
            null
        } else {
            val u = size.width / world.sliceW
            val padY = (size.height - world.h * u) / 2f
            videoTransform(
                viewW = size.width.toFloat(), viewH = size.height.toFloat(),
                u = u, totalW = world.totalW, h = world.h,
                sliceX = world.sliceX, padY = padY, videoAspect = aspect,
            )
        }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                session.player?.setVideoTextureView(this)
                // The surface size is only known after layout, and nothing else
                // here recomposes on its own. Without this the transform never
                // gets a size and the video draws uncropped.
                addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                    size = IntSize(r - l, b - t)
                }
            }
        },
        update = { tv ->
            if (tv.width != size.width || tv.height != size.height) {
                size = IntSize(tv.width, tv.height)
            }
            transform?.let { t ->
                val m = Matrix()
                m.setScale(t.scaleX, t.scaleY)
                m.postTranslate(t.tx, t.ty)
                tv.setTransform(m)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Both phones lay the page out at the same CSS width — the width of the whole
 * canvas. Each phone then zooms by its own calibration, so one CSS pixel is the
 * same physical size on both screens, and each scrolls to its own slice.
 *
 * If the two halves disagree, the page fought the viewport we injected. Try a
 * page with a plain layout.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebSurface(session: Session) {
    val world = session.world
    val calib = session.calib
    val gap = session.gapMm
    val url = session.liveUrl
    val order = session.scrollCmd

    var size by remember { mutableStateOf(IntSize.Zero) }
    var view by remember { mutableStateOf<MeasurableWebView?>(null) }
    var loaded by remember { mutableIntStateOf(0) }
    var suppress by remember { mutableStateOf(false) }

    AndroidView(
        factory = { ctx ->
            MeasurableWebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = false
                settings.builtInZoomControls = false
                settings.displayZoomControls = false

                setOnScrollChangeListener { _, _, y, _, _ ->
                    if (suppress) return@setOnScrollChangeListener
                    val range = (computeVerticalScrollRange() - height).coerceAtLeast(1)
                    session.sendScroll(y.toFloat() / range)
                }

                // Same reason as the video surface: nothing else here recomposes,
                // so the size must come from a layout callback or it stays zero
                // and the scroll is never applied.
                addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                    size = IntSize(r - l, b - t)
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, u: String) {
                        // Pin the layout to the full canvas width, identically on
                        // both phones, then let calibration set the physical size.
                        val w = world.totalW
                        val c = session.calib
                        v.evaluateJavascript(
                            "(function(){" +
                                "var m=document.querySelector('meta[name=viewport]');" +
                                "if(!m){m=document.createElement('meta');m.name='viewport';" +
                                "document.head.appendChild(m);}" +
                                "m.setAttribute('content','width=" + w + ", initial-scale=" + c + "');" +
                                "})()",
                            null,
                        )
                        loaded++
                    }
                }
                if (url.isNotEmpty()) loadUrl(url)
                view = this
            }
        },
        update = { v -> if (url.isNotEmpty() && v.url != url) v.loadUrl(url) },
        modifier = Modifier.fillMaxSize(),
    )

    // Place the window on this phone's slice, and follow the other phone's scroll.
    LaunchedEffect(url, gap, size, loaded, order) {
        val v = view ?: return@LaunchedEffect
        if (size.width == 0 || world.totalW <= 0f) return@LaunchedEffect
        val hRange = v.computeHorizontalScrollRange().toFloat()
        val x = if (hRange > 0f) (world.sliceX / world.totalW * hRange).toInt() else 0
        val vRange = (v.computeVerticalScrollRange() - size.height).coerceAtLeast(1)
        val f = order?.fraction
        val y = if (f != null && f >= 0f) (f * vRange).toInt() else v.scrollY
        suppress = true
        v.scrollTo(x, y)
        v.post { suppress = false }
    }
}

@Composable
private fun LiveControls(session: Session) {
    val pick = clipPicker { uri, name -> session.pickClip(uri, name) }

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (session.isHost) {
                StepButton("gap −") { session.setGap(session.gapMm - 0.5f) }
                Spacer(Modifier.width(8.dp))
                Text(
                    mm(session.gapMm),
                    color = Dim, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(8.dp))
                StepButton("gap +") { session.setGap(session.gapMm + 0.5f) }
                Spacer(Modifier.width(14.dp))
            }
            // Either phone can take over the clip while the app runs.
            StepButton(if (session.myClip == null) "PICK" else "CHANGE") {
                pick.launch(pickerRequest())
            }
            if (session.videoMode && session.isHost) {
                Spacer(Modifier.width(8.dp))
                StepButton(if (session.playing) "❚❚" else "▶") { session.togglePlay() }
            }
        }
        if (session.videoMode && session.clipName.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                (if (session.source == Source.Me) "serving · " else "streaming · ") +
                    shortName(session.clipName, 40),
                color = Faint,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------- widgets

@Composable
private fun DuoButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .border(1.dp, if (primary) Seam else GridBold, RoundedCornerShape(12.dp))
            .background(
                if (primary) Seam.copy(alpha = 0.10f) else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (primary) Seam else Dim,
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
