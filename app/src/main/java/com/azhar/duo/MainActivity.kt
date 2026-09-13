package com.azhar.duo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val session = remember { Session(scope) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Night)) {
        val wDp = maxWidth.value
        val hDp = maxHeight.value
        when (session.phase) {
            Phase.Menu -> MenuScreen(session, wDp, hDp)
            Phase.Waiting, Phase.Dead -> WaitingScreen(session)
            Phase.Live -> LiveCanvas(session, wDp, hDp)
        }
    }

    BackHandler(enabled = session.phase != Phase.Menu) { session.reset() }
}

// ---------------------------------------------------------------- menu

@Composable
private fun MenuScreen(session: Session, wDp: Float, hDp: Float) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("DUO", fontSize = 64.sp, color = Seam, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(4.dp))
        Text(
            "two phones · one canvas",
            color = Dim, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
        )

        Spacer(Modifier.height(56.dp))
        DuoButton("HOST   ·   left half", primary = true) { session.startHost(wDp, hDp) }
        Spacer(Modifier.height(12.dp))
        DuoButton("JOIN   ·   right half", primary = false) { session.startJoin(wDp, hDp) }

        Spacer(Modifier.height(64.dp))
        Text(
            "SIZE CALIBRATION   ${String.format(Locale.US, "%.2f", session.calib)}×",
            color = Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("−") { session.calib = (session.calib - 0.02f).coerceIn(0.70f, 1.40f) }
            Spacer(Modifier.width(8.dp))
            StepButton("100%") { session.calib = 1f }
            Spacer(Modifier.width(8.dp))
            StepButton("+") { session.calib = (session.calib + 0.02f).coerceIn(0.70f, 1.40f) }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Set the same on both phones until the grid squares are the same\n" +
                "physical size. Only needed if the panels disagree.",
            color = Faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center, lineHeight = 16.sp,
        )
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .border(1.dp, GridBold, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(label, color = Dim, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
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
            Spacer(Modifier.height(60.dp))
            Text("host ip", color = Faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
            Text(ip, color = Dim, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(56.dp))
        StepButton("back") { session.reset() }
    }
}

// ---------------------------------------------------------------- live

@Composable
private fun LiveCanvas(session: Session, wDp: Float, hDp: Float) {
    val world = session.world
    val density = LocalDensity.current.density

    // The ball lives in World (plain Kotlin, no snapshot state). Mirror it into
    // Compose state once per frame so the Canvas redraws.
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

    // Logical units per screen pixel, and where this phone's slice starts.
    // sliceW == wDp / calib, and the canvas is wDp * density px wide, so this
    // collapses to density * calib.
    val unitsPerPx = 1f / (density * session.calib)
    val padY = (hDp - world.h * session.calib) * density / 2f

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

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
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
    ) {
        val u = size.width / world.sliceW
        val padPx = (size.height - world.h * u) / 2f

        clipRect {
            translate(left = -world.sliceX * u, top = padPx) {
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

                // Sits on the seam, so it is drawn half here and half on the neighbour.
                val cx = world.totalW / 2f * u
                val cy = world.h / 2f * u
                drawCircle(GridBold, radius = 60f * u, center = Offset(cx, cy), style = Stroke(2f))

                val c = Offset(ballX * u, ballY * u)
                drawCircle(BallColor.copy(alpha = 0.16f), radius = BALL_R * u * 2.2f, center = c)
                drawCircle(BallColor, radius = BALL_R * u, center = c)
            }
        }

        // Mark this phone's inner edge so the physical bezel is accounted for.
        val onLeft = world.sliceX == 0f
        drawRect(
            Seam.copy(alpha = 0.55f),
            topLeft = Offset(if (onLeft) size.width - 3f else 0f, padPx),
            size = Size(3f, world.h * u),
        )
    }
}

// ---------------------------------------------------------------- widgets

@Composable
private fun DuoButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .border(1.dp, if (primary) Seam else GridBold, RoundedCornerShape(12.dp))
            .background(if (primary) Seam.copy(alpha = 0.10f) else Color.Transparent, RoundedCornerShape(12.dp))
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
