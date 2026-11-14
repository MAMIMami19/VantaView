// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.live

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.vantaview.app.data.ChannelNames
import com.vantaview.app.data.db.CategoryEntity
import com.vantaview.app.data.db.ChannelEntity
import com.vantaview.app.data.db.ChannelRow
import com.vantaview.app.data.db.ProgrammeEntity
import com.vantaview.app.data.repo.SyncState
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Digit keys on a remote or keyboard, for typing a channel number. */
private val digitKeys = mapOf(
    Key.Zero to 0, Key.One to 1, Key.Two to 2, Key.Three to 3, Key.Four to 4,
    Key.Five to 5, Key.Six to 6, Key.Seven to 7, Key.Eight to 8, Key.Nine to 9,
    Key.NumPad0 to 0, Key.NumPad1 to 1, Key.NumPad2 to 2, Key.NumPad3 to 3, Key.NumPad4 to 4,
    Key.NumPad5 to 5, Key.NumPad6 to 6, Key.NumPad7 to 7, Key.NumPad8 to 8, Key.NumPad9 to 9,
)

@Composable
fun LiveScreen(
    onPlay: (Long) -> Unit,
    onAddPlaylist: () -> Unit,
    onAddAddon: () -> Unit,
    vm: LiveViewModel = hiltViewModel(),
) {
    val providers by vm.providers.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val isKids by vm.isKids.collectAsStateWithLifecycle()
    val countryLabel by vm.countryLabel.collectAsStateWithLifecycle()
    val guideVersion by vm.guideVersion.collectAsStateWithLifecycle()
    val state = active?.let { sync[it.id] }

    // Re-read now and next every minute, and whenever a guide finishes loading.
    var minute by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); minute++ } }
    val guideTick = guideVersion * 100_000 + minute

    // Typing a channel number: digits collect for a moment, then that channel plays.
    var typed by remember { mutableStateOf("") }
    var notFound by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(typed) {
        if (typed.isEmpty()) return@LaunchedEffect
        delay(1_500)
        val n = typed.toIntOrNull()
        val id = n?.let { vm.channelIdByNumber(it) }
        if (id != null) onPlay(id) else notFound = typed
        typed = ""
    }
    LaunchedEffect(notFound) { if (notFound != null) { delay(2_000); notFound = null } }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Vanta.Bg).onKeyEvent { e ->
            val d = digitKeys[e.key] ?: return@onKeyEvent false
            if (providers.isEmpty()) return@onKeyEvent false
            if (e.type == KeyEventType.KeyDown && typed.length < 5) typed += d
            true
        }
    ) {
        val wide = maxWidth >= 720.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Header(
                wide = wide,
                playlistName = active?.name ?: "No playlist",
                providers = providers.map { it.id to it.name },
                countryLabel = countryLabel,
                showRefresh = providers.isNotEmpty(),
                onPickProvider = vm::selectProvider,
                onCountries = vm::editCountries,
                onRefresh = vm::refresh,
            )
            if (providers.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("No playlists yet", color = Vanta.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (isKids) "Ask a grown-up to add a playlist to this profile."
                            else "Add an IPTV playlist for live channels, or install add-ons for films and series.",
                            color = Vanta.TextDim,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.widthIn(max = 460.dp),
                        )
                        if (!isKids) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            com.vantaview.app.ui.GradientButton("Add a playlist", onClick = onAddPlaylist)
                            FocusCard(onClick = onAddAddon, contentAlignment = Alignment.Center) { Text("Add an add-on", color = Vanta.Text) }
                        }
                        if (!isKids) Box(Modifier.widthIn(max = 460.dp)) { com.vantaview.app.feature.playlist.FreeTvCard(onAdd = vm::addFreeTv) }
                    }
                }
                return@Column
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                label = { Text("Search channels or numbers") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            when (state) {
                is SyncState.Running -> Text("${state.stage}…", color = Vanta.Cyan, modifier = Modifier.padding(top = 8.dp))
                is SyncState.Failed -> Text(state.message, color = Vanta.Error, modifier = Modifier.padding(top = 8.dp))
                else -> Unit
            }
            Spacer(Modifier.height(10.dp))

            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LazyColumn(
                        Modifier.width(260.dp).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        item { FilterChip("All channels", filter == Filter.All, Modifier.fillMaxWidth()) { vm.filter.value = Filter.All } }
                        item { FilterChip("★ Favourites", filter == Filter.Favourites, Modifier.fillMaxWidth()) { vm.filter.value = Filter.Favourites } }
                        items(categories, key = { it.id }) { c ->
                            FilterChip(c.name, filter == Filter.Category(c.remoteId), Modifier.fillMaxWidth()) {
                                vm.filter.value = Filter.Category(c.remoteId)
                            }
                        }
                    }
                    ChannelList(channels, filter, query, wide = true, Modifier.weight(1f).fillMaxHeight(), onPlay, vm::toggleFavourite, guideTick, vm::nowNext)
                }
            } else {
                CategoryRow(categories, filter) { vm.filter.value = it }
                Spacer(Modifier.height(10.dp))
                ChannelList(channels, filter, query, wide = false, Modifier.fillMaxSize(), onPlay, vm::toggleFavourite, guideTick, vm::nowNext)
            }
        }

        // The number being typed, or a note that it wasn't found.
        val badge = when {
            typed.isNotEmpty() -> "Channel $typed"
            notFound != null -> "No channel $notFound"
            else -> null
        }
        if (badge != null) {
            Text(
                badge,
                color = if (typed.isNotEmpty()) Vanta.Cyan else Vanta.Error,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(24.dp)
                    .background(Vanta.Surface, RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun Header(
    wide: Boolean,
    playlistName: String,
    providers: List<Pair<Long, String>>,
    countryLabel: String?,
    showRefresh: Boolean,
    onPickProvider: (Long) -> Unit,
    onCountries: () -> Unit,
    onRefresh: () -> Unit,
) {
    if (wide) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Live TV", color = Vanta.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            PlaylistChip(playlistName, providers, onPickProvider)
            if (countryLabel != null) CountriesChip(countryLabel, onCountries)
            Spacer(Modifier.weight(1f))
            if (showRefresh) RefreshButton(onRefresh)
        }
    } else {
        // Phones: title and refresh on one line, playlist and countries on the next.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Live TV", color = Vanta.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (showRefresh) RefreshButton(onRefresh)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlaylistChip(playlistName, providers, onPickProvider, Modifier.weight(1f, fill = false))
                if (countryLabel != null) CountriesChip(countryLabel, onCountries, Modifier.weight(1f, fill = false))
            }
        }
    }
}

