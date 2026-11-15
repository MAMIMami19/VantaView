// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vantaview.app.data.addon.AddonRepository
import com.vantaview.app.data.addon.CatalogRef
import com.vantaview.app.data.addon.MetaPreview
import com.vantaview.app.ui.Vanta
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything in one row of Movies or TV Series (an add-on catalogue or a Trakt list), loading more as you scroll. */
@HiltViewModel
class CatalogRowViewModel @Inject constructor(
    private val repo: AddonRepository,
    private val trakt: com.vantaview.app.data.trakt.TraktSync,
    private val profiles: com.vantaview.app.data.profile.ProfileRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    val type: String = savedState.get<String>("type") ?: "movie"
    private val key: String = savedState.get<String>("key").orEmpty()
    private val _ui = MutableStateFlow(BrowseUi(title = savedState.get<String>("title").orEmpty()))
    val ui: StateFlow<BrowseUi> = _ui
    private var ref: CatalogRef? = null
    private var busy = false

    init {
        viewModelScope.launch {
            if (key.startsWith("trakt-")) {
                val row = runCatching { trakt.rows(type) }.getOrDefault(emptyList()).firstOrNull { it.key == key }
                _ui.update { it.copy(title = row?.title ?: it.title, items = row?.items.orEmpty(), loading = false, end = true) }
                return@launch
            }
            val kids = profiles.observeCurrent().first()?.isKids == true
            ref = (if (kids) repo.kidsCatalogs(type) else repo.catalogs(type)).firstOrNull { it.key == key }
            if (ref == null) { _ui.update { it.copy(loading = false, end = true, message = "This list isn't available any more.") }; return@launch }
            _ui.update { it.copy(title = ref!!.title) }
            more()
        }
    }

    fun more() {
        val r = ref ?: return
        if (busy || _ui.value.end) return
        busy = true
        _ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            val got = runCatching { repo.loadCatalog(r, skip = _ui.value.items.size) }.getOrDefault(emptyList())
            _ui.update { u ->
                val all = (u.items + got).distinctBy { it.id }
                u.copy(items = all, loading = false, end = got.isEmpty() || all.size == u.items.size || _ui.value.items.size > 2000)
            }
            busy = false
        }
    }
}

@Composable
fun CatalogRowScreen(onOpen: (MetaPreview) -> Unit, vm: CatalogRowViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Vanta.Page).padding(horizontal = 16.dp, vertical = 12.dp)) {
        com.vantaview.app.ui.PageTitle(ui.title)
        ui.message?.let { Text(it, color = Vanta.TextDim) }
        Spacer(Modifier.height(10.dp))
        if (!ui.loading && ui.items.isEmpty() && ui.message == null) Text("Nothing here right now.", color = Vanta.TextDim)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(118.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(ui.items, key = { _, m -> m.id }) { i, m ->
                if (i >= ui.items.size - 12) LaunchedEffect(ui.items.size) { vm.more() }
                PosterCard(m, onClick = { onOpen(m) }, modifier = Modifier)
            }
        }
    }
}
