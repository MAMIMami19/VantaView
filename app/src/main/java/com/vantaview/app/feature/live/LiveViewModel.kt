// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vantaview.app.data.Prefs
import com.vantaview.app.data.db.CategoryEntity
import com.vantaview.app.data.db.ChannelRow
import com.vantaview.app.data.db.ProviderEntity
import com.vantaview.app.data.db.VantaDb
import com.vantaview.app.data.repo.ProviderRepository
import com.vantaview.app.data.repo.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which group of channels is showing. */
sealed interface Filter {
    data object All : Filter
    data object Favourites : Filter
    data class Category(val remoteId: String) : Filter
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LiveViewModel @Inject constructor(
    private val db: VantaDb,
    private val repo: ProviderRepository,
    private val prefs: Prefs,
) : ViewModel() {
    val providers: StateFlow<List<ProviderEntity>> =
        repo.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val providerId = MutableStateFlow(prefs.lastProviderId)
    val filter = MutableStateFlow<Filter>(Filter.All)
    val query = MutableStateFlow("")

    val active: StateFlow<ProviderEntity?> =
        combine(providers, providerId) { list, id -> list.firstOrNull { it.id == id } ?: list.firstOrNull() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val activeId = combine(providers, providerId) { list, id ->
        (list.firstOrNull { it.id == id } ?: list.firstOrNull())?.id
    }.distinctUntilChanged()

    val categories: StateFlow<List<CategoryEntity>> = activeId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList<CategoryEntity>()) else db.content().observeCategories(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val channels: StateFlow<List<ChannelRow>> =
        combine(activeId, filter, query.debounce(250)) { id, f, q -> Triple(id, f, q.trim()) }
            .flatMapLatest { (id, f, q) ->
                if (id == null) flowOf(emptyList<ChannelRow>())
                else db.content().observeChannels(
                    providerId = id,
                    category = (f as? Filter.Category)?.remoteId,
                    query = q,
                    favOnly = if (f is Filter.Favourites) 1 else 0,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sync: StateFlow<Map<Long, SyncState>> = repo.syncStates

    fun selectProvider(id: Long) {
        prefs.lastProviderId = id
        providerId.value = id
        filter.value = Filter.All
    }

    fun toggleFavourite(row: ChannelRow) {
        viewModelScope.launch { repo.setFavourite(row.channel, !row.isFav) }
    }

    fun refresh() {
        active.value?.let { repo.sync(it.id) }
    }
}