@Composable
private fun PlaylistChip(
    playlistName: String,
    providers: List<Pair<Long, String>>,
    onPickProvider: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        var open by remember { mutableStateOf(false) }
        FocusCard(onClick = { if (providers.size > 1) open = true }) {
            Text(
                playlistName + if (providers.size > 1) "  ▾" else "",
                color = Vanta.Cyan, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            providers.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onPickProvider(id); open = false })
            }
        }
    }
}

@Composable
private fun CountriesChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusCard(onClick = onClick, modifier = modifier) {
        Text("Countries: $label", color = Vanta.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RefreshButton(onClick: () -> Unit) {
    FocusCard(onClick = onClick, padding = PaddingValues(10.dp), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Refresh, contentDescription = "Refresh playlist", tint = Vanta.Text)
    }
}

@Composable
internal fun CategoryRow(categories: List<CategoryEntity>, filter: Filter, onSelect: (Filter) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip("All", filter == Filter.All) { onSelect(Filter.All) } }
        item { FilterChip("★ Favourites", filter == Filter.Favourites) { onSelect(Filter.Favourites) } }
        items(categories, key = { it.id }) { c ->
            FilterChip(c.name, filter == Filter.Category(c.remoteId)) { onSelect(Filter.Category(c.remoteId)) }
        }
    }
}

@Composable
internal fun FilterChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusCard(onClick = onClick, selected = selected, modifier = modifier) {
        Text(
            label,
            color = if (selected) Vanta.Cyan else Vanta.Text,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChannelList(
    channels: List<ChannelRow>,
    filter: Filter,
    query: String,
    wide: Boolean,
    modifier: Modifier,
    onPlay: (Long) -> Unit,
    onToggleFav: (ChannelRow) -> Unit,
    guideTick: Int,
    nowNext: suspend (ChannelEntity) -> List<ProgrammeEntity>,
) {
    if (channels.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                when {
                    query.isNotBlank() -> "No channels match \"$query\"."
                    filter is Filter.Favourites -> "No favourites yet. Press the star on a channel to add it."
                    else -> "No channels here yet."
                },
                color = Vanta.TextDim,
            )
        }
        return
    }
    val listState = rememberLazyListState()
    // Back to the top when the category or search changes.
    LaunchedEffect(filter, query) { listState.scrollToItem(0) }
    Row(modifier) {
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(channels, key = { it.channel.id }) { row -> ChannelItem(row, wide, onPlay, onToggleFav, guideTick, nowNext) }
        }
        if (channels.size > 60) FastScroller(listState, channels)
    }
}

