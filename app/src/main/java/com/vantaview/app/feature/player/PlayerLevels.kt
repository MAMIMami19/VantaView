// SPDX-License-Identifier: GPL-3.0-or-later
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.vantaview.app.feature.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.provider.Settings
import android.view.WindowManager
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.roundToInt

/**
 * Brightness and volume for the player's swipe gestures. Brightness changes only this screen and goes
 * back to normal when the player closes. Volume goes up to 200%: past 100% the sound is boosted.
 */
class PlayerLevels(private val activity: Activity?, private val player: ExoPlayer) {
    private val audio = activity?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val maxVol = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
    private var enhancer: LoudnessEnhancer? = null
    private var enhancerSession = 0

    /** 0 to 1. */
    var brightness: Float = run {
        val w = activity?.window?.attributes?.screenBrightness ?: -1f
        if (w >= 0f) w else runCatching {
            Settings.System.getInt(activity?.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f)
    }
        private set

    /** 0 to 2 (2 = 200%). */
    var volume: Float = (audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: maxVol).toFloat() / maxVol
        private set

    fun changeBrightness(delta: Float) {
        brightness = (brightness + delta).coerceIn(0.01f, 1f)
        activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = brightness } }
    }

    fun changeVolume(delta: Float) {
        // Start from where the device's own volume is now (it may have changed with the volume keys).
        if (volume <= 1f) volume = (audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / maxVol
        volume = (volume + delta).coerceIn(0f, MAX)
        val stream = (volume.coerceAtMost(1f) * maxVol).roundToInt()
        runCatching { audio?.setStreamVolume(AudioManager.STREAM_MUSIC, stream, 0) }
        boost(((volume - 1f).coerceAtLeast(0f) * 1000).roundToInt()) // up to +10 dB at 200%
    }

    private fun boost(gainMb: Int) {
        runCatching {
            val session = player.audioSessionId
            if (gainMb <= 0) { enhancer?.enabled = false; return }
            if (enhancer == null || enhancerSession != session) {
                enhancer?.release()
                enhancer = LoudnessEnhancer(session)
                enhancerSession = session
            }
            enhancer?.setTargetGain(gainMb)
            enhancer?.enabled = true
        }
    }

    /** The player is closing: screen brightness back to the device's setting, boost off. */
    fun release() {
        activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
        runCatching { enhancer?.release() }
        enhancer = null
    }

    companion object { const val MAX = 2f }
}
