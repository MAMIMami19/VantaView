// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.vod

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vantaview.app.R
import com.vantaview.app.data.addon.MetaPreview
import com.vantaview.app.data.browse.BrowseGroup
import com.vantaview.app.data.browse.BrowseRepository
import com.vantaview.app.data.browse.BrowseTile
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Bebas Neue (SIL Open Font License), for tile titles. */
val TileFont = FontFamily(Font(R.font.bebas_neue))

/** A tile with the app's own picture and its title in tall capitals. */
@Composable
fun BrowseTileCard(t: BrowseTile, width: Dp, onClick: () -> Unit) {
    FocusCard(onClick = onClick, padding = PaddingValues(0.dp), shape = RoundedCornerShape(14.dp), modifier = Modifier.width(width).aspectRatio(16f / 9f)) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0A0A0E))) {
            Image(painterResource(t.art), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.CenterStart).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(2.dp).height(30.dp).background(Vanta.Cyan))
                Spacer(Modifier.width(6.dp))
                Text(
                    t.title.uppercase(), color = Color.White, maxLines = 1,
                    style = TextStyle(fontFamily = TileFont, fontSize = (width.value / 8.5f).coerceIn(22f, 38f).sp, shadow = Shadow(Color.Black, blurRadius = 8f)),
                )
            }
        }
    }
}

/** The tile groups at the top of Movies and TV Series. */
@Composable
fun BrowseGroups(tiles: List<BrowseTile>, wide: Boolean, onTile: (BrowseTile) -> Unit) {
    val width = if (wide) 250.dp else 200.dp
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        BrowseGroup.entries.forEach { g ->
            val list = tiles.filter { it.group == g }
            if (list.isEmpty()) return@forEach
            Column {
                Text(g.title, color = Vanta.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(list, key = { it.key }) { t -> BrowseTileCard(t, width) { onTile(t) } }
                }
            }
        }
    }
}

data class BrowseUi(
    val title: String = "",
    val items: List<MetaPreview> = emptyList(),
    val loading: Boolean = true,
    val end: Boolean = false,
    val message: String? = null,
)

/** A full page of films or series for one tile, loading more as you scroll. */
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repo: BrowseRepository,
    private val profiles: com.vantaview.app.data.profile.ProfileRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    val type: String = savedState.get<String>("type") ?: "movie"
    private val tile = repo.tile(savedState.get<String>("key").orEmpty())
    private val _ui = MutableStateFlow(BrowseUi(title = tile?.title.orEmpty()))
    val ui: StateFlow<BrowseUi> = _ui
    private var page = 0
    private var busy = false

    init { more() }

    fun more() {
        val t = tile ?: run { _ui.update { it.copy(loading = false, end = true) }; return }
        if (busy || _ui.value.end || page >= 10) return
        busy = true
        _ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            val kids = profiles.observeCurrent().first()?.isKids == true
            val got = runCatching { repo.page(type, t, page + 1, kids) }.getOrDefault(emptyList())
            page++
            _ui.update { u ->
                val all = (u.items + got).distinctBy { it.id }
                u.copy(items = all, loading = false, end = got.isEmpty() || all.size == u.items.size)
            }
            busy = false
        }
    }

    /** Titles from TMDB are opened by their IMDb id, which add-ons use. */
    fun open(m: MetaPreview, go: (MetaPreview) -> Unit) {
        if (!m.id.startsWith("tmdb:")) { go(m); return }
        _ui.update { it.copy(message = "Opening ${m.name}…") }
        viewModelScope.launch {
            val tt = runCatching { repo.imdbId(m.id) }.getOrNull()
            if (tt == null) _ui.update { it.copy(message = "Your add-ons can't look up ${m.name} yet.") }
            else { _ui.update { it.copy(message = null) }; go(m.copy(id = tt)) }
        }
    }
}

@Composable
fun BrowseScreen(onOpen: (MetaPreview) -> Unit, vm: BrowseViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Vanta.Page).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            ui.title.uppercase(), color = Vanta.Text,
            style = TextStyle(fontFamily = TileFont, fontSize = 40.sp),
        )
        ui.message?.let { Text(it, color = Vanta.Cyan, fontSize = 13.sp) }
        Spacer(Modifier.height(8.dp))
        if (!ui.loading && ui.items.isEmpty()) {
            Text("Nothing here right now.", color = Vanta.TextDim)
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(118.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(ui.items, key = { _, m -> m.id }) { i, m ->
                if (i >= ui.items.size - 12) LaunchedEffect(ui.items.size) { vm.more() }
                PosterCard(m, onClick = { vm.open(m, onOpen) }, modifier = Modifier)
            }
        }
    }
}
