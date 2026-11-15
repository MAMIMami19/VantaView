// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vantaview.app.data.profile.ProfileSession
import com.vantaview.app.data.db.ProviderEntity
import com.vantaview.app.data.remote.SmartAdd
import com.vantaview.app.data.remote.SmartAddResult
import com.vantaview.app.data.repo.ProviderRepository
import com.vantaview.app.data.repo.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SourceTab(val label: String) { SMART("Smart Add"), M3U("M3U link"), XTREAM("Xtream Codes"), STALKER("Stalker portal") }

data class PlaylistForm(
    val tab: SourceTab = SourceTab.SMART,
    val name: String = "",
    val smartText: String = "",
    val m3uUrl: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val portal: String = "",
    val mac: String = "",
    /** Stalker › Advanced: details of the user's own MAG box, for providers that lock to one box. */
    val boxOpen: Boolean = false,
    val model: String = "MAG250",
    val serial: String = "",
    val deviceId: String = "",
    val deviceId2: String = "",
    val signature: String = "",
    val message: String? = null,
    val isError: Boolean = false,
)

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repo: ProviderRepository,
    private val session: ProfileSession,
    private val countries: com.vantaview.app.data.country.CountryStore,
) : ViewModel() {
    val providers: StateFlow<List<ProviderEntity>> =
        repo.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sync: StateFlow<Map<Long, SyncState>> = repo.syncStates

    private val _form = MutableStateFlow(PlaylistForm())
    val form: StateFlow<PlaylistForm> = _form

    fun edit(change: (PlaylistForm) -> PlaylistForm) = _form.update { change(it).copy(message = null, isError = false) }

    fun detect() {
        when (val r = SmartAdd.parse(_form.value.smartText)) {
            is SmartAddResult.Xtream -> _form.update {
                it.copy(
                    tab = SourceTab.XTREAM, server = r.server, username = r.username, password = r.password,
                    message = "Found an Xtream Codes login. Check it and press Add playlist.", isError = false,
                )
            }
            is SmartAddResult.M3u -> _form.update {
                it.copy(
                    tab = SourceTab.M3U, m3uUrl = r.url,
                    message = "Found an M3U link. Check it and press Add playlist.", isError = false,
                )
            }
            is SmartAddResult.Stalker -> _form.update {
                it.copy(
                    tab = SourceTab.STALKER, portal = r.portal, mac = r.mac,
                    message = "Found a Stalker portal and MAC address. Check them and press Add portal.", isError = false,
                )
            }
            SmartAddResult.None -> _form.update {
                it.copy(message = "Couldn't find a playlist link or login in that text.", isError = true)
            }
        }
    }

    fun add() {
        val f = _form.value
        viewModelScope.launch {
            try {
                val id = when (f.tab) {
                    SourceTab.M3U -> repo.addM3u(f.name, f.m3uUrl)
                    SourceTab.XTREAM -> repo.addXtream(f.name, f.server, f.username, f.password)
                    SourceTab.STALKER -> repo.addStalker(f.name, f.portal, f.mac, f.model, f.serial, f.deviceId, f.deviceId2, f.signature)
                    SourceTab.SMART -> { detect(); return@launch }
                }
                session.setLastProvider(id)
                _form.value = PlaylistForm(tab = f.tab, message = "Playlist added. Loading channels…")
            } catch (e: Exception) {
                _form.update { it.copy(message = e.message ?: "Could not add the playlist", isError = true) }
            }
        }
    }

    fun refresh(id: Long) = repo.sync(id)

    fun hasFreeTv(list: List<ProviderEntity>): Boolean = list.any { repo.isFreeTv(it) }

    fun addFreeTv() { viewModelScope.launch { repo.addFreeTv() } }

    fun editCountries(id: Long) { countries.editRequest.value = id }

    fun delete(id: Long) {
        countries.forget(id)
        viewModelScope.launch { repo.delete(id) }
    }
}