@Composable
private fun ChannelItem(
    row: ChannelRow,
    wide: Boolean,
    onPlay: (Long) -> Unit,
    onToggleFav: (ChannelRow) -> Unit,
    guideTick: Int,
    nowNext: suspend (ChannelEntity) -> List<ProgrammeEntity>,
) {
    val name = row.channel.name
    if (ChannelNames.isDivider(name)) {
        // The provider's own heading between groups of channels: shown as a heading, not played.
        Text(
            ChannelNames.dividerTitle(name),
            color = Vanta.Cyan,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
        )
        return
    }
    val event = ChannelNames.isEventOnly(name)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // Favourite star on the left, away from the fast-scroll handle on the right.
        FocusCard(
            onClick = { onToggleFav(row) },
            padding = PaddingValues(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Star,
                contentDescription = if (row.isFav) "Remove from favourites" else "Add to favourites",
                tint = if (row.isFav) androidx.compose.ui.graphics.Color(0xFFFFEA00) else Vanta.TextDim.copy(alpha = 0.45f),
            )
        }
        FocusCard(onClick = { onPlay(row.channel.id) }, modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (event) 0.65f else 1f)) {
                Text(
                    row.channel.number.toString(),
                    color = Vanta.TextDim, fontSize = 13.sp, modifier = Modifier.width(44.dp), maxLines = 1,
                )
                Box(
                    Modifier.size(width = 56.dp, height = 36.dp).clip(RoundedCornerShape(6.dp)).background(Vanta.Bg),
                    contentAlignment = Alignment.Center,
                ) {
                    if (row.channel.logo != null) {
                        AsyncImage(
                            model = row.channel.logo,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(2.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        ChannelNames.display(name),
                        color = Vanta.Text, fontSize = 16.sp,
                        maxLines = if (wide) 1 else 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (event) Text("Event only · plays while an event is on", color = Vanta.TextDim, fontSize = 12.sp, maxLines = 1)
                    NowNextLine(row.channel, guideTick, nowNext)
                }
            }
        }
    }
}

/** A handle down the right-hand side: drag (or tap the track) to jump through a long list. */
@Composable
private fun FastScroller(state: LazyListState, channels: List<ChannelRow>) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    val fraction by remember(channels) {
        derivedStateOf { state.firstVisibleItemIndex.toFloat() / (channels.size - 1).coerceAtLeast(1) }
    }
    BoxWithConstraints(Modifier.width(28.dp).fillMaxHeight().padding(start = 4.dp)) {
        val trackPx = with(density) { maxHeight.toPx() }
        val thumbH = 44.dp
        val thumbPx = with(density) { thumbH.toPx() }
        fun jump(y: Float) {
            val f = ((y - thumbPx / 2) / (trackPx - thumbPx)).coerceIn(0f, 1f)
            val target = (f * (channels.size - 1)).toInt()
            scope.launch { state.scrollToItem(target) }
        }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(channels) { detectTapGestures { jump(it.y) } }
                .pointerInput(channels) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true; jump(it.y) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, _ -> change.consume(); jump(change.position.y) },
                    )
                }
        ) {
            val top = with(density) { (fraction * (trackPx - thumbPx)).toDp() }
            Box(
                Modifier.align(Alignment.TopEnd).offset(y = top).width(if (dragging) 10.dp else 6.dp).height(thumbH)
                    .clip(RoundedCornerShape(5.dp)).background(if (dragging) Vanta.Cyan else Vanta.TextDim.copy(alpha = 0.6f))
            )
        }
    }
}

private val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK)

/** "Now: title" with a thin progress bar, then "Next: 20:00 title". Nothing when the channel has no guide. */
@Composable
internal fun NowNextLine(channel: ChannelEntity, tick: Int, nowNext: suspend (ChannelEntity) -> List<ProgrammeEntity>) {
    if (channel.epgChannelId == null) return
    val progs by produceState(initialValue = emptyList<ProgrammeEntity>(), channel.id, tick) {
        value = runCatching { nowNext(channel) }.getOrDefault(emptyList())
    }
    val t = System.currentTimeMillis()
    val now = progs.firstOrNull()?.takeIf { it.start <= t }
    val next = if (now != null) progs.getOrNull(1) else progs.firstOrNull()
    if (now == null && next == null) return
    Column(Modifier.padding(top = 3.dp)) {
        if (now != null) {
            Text("Now: ${now.title}", color = Vanta.Cyan, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val f = ((t - now.start).toFloat() / (now.stop - now.start).coerceAtLeast(1)).coerceIn(0f, 1f)
            Box(Modifier.padding(vertical = 3.dp).width(140.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Vanta.SurfaceHigh)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Vanta.Cyan))
            }
        }
        if (next != null) {
            Text("Next: ${clock.format(java.util.Date(next.start))} ${next.title}", color = Vanta.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
