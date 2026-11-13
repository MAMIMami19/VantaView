// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.live

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.vantaview.app.data.db.CategoryEntity
import com.vantaview.app.data.db.ChannelRow
import com.vantaview.app.data.repo.SyncState
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta

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
    val state = active?.let { sync[it.id] }

    BoxWithConstraints(Modifier.fillMaxSize().background(Vanta.Bg)) {
        val wide = maxWidth >= 720.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            // Top bar
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Live TV", color = Vanta.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Box {
                    var open by remember { mutableStateOf(false) }
                    FocusCard(onClick = { if (providers.size > 1) open = true }) {
                        Text(
                            (active?.name ?: "No playlist") + if (providers.size > 1) "  ▾" else "",
                            color = Vanta.Cyan, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        providers.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = { vm.selectProvider(p.id); open = false })
                        }
                    }
                }
                countryLabel?.let { label ->
                    FocusCard(onClick = vm::editCountries) {
                        Text("Countries: $label", color = Vanta.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (providers.isNotEmpty()) FocusCard(onClick = vm::refresh) { Text("Refresh", color = Vanta.Text) }
            }
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
                    }
                }
                return@Column
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                label = { Text("Search channels") },
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
                    ChannelList(channels, filter, query, Modifier.weight(1f).fillMaxHeight(), onPlay, vm::toggleFavourite)
                }
            } else {
                CategoryRow(categories, filter) { vm.filter.value = it }
                Spacer(Modifier.height(10.dp))
                ChannelList(channels, filter, query, Modifier.fillMaxSize(), onPlay, vm::toggleFavourite)
            }
        }
    }
}

@Composable
private fun CategoryRow(categories: List<CategoryEntity>, filter: Filter, onSelect: (Filter) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip("All", filter == Filter.All) { onSelect(Filter.All) } }
        item { FilterChip("★ Favourites", filter == Filter.Favourites) { onSelect(Filter.Favourites) } }
        items(categories, key = { it.id }) { c ->
            FilterChip(c.name, filter == Filter.Category(c.remoteId)) { onSelect(Filter.Category(c.remoteId)) }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
    modifier: Modifier,
    onPlay: (Long) -> Unit,
    onToggleFav: (ChannelRow) -> Unit,
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
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(channels, key = { it.channel.id }) { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FocusCard(onClick = { onPlay(row.channel.id) }, modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                        Text(
                            row.channel.name,
                            color = Vanta.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
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
            }
        }
    }
}
