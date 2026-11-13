// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.vantaview.app.data.addon.Video
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta

@Composable
fun DetailsScreen(onPlay: () -> Unit, onBack: () -> Unit, vm: DetailsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.play.collect { onPlay() } }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.refreshProgress() }

    val meta = ui.meta
    val name = meta?.name?.ifBlank { null } ?: vm.fallbackName
    val poster = meta?.poster ?: vm.fallbackPoster
    val isSeries = vm.type == "series"

    BoxWithConstraints(Modifier.fillMaxSize().background(Vanta.Bg)) {
        val wide = maxWidth >= 720.dp
        meta?.background?.let { bg ->
            AsyncImage(
                model = bg, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(if (wide) 380.dp else 240.dp),
            )
            Box(
                Modifier.fillMaxWidth().height(if (wide) 380.dp else 240.dp)
                    .background(Brush.verticalGradient(listOf(Color(0x6607080F), Vanta.Bg)))
            )
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                FocusCard(onClick = onBack) { Text("‹ Back", color = Vanta.Text) }
            }
            if (ui.blocked) {
                item { Text("This title isn't available on a Kids profile.", color = Vanta.Text, fontSize = 18.sp) }
                return@LazyColumn
            }
            item {
                Row(verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.width(if (wide) 170.dp else 110.dp).height(if (wide) 255.dp else 165.dp).clip(RoundedCornerShape(12.dp)).background(Vanta.SurfaceHigh)) {
                        if (poster != null) AsyncImage(model = poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(name, color = Vanta.Text, fontSize = if (wide) 30.sp else 22.sp, fontWeight = FontWeight.Black, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        val facts = listOfNotNull(meta?.year, meta?.runtime, meta?.rating?.let { "IMDb $it" }).joinToString("  ·  ")
                        if (facts.isNotEmpty()) Text(facts, color = Vanta.TextDim, fontSize = 14.sp)
                        if (!meta?.genres.isNullOrEmpty()) Text(meta!!.genres.joinToString(", "), color = Vanta.TextDim, fontSize = 13.sp)
                    }
                }
            }
            if (isSeries) vm.continueVideo()?.let { v ->
                item {
                    val p = ui.progress
                    val code = "S${v.season} E${v.episode}"
                    val label = when {
                        ui.finding == v.id -> "Finding streams…"
                        p != null && p.videoId == v.id && p.resumable -> "▶  Resume $code · ${minutesLeft(p.remainingMs)}"
                        else -> "▶  Play $code"
                    }
                    GradientButton(label, onClick = { vm.play(v, false) }, enabled = ui.finding == null)
                }
            }
            if (!isSeries) item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val p = ui.progress?.takeIf { it.resumable }
                    GradientButton(
                        when {
                            ui.finding != null -> "Finding streams…"
                            p != null -> "▶  Resume · ${minutesLeft(p.remainingMs)}"
                            else -> "▶  Play"
                        },
                        onClick = { vm.play(null, false) }, enabled = ui.finding == null,
                    )
                    FocusCard(onClick = { vm.play(null, true) }) { Text("Choose stream", color = Vanta.Text) }
                }
            }
            ui.message?.let { m -> item { Text(m, color = Vanta.Error) } }
            meta?.description?.let { d -> item { Text(d, color = Vanta.Text, fontSize = 15.sp, lineHeight = 22.sp) } }
            if (!meta?.director.isNullOrEmpty()) item { Text("Director: " + meta!!.director.joinToString(", "), color = Vanta.TextDim, fontSize = 14.sp) }
            if (!meta?.cast.isNullOrEmpty()) item { Text("Cast: " + meta!!.cast.take(8).joinToString(", "), color = Vanta.TextDim, fontSize = 14.sp) }

            if (ui.loading) item { Text("Loading details…", color = Vanta.TextDim) }
            else if (meta == null) item {
                Text(
                    "None of your add-ons has details for this title" + if (isSeries) ", so its episodes can't be listed. Cinemeta usually fills this in." else ".",
                    color = Vanta.TextDim,
                )
            }

            if (isSeries && meta != null) {
                val seasons = meta.videos.map { it.season }.distinct().sorted()
                if (seasons.size > 1) item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons) { s ->
                            FocusCard(onClick = { vm.selectSeason(s) }, selected = ui.season == s) {
                                Text(if (s == 0) "Specials" else "Season $s", color = if (ui.season == s) Vanta.Cyan else Vanta.Text)
                            }
                        }
                    }
                }
                val episodes = meta.videos.filter { ui.season == null || it.season == ui.season }
                if (episodes.isEmpty()) item { Text("No episodes listed.", color = Vanta.TextDim) }
                items(episodes, key = { it.id }) { v ->
                    val p = ui.progress?.takeIf { it.videoId == v.id }
                    val note = when {
                        p == null -> null
                        p.finished -> "✓ Watched"
                        p.resumable -> "Resume · ${minutesLeft(p.remainingMs)}"
                        else -> null
                    }
                    EpisodeRow(v, finding = ui.finding == v.id, note = note, onPlay = { vm.play(v, false) }, onChoose = { vm.play(v, true) })
                }
            }
        }
    }

    ui.choice?.let { c ->
        StreamListDialog(
            title = listOfNotNull(c.title, c.subtitle).joinToString(" · "),
            streams = c.streams, current = null, onPick = vm::pick, onDismiss = vm::dismissChoice,
        )
    }
}

@Composable
private fun EpisodeRow(v: Video, finding: Boolean, note: String?, onPlay: () -> Unit, onChoose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FocusCard(onClick = onPlay, modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 112.dp, height = 63.dp).clip(RoundedCornerShape(8.dp)).background(Vanta.Bg)) {
                    if (v.thumbnail != null) AsyncImage(model = v.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${v.episode}. ${v.title.ifBlank { "Episode ${v.episode}" }}",
                        color = Vanta.Text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            finding -> "Finding streams…"
                            note != null -> note
                            else -> v.overview ?: v.released?.take(10) ?: ""
                        },
                        color = if (finding || note != null) Vanta.Cyan else Vanta.TextDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        FocusCard(onClick = onChoose, padding = PaddingValues(12.dp), contentAlignment = Alignment.Center) {
            Text("⋯", color = Vanta.Text, fontSize = 18.sp)
        }
    }
}

fun minutesLeft(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(1)
    return if (m >= 60) "${m / 60} h ${m % 60} min left" else "$m min left"
}
