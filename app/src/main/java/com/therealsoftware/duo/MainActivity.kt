package com.therealsoftware.duo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale

// ---------------------------------------------------------------- theme

private val Page = Color(0xFFFFFFFF)
private val CardBg = Color(0xFFFFFFFF)
private val Line = Color(0xFFE6E6E6)
private val Ink = Color(0xFF222222)
private val Sub = Color(0xFF717171)
private val Accent = Color(0xFFFF385C)
private val AccentSoft = Color(0xFFFFEDF0)
private val Night = Color(0xFF000000)
private val Grid = Color(0xFF1A1A1A)
private val GridBold = Color(0xFF2E2E2E)
private val BallColor = Color(0xFFFF385C)
private val Seam = Color(0xFF00E5A0)

private fun mm(v: Float) = String.format(Locale.US, "%.1f", v)
private fun pct(v: Float) = String.format(Locale.US, "%.0f%%", v * 100f)

/** Where the logical canvas sits inside a full-screen surface. */
private class Viewport(val u: Float, val padX: Float, val padY: Float)

private fun viewportOf(world: World, viewW: Float, viewH: Float): Viewport =
    when (world.axis) {
        Axis.Horizontal -> {
            val u = if (world.sliceW > 0f) viewW / world.sliceW else 1f
            Viewport(u, 0f, (viewH - world.h * u) / 2f)
        }
        Axis.Vertical -> {
            val u = if (world.sliceH > 0f) viewH / world.sliceH else 1f
            Viewport(u, (viewW - world.totalW * u) / 2f, 0f)
        }
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

    BoxWithConstraints(Modifier.fillMaxSize().background(Page)) {
        val wDp = maxWidth.value
        val hDp = maxHeight.value
        when (session.phase) {
            Phase.Menu -> SetupScreen(session, wDp, hDp)
            Phase.Waiting, Phase.Dead -> WaitingScreen(session)
            Phase.Live -> LiveScreen(session)
        }
    }

    BackHandler(enabled = session.phase != Phase.Menu) { session.reset() }
}

// ---------------------------------------------------------------- setup

