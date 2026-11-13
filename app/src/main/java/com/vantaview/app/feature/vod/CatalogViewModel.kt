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

data class CatalogUi(
    val loaded: Boolean = false,
    val addonCount: Int = 0,
    val rows: List<CatalogRef> = emptyList(),
    val rowStates: Map<String, RowState> = emptyMap(),
    val searching: Boolean = false,
    val results: List<MetaPreview>? = null,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val repo: AddonRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    /** "movie" or "series". */
    val type: String = savedState.get<String>("type") ?: "movie"

    private val _ui = MutableStateFlow(CatalogUi())
    val ui: StateFlow<CatalogUi> = _ui
    val query = MutableStateFlow("")
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            repo.observe().collect { addons ->
                val rows = repo.catalogs(type)
                _ui.update { it.copy(loaded = true, addonCount = addons.count { a -> a.entity.enabled }, rows = rows) }
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
            _ui.update { it.copy(searching = false, results = r) }
        }
    }
}
