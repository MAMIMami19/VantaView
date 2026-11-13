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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.vantaview.app.BuildConfig
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
    savedState: SavedStateHandle,
) : ViewModel() {
    private val channelId: Long = savedState.get<Long>("channelId") ?: 0L

    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui

    // "Medium" buffer preset: 15 s minimum, 30 s maximum, start after 2.5 s.
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 30_000, 2_500, 5_000)
                .build()
        )
        .build()

    private var siblings: List<ChannelEntity> = emptyList()
    private var index = 0

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
            siblings = db.content().siblings(channel.providerId, channel.categoryRemoteId)
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
            start(siblings[index])
        }
    }

    /** Channel up (+1) or down (-1) within the current category. */
    fun step(delta: Int) {
        if (siblings.size < 2) return
        index = ((index + delta) % siblings.size + siblings.size) % siblings.size
        start(siblings[index])
    }

    fun retryNow() {
        if (candidates.isEmpty()) return
        attempts = 0
        working = null
        candidate = 0
        _ui.update { it.copy(status = PlayStatus.LOADING, message = null) }
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
        watchdog?.cancel(); retry?.cancel(); loadJob?.cancel()
        player.stop()
        attempts = 0
        working = null
        candidate = 0
        candidates = emptyList()
        _ui.value = PlayerUi(
            title = channel.name, number = channel.number, status = PlayStatus.LOADING, hasSiblings = siblings.size > 1,
        )
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

    private fun onFailure(reason: String) {
        retry?.cancel()
        if (candidates.isEmpty()) return
        when {
            // Still looking for an address that works: try the next one straight away.
            working == null && candidate < candidates.lastIndex -> {
                candidate++
                prepareCurrent()
            }
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
        watchdog?.cancel(); retry?.cancel(); loadJob?.cancel()
        player.removeListener(listener)
        player.release()
    }
}