@Composable
private fun SetupScreen(session: Session, wDp: Float, hDp: Float) {
    var showLicences by remember { mutableStateOf(false) }
    if (showLicences) {
        LicencesScreen(onBack = { showLicences = false })
        return
    }

    val pick = clipPicker { uri, name -> session.pickClip(uri, name) }
    val choosePicture = documentPicker { uri, name -> session.pickPicture(uri, name) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
    ) {
        Text("Duo", fontSize = 44.sp, color = Ink, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Two phones, one big screen.",
            fontSize = 17.sp, color = Sub,
        )

        Spacer(Modifier.height(24.dp))

        // --- connect ---
        val myIp = remember { Link.localIp() }
        var hostToFind by remember { mutableStateOf("") }

        Panel {
            Text("Start a session", fontSize = 20.sp, color = Ink, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Both phones must be on the same WiFi. One can share a hotspot.",
                fontSize = 15.sp, color = Sub, lineHeight = 20.sp,
            )

            Spacer(Modifier.height(14.dp))
            StepperRow(
                label = "Channel",
                value = "${session.channel + 1}",
                onMinus = { session.chooseChannel(session.channel - 1) },
                onPlus = { session.chooseChannel(session.channel + 1) },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Two pairs of phones on one network must use different channels. " +
                    "Both phones in a pair must match.",
                fontSize = 14.sp, color = Sub, lineHeight = 19.sp,
            )

            Spacer(Modifier.height(16.dp))
            Text("This phone is at", fontSize = 14.sp, color = Sub)
            Spacer(Modifier.height(2.dp))
            Text(myIp, fontSize = 20.sp, color = Ink, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(14.dp))
            val firstHalf = if (session.axis == Axis.Horizontal) "left half" else "top half"
            val secondHalf = if (session.axis == Axis.Horizontal) "right half" else "bottom half"
            BigButton("Be the host  ·  $firstHalf", primary = true) {
                session.startHost(wDp, hDp)
            }

            Spacer(Modifier.height(16.dp))
            Text("Join this address", fontSize = 14.sp, color = Sub)
            Spacer(Modifier.height(6.dp))
            BasicTextField(
                value = hostToFind,
                onValueChange = { hostToFind = it },
                singleLine = true,
                textStyle = TextStyle(color = Ink, fontSize = 16.sp),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFF7F7F7))
                    .border(1.dp, Line, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (hostToFind.isBlank()) "Leave it empty to find a host on its own."
                else "Will connect straight to $hostToFind.",
                fontSize = 14.sp, color = Sub, lineHeight = 19.sp,
            )
            Spacer(Modifier.height(12.dp))
            BigButton("Join a host  ·  $secondHalf", primary = false) {
                session.startJoin(wDp, hDp, hostToFind)
            }
        }

        Spacer(Modifier.height(14.dp))

        // --- what to show ---
        Panel {
            SectionTitle("What to show")
            Segmented(
                options = listOf("Grid", "Video", "Web", "File"),
                selected = when (session.mode) {
                    Mode.Canvas -> 0
                    Mode.Video -> 1
                    Mode.Web -> 2
                    Mode.Picture -> 3
                },
                onSelect = {
                    session.mode = when (it) {
                        0 -> Mode.Canvas
                        1 -> Mode.Video
                        2 -> Mode.Web
                        else -> Mode.Picture
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            when (session.mode) {
                Mode.Video -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                session.myClip?.let { shortName(session.clipName, 24) }
                                    ?: "No clip on this phone",
                                fontSize = 16.sp,
                                color = if (session.myClip == null) Sub else Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Choose one here, or on the other phone.",
                                fontSize = 14.sp, color = Sub,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        SmallButton(if (session.myClip == null) "Choose" else "Change") {
                            pick.launch(pickerRequest())
                        }
                    }
                }
                Mode.Web -> {
                    Text("Address", fontSize = 15.sp, color = Sub)
                    Spacer(Modifier.height(6.dp))
                    BasicTextField(
                        value = session.url,
                        onValueChange = { session.url = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Ink, fontSize = 16.sp),
                        cursorBrush = SolidColor(Accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFF7F7F7))
                            .border(1.dp, Line, RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The host renders the page and streams it. Both phones show one half.",
                        fontSize = 14.sp, color = Sub, lineHeight = 16.sp,
                    )
                }
                Mode.Canvas -> {
                    Text(
                        "A grid and a ball. Use it to line the two screens up, and to " +
                            "check the gap.",
                        fontSize = 15.sp, color = Sub, lineHeight = 18.sp,
                    )
                }
                Mode.Picture -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            session.pictureUri?.let { shortName(session.clipName, 22) }
                                ?: "No picture on this phone",
                            fontSize = 16.sp,
                            color = if (session.pictureUri == null) Sub else Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "A photo or a PDF, shown whole across both screens.",
                            fontSize = 14.sp, color = Sub,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    SmallButton(if (session.pictureUri == null) "Choose" else "Change") {
                        choosePicture.launch(DOCUMENT_TYPES)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // --- how they sit ---
        Panel {
            SectionTitle("How the phones sit")
            Segmented(
                options = listOf("Side by side", "Stacked"),
                selected = if (session.axis == Axis.Horizontal) 0 else 1,
                onSelect = { session.chooseAxis(if (it == 0) Axis.Horizontal else Axis.Vertical) },
            )
            Spacer(Modifier.height(12.dp))
            StepperRow(
                label = "Gap between the screens",
                value = "${mm(session.gapMm)} mm",
                onMinus = { session.setGap(session.gapMm - 0.5f) },
                onPlus = { session.setGap(session.gapMm + 0.5f) },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Measure the lit screens with a ruler. A wide video suits side by side; " +
                    "a tall one suits stacked.",
                fontSize = 14.sp, color = Sub, lineHeight = 16.sp,
            )
        }

        Spacer(Modifier.height(14.dp))

        // --- size ---
        Panel {
            SectionTitle("Size on this phone")
            StepperRow(
                label = "Match the other screen",
                value = pct(session.calib),
                onMinus = { session.calib = (session.calib - 0.02f).coerceIn(0.70f, 1.40f) },
                onPlus = { session.calib = (session.calib + 0.02f).coerceIn(0.70f, 1.40f) },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Change this only when a grid square is a different size on the two " +
                    "screens. Set it before you start.",
                fontSize = 14.sp, color = Sub, lineHeight = 16.sp,
            )
        }

        Spacer(Modifier.height(20.dp))
        Box(
            Modifier.fillMaxWidth().clickable { showLicences = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Licences and credits",
                fontSize = 15.sp, color = Accent, fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** What the app is built on. Required reading on Google Play, useful anyway. */
private val LICENCES = listOf(
    "AndroidX — Jetpack Compose" to
        "Draws the interface, the grid, and the control bar. Apache License 2.0. " +
        "Copyright the Android Open Source Project.",
    "AndroidX — Activity and Core" to
        "The back gesture, the app lifecycle, and Android's photo picker. " +
        "Apache License 2.0.",
    "AndroidX Media3 — ExoPlayer" to
        "Plays the video, seeks to byte ranges, and follows the playback clock. " +
        "Apache License 2.0.",
    "Kotlin and kotlinx.coroutines" to
        "The language, and the coroutines that carry the network work. " +
        "Apache License 2.0. Copyright JetBrains s.r.o.",
)

@Composable
private fun LicencesScreen(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallButton("Back") { onBack() }
            Spacer(Modifier.width(14.dp))
            Text("Licences", fontSize = 28.sp, color = Ink, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "Duo is MIT licensed. It is built on other people's work, and this is " +
                "the list.",
            fontSize = 16.sp, color = Sub, lineHeight = 24.sp,
        )
        Spacer(Modifier.height(18.dp))

        LICENCES.forEach { (name, detail) ->
            Panel {
                Text(name, fontSize = 17.sp, color = Ink, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text(detail, fontSize = 15.sp, color = Sub, lineHeight = 18.sp)
            }
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "The full text of every licence, and the full list including the " +
                "build tools, is in the NOTICE file at " +
                "github.com/azhar9/android-duo.",
            fontSize = 14.sp, color = Sub, lineHeight = 20.sp,
        )
        Spacer(Modifier.height(28.dp))
    }
}

// ---------------------------------------------------------------- waiting

@Composable
private fun WaitingScreen(session: Session) {
    val dead = session.phase == Phase.Dead
    val ip = remember { if (session.isHost) Link.localIp() else "" }

    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!dead) {
            CircularProgressIndicator(color = Accent, strokeWidth = 3.dp)
            Spacer(Modifier.height(24.dp))
            Text("Looking for the other phone", fontSize = 20.sp, color = Ink)
            if (session.isHost) {
                Spacer(Modifier.height(28.dp))
                Text("This phone's address", fontSize = 15.sp, color = Sub)
                Spacer(Modifier.height(4.dp))
                Text(ip, fontSize = 24.sp, color = Ink, fontWeight = FontWeight.Medium)
            }
        } else {
            Text("Disconnected", fontSize = 24.sp, color = Ink, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                session.note, fontSize = 16.sp, color = Sub, textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(36.dp))
        SmallButton("Back") { session.reset() }
    }
}

// ---------------------------------------------------------------- live

@Composable
private fun LiveScreen(session: Session) {
    // The control bar drives the browser, so it needs the browser itself.
    var webView by remember { mutableStateOf<WebView?>(null) }

    val world = session.world

    var frame by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    session.tick(((now - last) / 1e9f).coerceIn(0f, 0.05f))
                }
                last = now
                frame++
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Night)) {
        val viewW = maxWidth.value * LocalDensity.current.density
        val viewH = maxHeight.value * LocalDensity.current.density
        val vp = viewportOf(world, viewW, viewH)

        val sendTouch by rememberUpdatedState<(Offset, Boolean) -> Unit> { p, down ->
            session.touch(
                world.sliceX + (p.x - vp.padX) / vp.u,
                world.sliceY + (p.y - vp.padY) / vp.u,
                down,
            )
        }

        Box(Modifier.fillMaxSize()) {
            when {
                session.webMode && session.servesFrames ->
                    RemoteHostSurface(session) { webView = it }
                session.pictureMode && session.servesFrames -> PictureHostSurface(session)
                session.webMode || session.pictureMode -> RemoteViewerSurface(session)
                session.videoMode -> VideoSurface(session)
            }

            // The ball rides on top of the video. It does not ride on top of a web
            // page: there the page needs every touch, and the page is the demo.
            val gesture = if (session.webMode) Modifier
            else Modifier.pointerInput(Unit) {
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
                val v = viewportOf(world, size.width, size.height)
                val u = v.u
                clipRect {
                    translate(left = v.padX - world.sliceX * u, top = v.padY - world.sliceY * u) {
                        // A picture and a page are the content itself. The grid
                        // would draw over them, so it belongs on the test screen only.
                        if (!session.videoMode && !session.webMode && !session.pictureMode) {
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

                        // Sits on the seam, drawn half here and half on the neighbour.
                        if (!session.webMode && !session.pictureMode) {
                            val cx = world.totalW / 2f * u
                            val cy = world.h / 2f * u
                            drawCircle(
                                if (session.videoMode) Color.White.copy(alpha = 0.5f) else GridBold,
                                radius = 60f * u, center = Offset(cx, cy), style = Stroke(2f),
                            )
                            val c = Offset(world.bx * u, world.by * u)
                            drawCircle(BallColor.copy(alpha = 0.16f), radius = BALL_R * u * 2.2f, center = c)
                            drawCircle(BallColor, radius = BALL_R * u, center = c)
                        }
                    }
                }

                // Mark this phone's inner edge so the physical bezel is accounted for.
                val first = when (world.axis) {
                    Axis.Horizontal -> world.sliceX == 0f
                    Axis.Vertical -> world.sliceY == 0f
                }
                val alpha = if (session.videoMode || session.webMode) 0.30f else 0.55f
                when (world.axis) {
                    Axis.Horizontal -> drawRect(
                        Seam.copy(alpha = alpha),
                        topLeft = Offset(if (first) size.width - 3f else 0f, v.padY),
                        size = Size(3f, world.h * u),
                    )
                    Axis.Vertical -> drawRect(
                        Seam.copy(alpha = alpha),
                        topLeft = Offset(v.padX, if (first) size.height - 3f else 0f),
                        size = Size(world.totalW * u, 3f),
                    )
                }
            }

            LiveBar(session, webView, Modifier.align(Alignment.BottomCenter))
        }

        if (frame < 0) Unit
    }
}

/** The floating control bar over the content. */
@Composable
private fun LiveBar(session: Session, web: WebView?, modifier: Modifier = Modifier) {
    val pick = clipPicker { uri, name -> session.pickClip(uri, name) }
    var typed by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf("") }

    // Follow the address as the user follows links.
    LaunchedEffect(web) {
        val v = web ?: return@LaunchedEffect
        while (isActive) {
            val u = v.url ?: ""
            if (u != shown && !u.startsWith("about:")) {
                shown = u
                typed = u
            }
            delay(400)
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (session.webMode && session.isHost && web != null) {
            BrowserBar(web, typed) { typed = it }
            Spacer(Modifier.height(8.dp))
        }
        if (session.pictureMode && session.servesFrames && session.picturePages > 1) {
            Row(
                Modifier
                    .shadow(10.dp, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xE6151515))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp))
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarButton("<") { session.stepPage(-1) }
                Text(
                    "${session.picturePage + 1} of ${session.picturePages}",
                    color = Color.White, fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                BarButton(">") { session.stepPage(1) }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(
            Modifier
                .shadow(10.dp, RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xE6151515))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(28.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (session.isHost) {
                BarButton("−") { session.setGap(session.gapMm - 0.5f) }
                Text(
                    "${mm(session.gapMm)}",
                    color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                BarButton("+") { session.setGap(session.gapMm + 0.5f) }
                Spacer(Modifier.width(10.dp))
            }
            BarButton(if (session.myClip == null) "Clip" else "Swap") {
                pick.launch(pickerRequest())
            }
            if (session.videoMode && session.isHost) {
                Spacer(Modifier.width(10.dp))
                BarButton(if (session.playing) "Pause" else "Play") { session.togglePlay() }
            }
        }

        if (session.videoMode && session.clipName.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                (if (session.source == Source.Me) "Playing here · " else "Streaming · ") +
                    shortName(session.clipName, 34),
                color = Color(0x99FFFFFF),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Back, an address, and Go. Enough to browse with. */
@Composable
private fun BrowserBar(web: WebView, typed: String, onType: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xE6151515))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton("<") { if (web.canGoBack()) web.goBack() else web.reload() }
        BasicTextField(
            value = typed,
            onValueChange = onType,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
            cursorBrush = SolidColor(Color.White),
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0x33FFFFFF))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        )
        BarButton("Go") { addressOf(typed)?.let { web.loadUrl(it) } }
    }
}

/**
 * Turn what the user typed into something loadable. A bare word searches, an
 * address goes straight there. People type "cats" and expect results, not a
 * DNS failure.
 */
fun addressOf(text: String): String? {
    val t = text.trim()
    if (t.isEmpty()) return null
    if (t.startsWith("http://") || t.startsWith("https://")) return t
    return if (t.contains('.') && !t.contains(' ')) "https://$t"
    else "https://www.google.com/search?q=" + java.net.URLEncoder.encode(t, "UTF-8")
}

@Composable
private fun BarButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * The clip fills the span, and the surface shows only this phone's slice. That
 * is the whole crop — see [videoTransform] for the maths.
 */
@Composable
private fun VideoSurface(session: Session) {
    val world = session.world
    val aspect = session.videoAspect
    val gap = session.gapMm
    var size by remember { mutableStateOf(IntSize.Zero) }

    val transform = remember(gap, aspect, size, world.totalW, world.h, world.sliceX, world.sliceY) {
        if (size.width == 0 || world.sliceW <= 0f || world.sliceH <= 0f) {
            null
        } else {
            val v = viewportOf(world, size.width.toFloat(), size.height.toFloat())
            videoTransform(
                viewW = size.width.toFloat(), viewH = size.height.toFloat(),
                u = v.u, totalW = world.totalW, h = world.h,
                sliceX = world.sliceX, sliceY = world.sliceY,
                padX = v.padX, padY = v.padY,
                aspect = aspect, axis = world.axis,
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
                addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
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
 * The host renders the page once, at the size of the whole canvas, and shows
 * its own half. It also crops the other half and streams it, so the viewer never
 * lays the page out at all.
 *
 * That is why any page works here, and why a video on the page plays with sound:
 * the host is an ordinary single browser on an ordinary single device.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RemoteHostSurface(session: Session, onWeb: (WebView) -> Unit) {
    val world = session.world
    val density = LocalDensity.current.density
    val calib = session.calib
    val url = session.liveUrl

    var web by remember { mutableStateOf<WebView?>(null) }
    var mine by remember { mutableStateOf<ImageBitmap?>(null) }

    // The page is laid out once, at the size of the whole canvas.
    val fullW = (world.totalW * calib).dp
    val fullH = (world.h * calib).dp
    val layoutW = world.totalW

    val cut = remember { FrameCutter() }

    Box(Modifier.fillMaxSize().clipToBounds().background(Night)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    // false, not true. With it on, the WebView honours the page's
                    // own viewport and picks its own zoom — which was measured at
                    // 137%, so the page came out wider than both screens and the
                    // right edge was lost. With it off the WebView lays the page
                    // out at a fixed width and scales it to fit the view, so the
                    // page always fills the canvas exactly.
                    settings.useWideViewPort = false
                    settings.loadWithOverviewMode = true
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    settings.mediaPlaybackRequiresUserGesture = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView, u: String) {
                            // Pin the layout to the width of the whole canvas,
                            // whatever viewport the page asked for. Without this
                            // the page picks its own width and the two halves
                            // stop agreeing.
                            v.evaluateJavascript(
                                "(function(){var m=document.querySelector('meta[name=viewport]');" +
                                    "if(!m){m=document.createElement('meta');m.name='viewport';" +
                                    "document.head.appendChild(m);}" +
                                    "m.setAttribute('content','width=$layoutW, initial-scale=1');})()",
                                null,
                            )
                        }
                    }
                    if (url.isNotEmpty()) loadUrl(url)
                    web = this
                    onWeb(this)
                }
            },
            update = { v -> if (url.isNotEmpty() && v.url != url) v.loadUrl(url) },
            // requiredSize, not size: a plain size is clamped by the parent.
            modifier = Modifier.requiredSize(fullW, fullH),
        )

        // Both halves are cut from one render, so they always agree. The WebView
        // is never seen — this picture covers it.
        mine?.let {
            Image(it, null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        }
    }

    LaunchedEffect(url, fullW, fullH) {
        var view: WebView? = null
        while (isActive) {
            delay(1000L / REMOTE_FPS)
            // The factory above can run after this effect starts. Keep looking
            // rather than giving up once.
            if (view == null) view = web
            val v = view ?: continue
            if (v.width <= 0 || v.height <= 0) continue
            // Drawing must happen on the main thread. Writing to a socket must
            // not: Android stops the app for network work on the main thread.
            val shot = withContext(Dispatchers.Main) { cut.capture(v, world, density * calib) }
                ?: continue
            mine = shot.mine.asImageBitmap()
            if (shot.peer != null && session.streamer.connected) {
                withContext(Dispatchers.IO) { session.streamer.send(shot.peer) }
            }
        }
    }
}

/** One frame's two halves. */
private class Shot(val mine: Bitmap, val peer: ByteArray?)

/**
 * Draws the page into one reused bitmap and cuts both slices out of it.
 *
 * The bitmaps are allocated once and drawn into again on each frame. Creating
 * four-megapixel bitmaps ten times a second would bury the collector. Every
 * call happens on the main thread, so the picture Compose draws is never
 * modified while it is being drawn.
 */
private class FrameCutter {
    private var full: Bitmap? = null
    private var mine: Bitmap? = null
    private var peer: Bitmap? = null

    fun capture(view: WebView, world: World, u: Float): Shot? {
        if (u <= 0f || world.peerW <= 0f || world.sliceW <= 0f) return null

        val f = full?.takeIf { it.width == view.width && it.height == view.height }
            ?: Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { full = it }
        view.draw(Canvas(f))

        val m = cut(f, mine, world.sliceX, world.sliceY, world.sliceW, world.sliceH, u) ?: return null
        mine = m

        val peerBytes = cut(f, peer, world.peerX, world.peerY, world.peerW, world.peerH, u)?.let { p ->
            peer = p
            ByteArrayOutputStream().also { p.compress(Bitmap.CompressFormat.JPEG, 65, it) }.toByteArray()
        }
        return Shot(m, peerBytes)
    }

    /** Cut one rectangle out of [src] into [into], reusing the buffer when it fits. */
    private fun cut(
        src: Bitmap, into: Bitmap?, x: Float, y: Float, w: Float, h: Float, u: Float,
    ): Bitmap? {
        val px = (x * u).toInt().coerceIn(0, src.width - 1)
        val py = (y * u).toInt().coerceIn(0, src.height - 1)
        val pw = (w * u).toInt().coerceIn(1, src.width - px)
        val ph = (h * u).toInt().coerceIn(1, src.height - py)

        val dst = if (into != null && into.width == pw && into.height == ph) {
            into
        } else {
            Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        }
        Canvas(dst).apply {
            drawColor(android.graphics.Color.BLACK)
            drawBitmap(src, -px.toFloat(), -py.toFloat(), null)
        }
        return dst
    }
}

/**
 * The phone holding the picture renders it once, shows its own half, and sends
 * the other half. There is no clock: a picture does not move.
 */
@Composable
private fun PictureHostSurface(session: Session) {
    val world = session.world
    val density = LocalDensity.current.density
    val calib = session.calib
    val pic = session.picture
    val gap = session.gapMm

    var mine by remember { mutableStateOf<ImageBitmap?>(null) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }

    // Cut the two halves whenever the picture, the layout, or the gap changes.
    LaunchedEffect(pic, gap, world.totalW, world.h, world.sliceX, world.sliceY) {
        val src = pic ?: return@LaunchedEffect
        val u = density * calib
        val halves = withContext(Dispatchers.Default) { cutHalves(src, world, u) }
            ?: return@LaunchedEffect
        mine = halves.first.asImageBitmap()
        pending = halves.second
    }

    // A viewer can arrive after the picture was cut, so the bytes are kept and
    // sent again. Encoding happens once; this only writes to a socket.
    LaunchedEffect(pending) {
        val bytes = pending ?: return@LaunchedEffect
        while (isActive) {
            if (session.streamer.connected) {
                withContext(Dispatchers.IO) { session.streamer.send(bytes) }
            }
            delay(500)
        }
    }

    Box(Modifier.fillMaxSize().background(Night), contentAlignment = Alignment.Center) {
        val shown = mine
        if (shown != null) {
            Image(
                bitmap = shown,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("Choose a picture or a PDF", color = Color(0x99FFFFFF), fontSize = 16.sp)
        }
    }
}

/**
 * The two halves of one picture, plus the other phone's half already encoded.
 * The bytes are made once and kept, because a picture does not change.
 */
private fun cutHalves(src: Bitmap, world: World, u: Float): Pair<Bitmap, ByteArray?>? {
    fun cut(x: Float, y: Float, w: Float, h: Float): Bitmap? {
        val px = (x * u).toInt().coerceIn(0, src.width - 1)
        val py = (y * u).toInt().coerceIn(0, src.height - 1)
        val pw = (w * u).toInt().coerceIn(1, src.width - px)
        val ph = (h * u).toInt().coerceIn(1, src.height - py)
        return runCatching { Bitmap.createBitmap(src, px, py, pw, ph) }.getOrNull()
    }
    val mine = cut(world.sliceX, world.sliceY, world.sliceW, world.sliceH) ?: return null
    val theirs = cut(world.peerX, world.peerY, world.peerW, world.peerH)
    val jpeg = theirs?.let {
        ByteArrayOutputStream().also { out -> it.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            .toByteArray()
    }
    theirs?.recycle()
    return mine to jpeg
}

/**
 * The viewer draws the frames the host sends. It holds no browser, so what it
 * shows is exactly what the host laid out — the two halves cannot disagree.
 */
@Composable
private fun RemoteViewerSurface(session: Session) {
    val scope = rememberCoroutineScope()
    val host = session.peerAddress
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var lost by remember { mutableStateOf(false) }

    DisposableEffect(host) {
        val client = FrameClient(scope, session.ports)
        if (host != null) {
            client.connect(
                host = host,
                onFrame = { bytes ->
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        val image = bmp.asImageBitmap()
                        scope.launch(Dispatchers.Main) {
                            frame = image
                            lost = false
                        }
                    }
                },
                onLost = { scope.launch(Dispatchers.Main) { lost = true } },
            )
        } else {
            lost = true
        }
        onDispose { client.stop() }
    }

    Box(Modifier.fillMaxSize().background(Night), contentAlignment = Alignment.Center) {
        val shown = frame
        if (shown != null) {
            // The frame is exactly this phone's slice, so it fills the screen.
            Image(
                bitmap = shown,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                if (lost) "Waiting for the host" else "Connecting",
                color = Color(0x99FFFFFF), fontSize = 16.sp,
            )
        }
    }
}

// ---------------------------------------------------------------- widgets

@Composable
private fun clipPicker(
    onPicked: (Uri, String) -> Unit,
): ManagedActivityResultLauncher<PickVisualMediaRequest, Uri?> {
    val context = LocalContext.current
    return rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPicked(uri, displayName(context, uri))
    }
}

@Composable
private fun documentPicker(
    onPicked: (Uri, String) -> Unit,
): ManagedActivityResultLauncher<Array<String>, Uri?> {
    val context = LocalContext.current
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(uri, displayName(context, uri))
    }
}

/** Android renders PDF pages itself, so a document needs no extra library. */
private val DOCUMENT_TYPES = arrayOf("image/*", "application/pdf")

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

private fun shortName(name: String, max: Int): String =
    if (name.length <= max) name else name.take(max - 1) + "…"

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 20.sp, color = Ink, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun BigButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) Accent else Color.Transparent)
            .border(1.dp, if (primary) Accent else Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (primary) Color.White else Ink,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(AccentSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(label, color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF2F2F2))
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) CardBg else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (on) Ink else Sub,
                    fontSize = 15.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 16.sp, color = Ink, modifier = Modifier.weight(1f))
        StepChip("−", onMinus)
        Text(
            value,
            fontSize = 16.sp,
            color = Ink,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(74.dp),
        )
        StepChip("+", onPlus)
    }
}

@Composable
private fun StepChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .width(46.dp)
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .border(1.dp, Line, RoundedCornerShape(23.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Ink, fontSize = 20.sp)
    }
}
