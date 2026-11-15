// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.vod

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vantaview.app.data.addon.AddonRepository
import com.vantaview.app.data.addon.CatalogRef
import com.vantaview.app.data.addon.MetaPreview
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface RowState {
    data object Loading : RowState
    data class Ready(val items: List<MetaPreview>) : RowState
    data object Failed : RowState
}

/** Orders for Movies and TV Series. Recently added keeps the catalogue's own order. */
enum class CatalogSort(val label: String) {
    RECENT("Recently added"), AZ("A to Z"), ZA("Z to A"), NEWEST("Newest"), OLDEST("Oldest"), RATED("Top rated");

    fun apply(list: List<MetaPreview>): List<MetaPreview> {
        fun year(m: MetaPreview) = m.year?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
        fun rating(m: MetaPreview) = m.rating?.replace(',', '.')?.toDoubleOrNull()
        return when (this) {
            RECENT -> list
            AZ -> list.sortedBy { it.name.lowercase().removePrefix("the ") }
            ZA -> list.sortedByDescending { it.name.lowercase().removePrefix("the ") }
            // Titles with no year or rating go last.
            NEWEST -> list.sortedWith(compareBy<MetaPreview> { year(it) == null }.thenByDescending { year(it) ?: 0 })
            OLDEST -> list.sortedWith(compareBy<MetaPreview> { year(it) == null }.thenBy { year(it) ?: 0 })
            RATED -> list.sortedWith(compareBy<MetaPreview> { rating(it) == null }.thenByDescending { rating(it) ?: 0.0 })
        }
    }
}

data class CatalogUi(
    val loaded: Boolean = false,
    val kids: Boolean = false,
    val addonCount: Int = 0,
    val rows: List<CatalogRef> = emptyList(),
    val rowStates: Map<String, RowState> = emptyMap(),
    val searching: Boolean = false,
    val results: List<MetaPreview>? = null,
    /** Watch now, genres, decades, themes and countries at the top of the page. */
    val tiles: List<com.vantaview.app.data.browse.BrowseTile> = emptyList(),
)

@OptIn(FlowPreview::class)
@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val repo: AddonRepository,
    private val profiles: com.vantaview.app.data.profile.ProfileRepository,
    private val browse: com.vantaview.app.data.browse.BrowseRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    savedState: SavedStateHandle,
) : ViewModel() {
    /** "movie" or "series". */
    val type: String = savedState.get<String>("type") ?: "movie"

    // The chosen order, remembered separately for Movies and TV Series.
    private val sp = context.getSharedPreferences("catalog", android.content.Context.MODE_PRIVATE)
    private val _sort = MutableStateFlow(runCatching { CatalogSort.valueOf(sp.getString("sort_$type", null)!!) }.getOrDefault(CatalogSort.RECENT))
    val sort: StateFlow<CatalogSort> = _sort
    fun setSort(s: CatalogSort) { _sort.value = s; sp.edit().putString("sort_$type", s.name).apply() }

    private val _ui = MutableStateFlow(CatalogUi())
    val ui: StateFlow<CatalogUi> = _ui
    val query = MutableStateFlow("")
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(repo.observe(), repo.observePortals(), profiles.observeCurrent()) { a, portals, p ->
                Triple(a, portals.size, p)
            }.collect { (addons, portals, p) ->
                val kids = p?.isKids == true
                val rows = if (kids) repo.kidsCatalogs(type) else repo.catalogs(type)
                _ui.update {
                    // Stalker portals count as sources here: their films and series are rows too (not on Kids profiles).
                    val sources = addons.count { a -> a.entity.enabled } + if (kids) 0 else portals
                    it.copy(loaded = true, kids = kids, addonCount = sources, rows = rows, tiles = browse.tiles(type, kids),
                        rowStates = it.rowStates.filterKeys { k -> rows.any { r -> r.key == k } })
                }
                rows.forEach { load(it) }
            }
        }
        viewModelScope.launch {
            query.drop(1).debounce(400).distinctUntilChanged().collect { search(it) }
        }
    }

    private fun load(ref: CatalogRef) {
        if (_ui.value.rowStates[ref.key] is RowState.Ready) return
        _ui.update { it.copy(rowStates = it.rowStates + (ref.key to RowState.Loading)) }
        viewModelScope.launch {
            val items = runCatching { repo.loadCatalog(ref) }.getOrNull()
            _ui.update {
                it.copy(rowStates = it.rowStates + (ref.key to if (items == null) RowState.Failed else RowState.Ready(items)))
            }
        }
    }

    private fun search(q: String) {
        searchJob?.cancel()
        if (q.isBlank()) { _ui.update { it.copy(searching = false, results = null) }; return }
        _ui.update { it.copy(searching = true) }
        searchJob = viewModelScope.launch {
            val r = runCatching { repo.search(type, q) }.getOrDefault(emptyList())
                .let { list -> if (_ui.value.kids) list.filter { com.vantaview.app.data.profile.KidsFilter.hasFamilyGenre(it.genres) } else list }
            _ui.update { it.copy(searching = false, results = r) }
        }
    }
}
