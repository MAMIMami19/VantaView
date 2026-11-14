// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.vantaview.app.data.ChannelNames
import com.vantaview.app.data.db.ChannelEntity
import com.vantaview.app.data.db.VantaDb
import com.vantaview.app.data.repo.PlayTarget
import com.vantaview.app.data.repo.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

enum class PlayStatus { LOADING, PLAYING, RECONNECTING, FAILED }

data class PlayerUi(
    val title: String = "",
    val number: Int = 0,
    val status: PlayStatus = PlayStatus.LOADING,
    val attempt: Int = 0,
    val message: String? = null,
    val hasSiblings: Boolean = false,
    /** A channel that only carries something while an event is on. */
    val eventOnly: Boolean = false,
    /** What's on now and next, from the TV guide (null when the channel has no guide). */
    val now: com.vantaview.app.data.db.ProgrammeEntity? = null,
    val next: com.vantaview.app.data.db.ProgrammeEntity? = null,
)

@OptIn(UnstableApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: VantaDb,
    private val repo: ProviderRepository,
    private val okHttp: OkHttpClient,
    private val profiles: com.vantaview.app.data.profile.ProfileRepository,
    private val session: com.vantaview.app.data.profile.ProfileSession,
    private val guide: com.vantaview.app.data.guide.GuideRepository,
    val settings: com.vantaview.app.data.PlaybackSettings,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val channelId: Long = savedState.get<Long>("channelId") ?: 0L

    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui

    // Buffer size from Settings › Playback (Medium unless changed).
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(settings.loadControl())
        .build()

    /** How the video fills the screen: Fit, Fill or Zoom. */
    private val _size = MutableStateFlow(settings.size)
    val size: StateFlow<com.vantaview.app.data.StreamSize> = _size
    fun cycleSize() { _size.value = _size.value.next(); settings.size = _size.value }

    private var siblings: List<ChannelEntity> = emptyList()
    private var index = 0

    /** The channels you can switch between (the current category), shown under the video in portrait. */
    private val _list = MutableStateFlow<List<ChannelEntity>>(emptyList())
    val list: StateFlow<List<ChannelEntity>> = _list
    private val _index = MutableStateFlow(0)
    val current: StateFlow<Int> = _index
    private var eventTimer: Job? = null
    private var guideJob: Job? = null

    /** Addresses to try for the current channel: (url, treat as HLS). */
    private var candidates: List<Pair<String, Boolean>> = emptyList()
    private var candidate = 0
    private var working: Int? = null
    private var headers: Map<String, String> = emptyMap()
    private var attempts = 0
    private var watchdog: Job? = null
    private var retry: Job? = null
    private var loadJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            watchdog?.cancel()
            when (state) {
                Player.STATE_READY -> {
                    working = candidate
                    attempts = 0
                    _ui.update { it.copy(status = PlayStatus.PLAYING, message = null, attempt = 0) }
                }
                Player.STATE_BUFFERING -> {
                    // A live stream stuck buffering for 12 seconds is treated as dropped.
                    watchdog = viewModelScope.launch {
                        delay(12_000)
                        if (player.playbackState == Player.STATE_BUFFERING) onFailure("The stream stopped responding")
                    }
                }
                Player.STATE_ENDED -> onFailure("The stream ended")
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            watchdog?.cancel()
            onFailure(describe(error))
        }
    }

    init {
        player.addListener(listener)
        viewModelScope.launch {
            val channel = db.content().channel(channelId)
            if (channel == null) {
                _ui.update { it.copy(status = PlayStatus.FAILED, message = "That channel is no longer in the playlist") }
                return@launch
            }
            // Provider divider rows ("◆◆◆ CINEMA ◆◆◆") are headings, not channels to switch to.
            siblings = db.content().siblings(channel.providerId, channel.categoryRemoteId)
                .filter { it.id == channel.id || !ChannelNames.isDivider(it.name) }
            val profile = profiles.current()
            if (profile?.isKids == true) {
                val allow = db.profiles().kidsCategories(profile.id)
                    .filter { it.providerId == channel.providerId }.map { it.categoryRemoteId }.toSet()
                val adultCats = db.content().categories(channel.providerId)
                    .filter { com.vantaview.app.data.profile.KidsFilter.looksAdult(it.name) }.map { it.remoteId }.toSet()
                siblings = siblings.filter { com.vantaview.app.data.profile.KidsFilter.channelAllowed(it, adultCats, allow) }
            }
            index = siblings.indexOfFirst { it.id == channel.id }.coerceAtLeast(0)
            if (siblings.isEmpty()) siblings = listOf(channel)
            _list.value = siblings
            start(siblings[index])
        }
    }

    /** Channel up (+1) or down (-1) within the current category. */
    fun step(delta: Int) {
        if (siblings.size < 2) return
        index = ((index + delta) % siblings.size + siblings.size) % siblings.size
        start(siblings[index])
    }

    /** Plays the channel at [i] in the list shown under the video. */
    fun playAt(i: Int) {
        if (i !in siblings.indices || i == index) return
        index = i
        start(siblings[index])
    }

    fun retryNow() {
        if (candidates.isEmpty()) return
        attempts = 0
        working = null
        candidate = 0
        _ui.update { it.copy(status = PlayStatus.LOADING, message = null) }
        eventTimer?.cancel()
        if (_ui.value.eventOnly) eventTimer = viewModelScope.launch {
            delay(10_000)
            if (_ui.value.status != PlayStatus.PLAYING) giveUpOnEvent()
        }
        prepareCurrent()
    }

    fun pause() { player.playWhenReady = false }

    fun resume() {
        if (candidates.isEmpty()) return
        // Live TV: jump back to the live edge instead of resuming old buffered video.
        prepareCurrent()
    }

    private fun start(channel: ChannelEntity) {
        session.setLastChannel(channel.id)
        _index.value = index
        watchdog?.cancel(); retry?.cancel(); loadJob?.cancel(); eventTimer?.cancel()
        player.stop()
        attempts = 0
        working = null
        candidate = 0
        candidates = emptyList()
        val event = ChannelNames.isEventOnly(channel.name)
        _ui.value = PlayerUi(
            title = ChannelNames.display(channel.name), number = channel.number, status = PlayStatus.LOADING,
            hasSiblings = siblings.size > 1, eventOnly = event,
        )
        // Now and next from the TV guide, refreshed every minute while watching.
        guideJob?.cancel()
        guideJob = viewModelScope.launch {
            val key = channel.epgChannelId ?: return@launch
            while (true) {
                val t = System.currentTimeMillis()
                val list = runCatching { guide.nowNext(channel.providerId, key, t) }.getOrDefault(emptyList())
                val now = list.firstOrNull()?.takeIf { it.start <= t }
                val next = if (now != null) list.getOrNull(1) else list.firstOrNull()
                _ui.update { it.copy(now = now, next = next) }
                delay(60_000)
            }
        }
        // Event channels are often empty: after 10 seconds without a picture, say so instead of spinning.
        if (event) eventTimer = viewModelScope.launch {
            delay(10_000)
            if (_ui.value.status != PlayStatus.PLAYING) giveUpOnEvent()
        }
        loadJob = viewModelScope.launch {
            val target: PlayTarget = try {
                repo.resolveStream(channel)
            } catch (e: Exception) {
                _ui.update { it.copy(status = PlayStatus.FAILED, message = e.message ?: "Could not open the channel") }
                return@launch
            }
            headers = target.headers
            val list = ArrayList<Pair<String, Boolean>>()
            val hls = looksHls(target.url)
            list += target.url to hls
            if (!hls) list += target.url to true
            target.altUrl?.let { list += it to looksHls(it) }
            candidates = list
            prepareCurrent()
        }
    }

    private fun looksHls(url: String): Boolean = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    private fun prepareCurrent() {
        val (url, hls) = candidates.getOrNull(candidate) ?: return
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: "VantaView/${BuildConfig.VERSION_NAME}"
        val http = OkHttpDataSource.Factory(okHttp)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
        val item = MediaItem.Builder().setUri(url).apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        val source = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)).createMediaSource(item)
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    private fun giveUpOnEvent() {
        watchdog?.cancel(); retry?.cancel(); eventTimer?.cancel()
        player.stop()
        _ui.update {
            it.copy(
                status = PlayStatus.FAILED,
                message = "Nothing is on this channel right now. Event channels only show something while an event is live.",
            )
        }
    }

    private fun onFailure(reason: String) {
        retry?.cancel()
        if (candidates.isEmpty()) return
        when {
            // Still looking for an address that works: try the next one straight away.
            working == null && candidate < candidates.lastIndex -> {
                candidate++
                prepareCurrent()
            }
            // An event channel that never started is almost always just empty: don't keep retrying.
            _ui.value.eventOnly && working == null -> giveUpOnEvent()
            attempts < 5 -> {
                attempts++
                _ui.update { it.copy(status = PlayStatus.RECONNECTING, attempt = attempts, message = reason) }
                retry = viewModelScope.launch {
                    delay(1000L shl (attempts - 1))
                    candidate = working ?: 0
                    prepareCurrent()
                }
            }
            else -> _ui.update { it.copy(status = PlayStatus.FAILED, message = reason) }
        }
    }

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "No connection to the stream"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "The provider refused the stream (check your login or connection limit)"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The stream was not found"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "This stream format isn't supported yet"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This device could not decode the stream"
        else -> "Playback problem (${e.errorCodeName})"
    }

    override fun onCleared() {
        watchdog?.cancel(); retry?.cancel(); loadJob?.cancel(); eventTimer?.cancel(); guideJob?.cancel()
        player.removeListener(listener)
        player.release()
    }
}
