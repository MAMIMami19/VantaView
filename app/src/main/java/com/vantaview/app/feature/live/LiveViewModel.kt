// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vantaview.app.data.country.CountryDetect
import com.vantaview.app.data.country.CountryScan
import com.vantaview.app.data.country.CountryStore
import com.vantaview.app.data.db.CategoryEntity
import com.vantaview.app.data.db.ChannelRow
import com.vantaview.app.data.db.ProfileEntity
import com.vantaview.app.data.db.ProviderEntity
import com.vantaview.app.data.db.VantaDb
import com.vantaview.app.data.profile.KidsFilter
import com.vantaview.app.data.profile.ProfileRepository
import com.vantaview.app.data.profile.ProfileSession
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    private val session: ProfileSession,
    private val countries: CountryStore,
    profiles: ProfileRepository,
) : ViewModel() {
    val providers: StateFlow<List<ProviderEntity>> =
        repo.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val profile: StateFlow<ProfileEntity?> =
        profiles.observeCurrent().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val providerId = MutableStateFlow(session.lastProvider())
    val filter = MutableStateFlow<Filter>(Filter.All)
    val query = MutableStateFlow("")

    val active: StateFlow<ProviderEntity?> =
        combine(providers, providerId) { list, id -> list.firstOrNull { it.id == id } ?: list.firstOrNull() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val activeId = active.map { it?.id }.distinctUntilChanged()

    /** For a Kids profile: the categories a parent allowed on the active playlist (empty = not picked yet). */
    private val allowed = combine(profile, activeId) { p, id -> p to id }
        .flatMapLatest { (p, id) ->
            if (p?.isKids != true || id == null) flowOf(null)
            else db.profiles().observeKidsCategories(p.id).map { rows ->
                rows.filter { it.providerId == id }.map { it.categoryRemoteId }.toSet()
            }
        }

    private val allCategories = activeId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList<CategoryEntity>()) else db.content().observeCategories(id) }

    /** Countries in the active playlist (re-read after each sync). */
    private val scan: StateFlow<CountryScan?> = active
        .distinctUntilChanged { a, b -> a?.id == b?.id && a?.lastSyncAt == b?.lastSyncAt }
        .flatMapLatest { p -> if (p == null) flowOf(null) else flow<CountryScan?> { emit(null); emit(countries.scan(p)) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Countries this profile shows for the active playlist; null = all. */
    private val chosen = combine(session.current, activeId, countries.changes) { prof, id, _ ->
        if (id == null) null else countries.chosen(prof, id)
    }.distinctUntilChanged()

    private val device = CountryDetect.deviceCountry()

    val categories: StateFlow<List<CategoryEntity>> =
        combine(allCategories, allowed, scan, chosen) { cats, allow, sc, pick ->
            val kidsOk = if (allow == null) cats else KidsFilter.categories(cats, allow)
            if (sc == null) kidsOk
            else kidsOk
                .filter { pick == null || sc.countryOf(it.remoteId) in pick }
                // This device's country first; otherwise keep the provider's order.
                .sortedBy { if (device != null && sc.countryOf(it.remoteId) == device) 0 else 1 }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Short label for the Countries button, or null when the playlist has fewer than two countries. */
    val countryLabel: StateFlow<String?> = combine(scan, chosen, profile) { sc, pick, p ->
        when {
            sc == null || sc.countries.size < 2 || p?.isKids == true -> null
            pick == null -> "All countries"
            else -> pick.filter { it != CountryDetect.OTHER }.let { l ->
                if (l.size <= 3) l.joinToString(", ").ifEmpty { "Other" } else "${l.size} countries"
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun editCountries() { active.value?.let { countries.editRequest.value = it.id } }

    val channels: StateFlow<List<ChannelRow>> =
        combine(combine(activeId, filter, query.debounce(250)) { id, f, q -> Triple(id, f, q.trim()) }, scan, chosen) { t, sc, pick ->
            Triple(t, sc, pick)
        }
            .flatMapLatest { (t, sc, pick) ->
                val (id, f, q) = t
                val fav = if (f is Filter.Favourites) 1 else 0
                when {
                    id == null -> flowOf(emptyList<ChannelRow>())
                    f is Filter.Category || pick == null || sc == null ->
                        db.content().observeChannels(id, (f as? Filter.Category)?.remoteId, q, fav)
                    else -> {
                        // Only the categories of the chosen countries.
                        val cats = sc.categoryCountry.filterValues { it in pick }.keys.toList()
                        if (cats.size > 900) db.content().observeChannels(id, null, q, fav)
                        else db.content().observeChannelsIn(id, cats, if (CountryDetect.OTHER in pick) 1 else 0, q, fav)
                    }
                }
            }
            .let { rows ->
                combine(rows, allCategories, allowed) { list, cats, allow ->
                    if (allow == null) list
                    else {
                        val adultCats = cats.filter { KidsFilter.looksAdult(it.name) }.map { it.remoteId }.toSet()
                        list.filter { KidsFilter.channelAllowed(it.channel, adultCats, allow) }
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isKids: StateFlow<Boolean> = profile.map { it?.isKids == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val sync: StateFlow<Map<Long, SyncState>> = repo.syncStates

    init {
        // A different profile has its own last-used playlist.
        viewModelScope.launch {
            session.current.collect {
                providerId.value = session.lastProvider()
                filter.value = Filter.All
            }
        }
    }

    fun selectProvider(id: Long) {
        session.setLastProvider(id)
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
