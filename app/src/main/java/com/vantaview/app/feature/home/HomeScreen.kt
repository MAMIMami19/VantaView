// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.home

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.vantaview.app.data.db.ChannelEntity
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Yellow = Color(0xFFFFC81A)

@OptIn(UnstableApi::class)
@Composable
fun HomeScreen(
    onSection: (String) -> Unit,
    onOpenTitle: (type: String, id: String, name: String, poster: String?, play: String?) -> Unit,
    onPlayChannel: (Long) -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val tiles by vm.tiles.collectAsStateWithLifecycle()
    val cont by vm.continueWatching.collectAsStateWithLifecycle()
    val favs by vm.favourites.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val newSeason by vm.newSeason.collectAsStateWithLifecycle()

    // The muted preview only runs while Home is on screen (it shares the provider connection).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.stopPreview() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.startPreview() }

    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(15_000) } }

    BoxWithConstraints(Modifier.fillMaxSize().background(Vanta.Bg)) {
        val wide = maxWidth >= 720.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(SimpleDateFormat("HH:mm", Locale.UK).format(now), color = Vanta.Text, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text(SimpleDateFormat("EEEE d MMMM", Locale.UK).format(now), color = Vanta.TextDim, fontSize = 14.sp)
            }

            val previewBox: @Composable (Modifier) -> Unit = { m ->
                PreviewBox(preview, vm, m, onFull = { preview?.channel?.id?.let(onPlayChannel) })
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(220.dp)) {
                    tiles.forEach { (route, t) -> SectionTile(t, Modifier.width(170.dp).fillMaxHeight()) { onSection(route) } }
                    previewBox(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                previewBox(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(tiles, key = { it.first }) { (route, t) -> SectionTile(t, Modifier.width(140.dp).height(170.dp)) { onSection(route) } }
                }
            }

            if (cont.isNotEmpty()) {
                Text("Continue watching", color = Vanta.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(cont, key = { it.entry.key }) { c ->
                        ContinueCard(c, Modifier.width(if (wide) 256.dp else 200.dp)) {
                            onOpenTitle(c.entry.type, c.entry.metaId, c.entry.title, c.entry.poster, c.playVideoId?.let { v -> if (c.entry.type == "movie") "movie" else v })
                        }
                    }
                }
            }

            if (favs.isNotEmpty()) {
                Text("Favourite channels", color = Vanta.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(favs, key = { it.id }) { ch -> ChannelCard(ch) { onPlayChannel(ch.id) } }
                }
            }

            if (cont.isEmpty() && favs.isEmpty()) {
                Text(
                    "Films and episodes you start appear under Continue watching, and channels you star appear here as favourites.",
                    color = Vanta.TextDim, fontSize = 14.sp,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    newSeason?.let { ns ->
        AlertDialog(
            onDismissRequest = vm::dismissNewSeason,
            containerColor = Vanta.Surface,
            title = null,
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.width(110.dp).height(165.dp).clip(RoundedCornerShape(10.dp)).background(Vanta.SurfaceHigh)) {
                        ns.entry.poster?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        Badge("NEW SEASON", Yellow, Modifier.padding(6.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("SEASON ${ns.season} IS HERE", color = Yellow, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(ns.entry.title, color = Vanta.Text, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 2)
                        Text(
                            "${ns.episodes} new episode" + (if (ns.episodes == 1) " is" else "s are") +
                                " ready to watch. Pick up with S${ns.season} E${ns.first.episode}.",
                            color = Vanta.TextDim,
                        )
                    }
                }
            },
            confirmButton = {
                GradientButton("▶  Watch now", onClick = {
                    vm.dismissNewSeason()
                    onOpenTitle("series", ns.entry.metaId, ns.entry.title, ns.entry.poster, ns.first.id)
                })
            },
            dismissButton = { TextButton(onClick = vm::dismissNewSeason) { Text("Later") } },
        )
    }
}

@Composable
private fun Badge(text: String, colour: Color, modifier: Modifier = Modifier) {
    Text(
        text, color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Black,
        modifier = modifier.background(colour, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun SectionTile(t: Tile, modifier: Modifier, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = modifier, padding = PaddingValues(10.dp), contentAlignment = Alignment.TopStart) {
        Column(Modifier.fillMaxSize()) {
            // 2 x 2 mosaic of channel logos or posters.
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in 0..1) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in 0..1) {
                        val img = t.images.getOrNull(r * 2 + c)
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Vanta.SurfaceHigh)) {
                            if (img != null) AsyncImage(
                                model = img, contentDescription = null,
                                contentScale = if (t.title == "LIVE TV") ContentScale.Fit else ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().padding(if (t.title == "LIVE TV") 6.dp else 0.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(t.title, color = Vanta.Text, fontWeight = FontWeight.Black, fontSize = 15.sp)
            Text(t.subtitle, color = Vanta.TextDim, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PreviewBox(p: Preview?, vm: HomeViewModel, modifier: Modifier, onFull: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Vanta.Surface)) {
        if (p == null) {
            Text(
                "Watch a live channel and it will play here.",
                color = Vanta.TextDim, modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
            return@Box
        }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    player = vm.player
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        Badge("LIVE", Color(0xFFFF3B4E), Modifier.padding(10.dp))
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(p.channel.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.error ?: (p.channel.categoryRemoteId?.let { "Last watched" } ?: "Last watched"), color = Vanta.TextDim, fontSize = 12.sp)
            }
            FocusCard(onClick = vm::toggleMute, shape = CircleShape, padding = PaddingValues(10.dp), contentAlignment = Alignment.Center) {
                Text(if (p.muted) "🔇" else "🔊", fontSize = 16.sp)
            }
            Spacer(Modifier.width(8.dp))
            FocusCard(onClick = onFull, shape = CircleShape, padding = PaddingValues(10.dp), contentAlignment = Alignment.Center) {
                Text("⛶", color = Color.White, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun ContinueCard(c: ContinueItem, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier) {
        FocusCard(onClick = onClick, padding = PaddingValues(0.dp), modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Vanta.SurfaceHigh)) {
                (c.entry.background ?: c.entry.poster)?.let {
                    AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                when (c.badge) {
                    Badge.NEW_SEASON -> Badge("NEW SEASON", Yellow, Modifier.padding(8.dp))
                    Badge.COMING_SOON -> Badge("COMING SOON", Vanta.TextDim, Modifier.padding(8.dp))
                    null -> Unit
                }
                c.progress?.let { f ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF))) {
                        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(Vanta.GradientBrush))
                    }
                }
            }
        }
        Text(c.entry.title, color = Vanta.Text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(c.line, color = if (c.badge == Badge.NEW_SEASON) Yellow else Vanta.TextDim, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun ChannelCard(ch: ChannelEntity, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = Modifier.width(220.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Vanta.Bg), contentAlignment = Alignment.Center) {
                if (ch.logo != null) AsyncImage(model = ch.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
                else Text(ch.name.take(2).uppercase(), color = Vanta.TextDim, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(ch.name, color = Vanta.Text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Channel ${ch.number}", color = Vanta.TextDim, fontSize = 12.sp)
            }
        }
    }
}
