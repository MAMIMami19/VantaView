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
import okhttp3.OkHttpClient
import javax.inject.Inject

data class VodUi(
    val title: String = "",
    val subtitle: String? = null,
    val streams: List<AddonStream> = emptyList(),
    val index: Int = 0,
    val notice: String? = null,
    val failed: String? = null,
)

/** Plays a film or episode from add-on streams, moving to the next stream if one fails. */
@OptIn(UnstableApi::class)
@HiltViewModel
class VodPlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttp: OkHttpClient,
    session: VodSession,
) : ViewModel() {
    private val _ui = MutableStateFlow(VodUi())
    val ui: StateFlow<VodUi> = _ui

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()

    private var startedOk = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                startedOk = true
                _ui.update { it.copy(failed = null) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val s = _ui.value
            val next = s.index + 1
            // Before a stream has ever started, quietly move on to the next one.
            if (!startedOk && next < s.streams.size) {
                _ui.update { it.copy(notice = "That stream didn't work, trying the next one (${next + 1} of ${s.streams.size})") }
                playIndex(next, 0)
            } else {
                _ui.update { it.copy(failed = "Playback problem (${error.errorCodeName}). Try another stream.") }
            }
        }
    }

    init {
        player.addListener(listener)
        val req = session.current
        if (req == null || req.streams.isEmpty()) {
            _ui.value = VodUi(failed = "Nothing to play")
        } else {
            _ui.value = VodUi(title = req.title, subtitle = req.subtitle, streams = req.streams, index = req.startIndex)
            playIndex(req.startIndex.coerceIn(0, req.streams.lastIndex), 0)
        }
    }

    /** Switches stream, keeping the current position (used by "Streams" in the player). */
    fun switchTo(index: Int) {
        val pos = player.currentPosition.takeIf { it > 0 && player.duration != C.TIME_UNSET } ?: 0
        startedOk = false
        _ui.update { it.copy(notice = null, failed = null) }
        playIndex(index, pos)
    }

    fun clearNotice() = _ui.update { it.copy(notice = null) }

    fun pause() { player.playWhenReady = false }

    private fun playIndex(index: Int, positionMs: Long) {
        val s = _ui.value.streams.getOrNull(index) ?: return
        _ui.update { it.copy(index = index) }
        val ua = s.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: "VantaView/${BuildConfig.VERSION_NAME}"
        val http = OkHttpDataSource.Factory(okHttp)
            .setUserAgent(ua)
            .setDefaultRequestProperties(s.headers.filterKeys { !it.equals("User-Agent", true) })
        val item = MediaItem.Builder().setUri(s.url).apply {
            if (s.url.substringBefore('?').endsWith(".m3u8", true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        player.setMediaSource(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)).createMediaSource(item))
        player.prepare()
        if (positionMs > 0) player.seekTo(positionMs)
        player.playWhenReady = true
    }

    override fun onCleared() {
        player.removeListener(listener)
        player.release()
    }
}
