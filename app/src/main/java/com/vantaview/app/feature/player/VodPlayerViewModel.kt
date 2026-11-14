// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.vantaview.app.BuildConfig
import com.vantaview.app.data.addon.AddonStream
import com.vantaview.app.data.addon.VodSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

data class VodUi(
    val title: String = "",
    val subtitle: String? = null,
    val streams: List<AddonStream> = emptyList(),
    val index: Int = 0,
    val notice: String? = null,
    val failed: String? = null,
    /** "Getting link…" or download progress on a debrid service, while a torrent is being prepared. */
    val preparing: String? = null,
)

/** Plays a film or episode from add-on streams, moving to the next stream if one fails. */
@OptIn(UnstableApi::class)
@HiltViewModel
class VodPlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val settings: com.vantaview.app.data.PlaybackSettings,
    aiSubs: com.vantaview.app.data.aisubs.AiSubtitles,
    private val okHttp: OkHttpClient,
    private val debrid: com.vantaview.app.data.debrid.DebridManager,
    private val history: com.vantaview.app.data.history.WatchHistory,
    session: VodSession,
) : ViewModel() {
    private val _ui = MutableStateFlow(VodUi())
    val ui: StateFlow<VodUi> = _ui

    /** AI subtitles (beta), when turned on in Settings and the speech model is downloaded. */
    val ai: com.vantaview.app.data.aisubs.AiSubtitleEngine? =
        if (aiSubs.active) com.vantaview.app.data.aisubs.AiSubtitleEngine(aiSubs) else null

    val player: ExoPlayer = (ai?.let { ExoPlayer.Builder(context, it.renderers(context)) } ?: ExoPlayer.Builder(context))
        .setLoadControl(settings.loadControl())
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()

    private var startedOk = false
    private var season: Int? = null
    private var episode: Int? = null
    private var resolveJob: kotlinx.coroutines.Job? = null
    private var request: com.vantaview.app.data.addon.VodRequest? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) saveProgress()
            if (state == Player.STATE_READY) {
                startedOk = true
                _ui.update { it.copy(failed = null) }
            }
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            _tracksVersion.value++
            if (!pickedTracks) TrackPrefs.onTracks(player, settings, tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            val s = _ui.value
            val next = s.index + 1
            // Before a stream has ever started, quietly move on to the next one.
            if (!startedOk && next < s.streams.size) {
                _ui.update { it.copy(notice = "That stream didn't work, trying the next one (${next + 1} of ${s.streams.size})") }
                playIndex(next, 0, explicit = false)
            } else {
                _ui.update { it.copy(failed = "Playback problem (${error.errorCodeName}). Try another stream.") }
            }
        }
    }

    // ---- Audio and subtitles (Settings › Subtitles and audio, and the picker in the player) ----
    private var pickedTracks = false
    private val _tracksVersion = MutableStateFlow(0)
    /** Changes whenever the stream's tracks change, so the pickers re-read them. */
    val tracksVersion: StateFlow<Int> = _tracksVersion
    fun trackChoices(type: Int) = TrackPrefs.choices(player, type)
    fun pickTrack(type: Int, c: TrackChoice) { pickedTracks = true; TrackPrefs.pick(player, type, c) }

    init {
        player.addListener(listener)
        TrackPrefs.apply(player, settings)
        ai?.start()
        val req = session.current
        if (req == null || req.streams.isEmpty()) {
            _ui.value = VodUi(failed = "Nothing to play")
        } else {
            _ui.value = VodUi(title = req.title, subtitle = req.subtitle, streams = req.streams, index = req.startIndex)
            season = req.season
            episode = req.episode
            request = req
            playIndex(req.startIndex.coerceIn(0, req.streams.lastIndex), req.startPositionMs, explicit = req.chosen)
            // Save progress every 10 seconds while playing.
            viewModelScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(10_000)
                    if (player.isPlaying) saveProgress()
                }
            }
        }
    }

    /** Switches stream, keeping the current position (used by "Streams" in the player). */
    fun switchTo(index: Int) {
        val pos = player.currentPosition.takeIf { it > 0 && player.duration != C.TIME_UNSET } ?: 0
        startedOk = false
        _ui.update { it.copy(notice = null, failed = null) }
        playIndex(index, pos, explicit = true)
    }

    fun clearNotice() = _ui.update { it.copy(notice = null) }

    fun pause() { player.playWhenReady = false; saveProgress() }

    /** Records where this profile got to, for Continue watching and resume. */
    private fun saveProgress() {
        val r = request ?: return
        if (r.metaId.isBlank()) return
        val dur = player.duration
        val pos = player.currentPosition
        if (dur == C.TIME_UNSET || dur <= 0 || pos <= 0) return
        history.record(
            com.vantaview.app.data.history.HistoryEntry(
                type = r.type, metaId = r.metaId, videoId = r.videoId, title = r.title,
                poster = r.poster, background = r.background, season = r.season, episode = r.episode,
                episodeTitle = r.episodeTitle, positionMs = pos, durationMs = dur,
                updatedAt = System.currentTimeMillis(), knownSeason = r.knownSeason,
            )
        )
    }

    /**
     * Plays stream [index]. A torrent is first turned into a link through the debrid accounts; when it isn't
     * cached, an [explicit] pick is downloaded on the service (with progress shown), otherwise the next
     * stream is tried.
     */
    private fun playIndex(index: Int, positionMs: Long, explicit: Boolean) {
        val s = _ui.value.streams.getOrNull(index) ?: return
        resolveJob?.cancel()
        _ui.update { it.copy(index = index, preparing = null) }
        if (!s.isTorrent) { playUrl(s.url, s.headers, positionMs); return }

        player.stop()
        _ui.update { it.copy(preparing = "Getting the link from your debrid service…") }
        resolveJob = viewModelScope.launch {
            val r = debrid.resolve(s, season, episode, allowDownload = explicit) { svc, pct ->
                _ui.update { it.copy(preparing = "Not cached yet. Downloading on ${svc.label}" + (pct?.let { p -> ": $p%" } ?: "…") + "\nIt will play when ready.") }
            }
            when (r) {
                is com.vantaview.app.data.debrid.Resolve.Ready -> {
                    _ui.update { it.copy(preparing = null) }
                    playUrl(r.url, emptyMap(), positionMs)
                }
                else -> {
                    val reason = when (r) {
                        is com.vantaview.app.data.debrid.Resolve.Failed -> r.message
                        else -> "Not cached on your debrid service"
                    }
                    val next = index + 1
                    if (!explicit && next < _ui.value.streams.size) {
                        _ui.update { it.copy(preparing = null, notice = "$reason. Trying the next stream (${next + 1} of ${_ui.value.streams.size})") }
                        playIndex(next, 0, explicit = false)
                    } else {
                        _ui.update { it.copy(preparing = null, failed = "$reason. Choose another stream, or pick this one to download it first.") }
                    }
                }
            }
        }
    }

    private fun playUrl(url: String, headers: Map<String, String>, positionMs: Long) {
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: "VantaView/${BuildConfig.VERSION_NAME}"
        val http = OkHttpDataSource.Factory(okHttp)
            .setUserAgent(ua)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
        val item = MediaItem.Builder().setUri(url).apply {
            if (url.substringBefore('?').endsWith(".m3u8", true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        player.setMediaSource(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)).createMediaSource(item))
        player.prepare()
        if (positionMs > 0) player.seekTo(positionMs)
        player.playWhenReady = true
    }

    override fun onCleared() {
        saveProgress()
        resolveJob?.cancel()
        player.removeListener(listener)
        player.release()
        ai?.release()
    }
}
