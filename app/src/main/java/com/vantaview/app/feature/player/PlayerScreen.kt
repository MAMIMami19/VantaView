// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(onBack: () -> Unit, vm: PlayerViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val isTv = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    var overlay by remember { mutableStateOf(true) }
    var overlayTick by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }

    // Hide the overlay a few seconds after it was last shown, unless something needs attention.
    LaunchedEffect(overlay, overlayTick, ui.status) {
        if (overlay && ui.status == PlayStatus.PLAYING) {
            delay(4000)
            overlay = false
        }
    }
    LaunchedEffect(ui.status) { if (ui.status != PlayStatus.PLAYING) overlay = true }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // Full screen, and landscape on phones and tablets.
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        val previous = activity?.requestedOrientation
        if (!isTv) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (!isTv && previous != null) activity?.requestedOrientation = previous
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.resume() }

    fun zap(delta: Int) {
        vm.step(delta)
        overlay = true
        overlayTick++
    }

    // A tap, or OK on a remote: retry if the channel failed, otherwise show or hide the overlay.
    fun tapped() {
        if (ui.status == PlayStatus.FAILED && isTv) vm.retryNow() else overlay = !overlay
        overlayTick++
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { e ->
                val handled = when (e.key) {
                    Key.DirectionUp, Key.ChannelUp, Key.PageUp, Key.MediaNext -> 1
                    Key.DirectionDown, Key.ChannelDown, Key.PageDown, Key.MediaPrevious -> -1
                    else -> 0
                }
                if (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) {
                    if (e.type == KeyEventType.KeyUp) tapped()
                    return@onKeyEvent true
                }
                if (handled == 0) return@onKeyEvent false
                if (e.type == KeyEventType.KeyDown) zap(handled)
                true
            }
            .focusable()
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    player = vm.player
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        // Tap (or OK on a remote) shows and hides the overlay.
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { tapped() }) })

        if (overlay) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!isTv) {
                        FocusCard(onClick = onBack) { Text("‹ Back", color = Vanta.Text) }
                        Spacer(Modifier.width(14.dp))
                    }
                    Text(ui.number.toString(), color = Vanta.Cyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    Text(ui.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }

                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (ui.status) {
                        PlayStatus.LOADING -> Text("Loading…", color = Color.White)
                        PlayStatus.RECONNECTING -> Text(
                            "Reconnecting (try ${ui.attempt} of 5)…",
                            color = Vanta.Cyan,
                            modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(8.dp),
                        )
                        PlayStatus.FAILED -> {
                            Text(ui.message ?: "This channel could not be played", color = Vanta.Error, fontSize = 17.sp)
                            Spacer(Modifier.height(10.dp))
                            if (!isTv) GradientButton("Try again", onClick = vm::retryNow)
                        }
                        PlayStatus.PLAYING -> Unit
                    }
                    Spacer(Modifier.height(10.dp))
                    if (isTv) {
                        Text(
                            when {
                                ui.status == PlayStatus.FAILED -> "OK to try again   ·   ▲ ▼ change channel   ·   Back to exit"
                                ui.hasSiblings -> "▲ ▼ change channel   ·   Back to exit"
                                else -> "Back to exit"
                            },
                            color = Vanta.TextDim, fontSize = 14.sp,
                        )
                    } else if (ui.hasSiblings) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            FocusCard(onClick = { zap(-1) }) { Text("▼ Previous", color = Vanta.Text) }
                            FocusCard(onClick = { zap(1) }) { Text("▲ Next", color = Vanta.Text) }
                        }
                    }
                }
            }
        }
    }
}
