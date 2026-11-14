// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.player

import android.app.Activity
import android.content.pm.PackageManager
import androidx.annotation.OptIn
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.vantaview.app.data.ChannelNames
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
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


    // Full screen on an upright phone (double-tap the video, or double-tap a channel in the list).
    var full by remember { mutableStateOf(false) }

    // Back: hides the controls first, then leaves full screen, then leaves the player.
    BackHandler(enabled = full) { full = false }
    BackHandler(enabled = overlay) { overlay = false }

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

    val list by vm.list.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        // Phones held upright get the video on top and the channel list underneath; otherwise full screen.
        // The screen follows the device's own rotation (and its rotation lock).
        val upright = !isTv && maxHeight > maxWidth
        val portrait = upright && !full
        DisposableEffect(portrait) {
            val window = activity?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (portrait) controller?.show(WindowInsetsCompat.Type.systemBars())
            else controller?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
        }

        val video: @Composable (Modifier) -> Unit = { m ->
            VideoArea(
                vm = vm, ui = ui, isTv = isTv, overlay = overlay, compact = portrait, focus = focus,
                modifier = m, onTap = ::tapped, onZap = ::zap, onBack = onBack,
                onDoubleTap = { if (upright) full = !full },
            )
        }
        if (portrait) {
            Column(Modifier.fillMaxSize().background(Vanta.Bg)) {
                video(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                NowStrip(ui)
                PortraitChannelList(
                    list, current, Modifier.weight(1f).fillMaxWidth(),
                    onPick = { i ->
                        vm.playAt(i)
                        overlay = true
                        overlayTick++
                    },
                    onFull = { i ->
                        vm.playAt(i)
                        full = true
                    },
                )
            }
        } else {
            video(Modifier.fillMaxSize())
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoArea(
    vm: PlayerViewModel,
    ui: PlayerUi,
    isTv: Boolean,
    overlay: Boolean,
    compact: Boolean,
    focus: FocusRequester,
    modifier: Modifier,
    onTap: () -> Unit,
    onZap: (Int) -> Unit,
    onBack: () -> Unit,
    onDoubleTap: () -> Unit,
) {
    val density = LocalDensity.current
    val swipe = with(density) { 60.dp.toPx() }
    Box(
        modifier
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { e ->
                val handled = when (e.key) {
                    Key.DirectionUp, Key.ChannelUp, Key.PageUp, Key.MediaNext -> 1
                    Key.DirectionDown, Key.ChannelDown, Key.PageDown, Key.MediaPrevious -> -1
                    else -> 0
                }
                if (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) {
                    if (e.type == KeyEventType.KeyUp) onTap()
                    return@onKeyEvent true
                }
                if (handled == 0) return@onKeyEvent false
                if (e.type == KeyEventType.KeyDown) onZap(handled)
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

        // Tap (or OK on a remote) shows and hides the overlay; swipe up or down on the video changes channel.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { onDoubleTap() }) }
                .pointerInput(ui.hasSiblings) {
                    if (!ui.hasSiblings) return@pointerInput
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            when {
                                total < -swipe -> onZap(1)
                                total > swipe -> onZap(-1)
                            }
                        },
                        onVerticalDrag = { change, dy -> change.consume(); total += dy },
                    )
                }
        )

        if (overlay) {
            val pad = if (compact) 12.dp else 20.dp
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .padding(horizontal = pad, vertical = if (compact) 8.dp else 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!isTv) {
                        FocusCard(onClick = onBack) { Text("‹ Back", color = Vanta.Text) }
                        Spacer(Modifier.width(14.dp))
                    }
                    Text(ui.number.toString(), color = Vanta.Cyan, fontSize = if (compact) 15.sp else 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        ui.title, color = Color.White, fontSize = if (compact) 16.sp else 20.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }

                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                        .padding(horizontal = pad, vertical = if (compact) 8.dp else 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!compact && ui.now != null && ui.status == PlayStatus.PLAYING) {
                        NowInfo(ui, Modifier.fillMaxWidth().padding(bottom = 10.dp))
                    }
                    when (ui.status) {
                        PlayStatus.LOADING -> Text(if (ui.eventOnly) "Looking for the event…" else "Loading…", color = Color.White)
                        PlayStatus.RECONNECTING -> Text(
                            "Reconnecting (try ${ui.attempt} of 5)…",
                            color = Vanta.Cyan,
                            modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(8.dp),
                        )
                        PlayStatus.FAILED -> {
                            Text(
                                ui.message ?: "This channel could not be played",
                                color = if (ui.eventOnly) Color.White else Vanta.Error,
                                fontSize = if (compact) 14.sp else 17.sp,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
                            if (!isTv) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                GradientButton("Try again", onClick = vm::retryNow)
                                if (ui.hasSiblings) FocusCard(onClick = { onZap(1) }) { Text("Next channel", color = Vanta.Text) }
                            }
                        }
                        PlayStatus.PLAYING -> Unit
                    }
                    if (!compact || ui.status == PlayStatus.PLAYING) Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
                    if (isTv) {
                        Text(
                            when {
                                ui.status == PlayStatus.FAILED -> "OK to try again   ·   ▲ ▼ change channel   ·   Back to exit"
                                ui.hasSiblings -> "▲ ▼ change channel   ·   Back to exit"
                                else -> "Back to exit"
                            },
                            color = Vanta.TextDim, fontSize = 14.sp,
                        )
                    } else if (ui.hasSiblings && ui.status != PlayStatus.FAILED) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            FocusCard(onClick = { onZap(-1) }) { Text("▼ Previous", color = Vanta.Text) }
                            if (!compact) Text("or swipe up and down", color = Vanta.TextDim, fontSize = 13.sp)
                            FocusCard(onClick = { onZap(1) }) { Text("▲ Next", color = Vanta.Text) }
                        }
                    }
                }
            }
        }
    }
}

