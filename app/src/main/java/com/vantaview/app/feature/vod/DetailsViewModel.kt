// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.vod

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vantaview.app.data.addon.AddonRepository
import com.vantaview.app.data.addon.AddonStream
import com.vantaview.app.data.addon.MetaDetail
import com.vantaview.app.data.addon.Video
import com.vantaview.app.data.addon.VodRequest
import com.vantaview.app.data.addon.VodSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A stream list waiting for the user to pick one ("Choose stream"). */
data class StreamChoice(
    val title: String,
    val subtitle: String?,
    val streams: List<AddonStream>,
    val season: Int? = null,
    val episode: Int? = null,
)

data class DetailsUi(
    val loading: Boolean = true,
    val meta: MetaDetail? = null,
    val season: Int? = null,
    val finding: String? = null,
    val message: String? = null,
    val choice: StreamChoice? = null,
    /** Not suitable for the Kids profile in use. */
    val blocked: Boolean = false,
)

@HiltViewModel
class DetailsViewModel @Inject constructor(
    private val repo: AddonRepository,
    private val session: VodSession,
    private val profiles: com.vantaview.app.data.profile.ProfileRepository,
    private val debrid: com.vantaview.app.data.debrid.DebridManager,
    savedState: SavedStateHandle,
) : ViewModel() {
    val type: String = savedState.get<String>("type") ?: "movie"
    val id: String = savedState.get<String>("id").orEmpty()
    val fallbackName: String = savedState.get<String>("name").orEmpty()
    val fallbackPoster: String? = savedState.get<String>("poster")?.ifBlank { null }

    private val _ui = MutableStateFlow(DetailsUi())
    val ui: StateFlow<DetailsUi> = _ui

    private val _play = Channel<Unit>(Channel.BUFFERED)
    /** Fires when a film or episode is ready in the player session. */
    val play = _play.receiveAsFlow()

    init {
        viewModelScope.launch {
            val meta = runCatching { repo.meta(type, id) }.getOrNull()
            if (profiles.isKids() && meta != null && meta.genres.isNotEmpty() &&
                !com.vantaview.app.data.profile.KidsFilter.hasFamilyGenre(meta.genres)
            ) {
                _ui.update { it.copy(loading = false, blocked = true) }
                return@launch
            }
            val firstSeason = meta?.videos?.map { it.season }?.distinct()?.sorted()?.let { s -> s.firstOrNull { it > 0 } ?: s.firstOrNull() }
            _ui.update { it.copy(loading = false, meta = meta, season = firstSeason) }
        }
    }

    val title: String get() = _ui.value.meta?.name?.ifBlank { null } ?: fallbackName

    fun selectSeason(s: Int) = _ui.update { it.copy(season = s) }

    fun dismissChoice() = _ui.update { it.copy(choice = null) }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    /** Plays the film ([video] null) or an episode. With [choose], shows the stream list instead of auto-playing. */
    fun play(video: Video?, choose: Boolean) {
        if (_ui.value.finding != null || _ui.value.blocked) return
        val streamId = video?.id ?: id
        val subtitle = video?.let { v ->
            val code = if (v.season > 0) "S${v.season} E${v.episode}" else "Episode ${v.episode}"
            listOf(code, v.title).filter { it.isNotBlank() }.joinToString(" · ")
        }
        _ui.update { it.copy(finding = streamId, message = null) }
        viewModelScope.launch {
            val hasSource = repo.hasStreamSource(type, streamId)
            val raw = if (hasSource) runCatching { repo.streams(type, streamId) }.getOrDefault(emptyList()) else emptyList()
            // Torrent results only stay when a debrid account is connected; cached ones are moved up.
            val streams = runCatching { debrid.annotate(raw) }.getOrDefault(raw.filter { !it.isTorrent })
            val season = video?.season?.takeIf { it > 0 }
            val episode = video?.episode?.takeIf { video.season > 0 }
            when {
                !hasSource -> _ui.update {
                    it.copy(finding = null, message = "None of your add-ons supply streams for this. Add a stream add-on on the Sources page.")
                }
                streams.isEmpty() && raw.any { it.isTorrent } -> _ui.update {
                    it.copy(finding = null, message = "Your add-ons only found torrent results. Connect a debrid account on Sources → Debrid to play them.")
                }
                streams.isEmpty() -> _ui.update {
                    it.copy(finding = null, message = "Your add-ons found no playable streams. External links can't be played in VantaView.")
                }
                choose -> _ui.update { it.copy(finding = null, choice = StreamChoice(title, subtitle, streams, season, episode)) }
                else -> {
                    session.current = VodRequest(title, subtitle, streams, 0, season, episode)
                    _ui.update { it.copy(finding = null) }
                    _play.send(Unit)
                }
            }
        }
    }

    fun pick(index: Int) {
        val c = _ui.value.choice ?: return
        session.current = VodRequest(c.title, c.subtitle, c.streams, index, c.season, c.episode, chosen = true)
        _ui.update { it.copy(choice = null) }
        viewModelScope.launch { _play.send(Unit) }
    }
}
