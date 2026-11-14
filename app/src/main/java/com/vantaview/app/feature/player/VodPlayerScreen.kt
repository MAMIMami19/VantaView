// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.player

import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.vantaview.app.feature.vod.StreamListDialog
import com.vantaview.app.feature.vod.qualityLabel
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun VodPlayerScreen(onBack: () -> Unit, vm: VodPlayerViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val isTv = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    var controls by remember { mutableStateOf(true) }
    var picking by remember { mutableStateOf(false) }

    // Full screen. The screen keeps following the device's own rotation (and its rotation lock).
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // Back: the first press hides the controls, the next one leaves the player.
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    BackHandler(enabled = controls && !picking && ui.failed == null && ui.preparing == null) { playerView?.hideController() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pause() }
    LaunchedEffect(ui.notice) { if (ui.notice != null) { delay(4000); vm.clearNotice() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    // Subtitles button (the settings button already offers audio tracks when there are several).
                    setShowSubtitleButton(true)
                    TrackPrefs.style(this, vm.settings)
                    // Settings › Playback: how long the controls stay (0 = until hidden).
                    controllerShowTimeoutMs = vm.settings.controlsSeconds.let { if (it <= 0) 0 else it * 1000 }
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controls = v == android.view.View.VISIBLE })
                    player = vm.player
                    requestFocus()
                    playerView = this
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        // AI subtitles (beta), kept clear of the playback controls.
        vm.ai?.let { AiCaption(it, vm.settings, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = if (controls) 120.dp else 28.dp)) }

        if (controls || ui.failed != null || ui.preparing != null) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isTv) {
                    FocusCard(onClick = onBack) { Text("‹ Back", color = Vanta.Text) }
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(ui.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ui.subtitle?.let { Text(it, color = Vanta.TextDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (ui.streams.size > 1) {
                    val cur = ui.streams.getOrNull(ui.index)
                    FocusCard(onClick = { picking = true }) {
                        Text("Streams" + (cur?.let { "  (${qualityLabel(it)})" } ?: ""), color = Vanta.Text)
                    }
                }
            }
        }

        Column(
            Modifier.align(Alignment.Center).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ui.preparing?.let { p ->
                Text(
                    p, color = Vanta.Cyan, fontSize = 17.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(12.dp),
                )
                if (ui.streams.size > 1) FocusCard(onClick = { picking = true }) { Text("Choose another stream", color = Vanta.Text) }
            }
            ui.failed?.let { f ->
                Text(f, color = Vanta.Error, fontSize = 17.sp, modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(10.dp))
                if (ui.streams.size > 1) FocusCard(onClick = { picking = true }) { Text("Choose another stream", color = Vanta.Text) }
            }
        }
        ui.notice?.let { n ->
            Text(
                n, color = Vanta.Cyan,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(10.dp),
            )
        }
    }

    if (picking) {
        StreamListDialog(
            title = "Streams",
            streams = ui.streams,
            current = ui.index,
            onPick = { i -> picking = false; vm.switchTo(i) },
            onDismiss = { picking = false },
        )
    }
}