/** The category's channels under the video when the phone is upright. Tap one to switch to it. */
@Composable
private fun PortraitChannelList(
    channels: List<com.vantaview.app.data.db.ChannelEntity>,
    current: Int,
    modifier: Modifier,
    onPick: (Int) -> Unit,
    onFull: (Int) -> Unit,
) {
    val state = rememberLazyListState()
    LaunchedEffect(current, channels.size) {
        if (channels.isNotEmpty()) state.animateScrollToItem((current - 2).coerceAtLeast(0))
    }
    LazyColumn(
        modifier,
        state = state,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        itemsIndexed(channels, key = { _, c -> c.id }) { i, c ->
            val event = ChannelNames.isEventOnly(c.name)
            // Tap to switch; double-tap to switch and go full screen.
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (i == current) Vanta.SurfaceHigh else Vanta.Surface)
                    .pointerInput(i) { detectTapGestures(onTap = { onPick(i) }, onDoubleTap = { onFull(i) }) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (event && i != current) 0.65f else 1f)) {
                    Text(c.number.toString(), color = Vanta.TextDim, fontSize = 13.sp, modifier = Modifier.width(44.dp), maxLines = 1)
                    Box(
                        Modifier.size(width = 52.dp, height = 32.dp).clip(RoundedCornerShape(6.dp)).background(Vanta.Bg),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (c.logo != null) AsyncImage(
                            model = c.logo, contentDescription = null, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(2.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        ChannelNames.display(c.name) + if (i == current) "  · now playing" else "",
                        color = if (i == current) Vanta.Cyan else Vanta.Text,
                        fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val hhmm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK)

/** Now: title, times and progress, then Next. Used over the video in full screen. */
@Composable
private fun NowInfo(ui: PlayerUi, modifier: Modifier = Modifier) {
    val now = ui.now ?: return
    val t = System.currentTimeMillis()
    Column(modifier) {
        Text(
            "Now: ${now.title}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${hhmm.format(java.util.Date(now.start))} – ${hhmm.format(java.util.Date(now.stop))}", color = Vanta.TextDim, fontSize = 13.sp)
            Spacer(Modifier.width(10.dp))
            val f = ((t - now.start).toFloat() / (now.stop - now.start).coerceAtLeast(1)).coerceIn(0f, 1f)
            Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x55FFFFFF))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Vanta.Cyan))
            }
        }
        ui.next?.let { n ->
            Text("Next: ${hhmm.format(java.util.Date(n.start))} ${n.title}", color = Vanta.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Under the video on an upright phone: what's on now with its description, and what's next. */
@Composable
private fun NowStrip(ui: PlayerUi) {
    val now = ui.now
    Column(Modifier.fillMaxWidth().background(Vanta.Surface).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ui.number.toString(), color = Vanta.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(ui.title, color = Vanta.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (now == null) {
            Text("No guide information for this channel", color = Vanta.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            return@Column
        }
        Spacer(Modifier.height(4.dp))
        NowInfo(ui.copy(next = null))
        now.description?.let {
            Text(it, color = Vanta.TextDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        ui.next?.let { n ->
            Text("Next: ${hhmm.format(java.util.Date(n.start))} ${n.title}", color = Vanta.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
