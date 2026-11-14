// SPDX-License-Identifier: GPL-3.0-or-later
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.vantaview.app.data

import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.ui.AspectRatioFrameLayout
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** How much video is loaded ahead. Bigger copes better with poor connections but takes longer to start. */
enum class BufferSize(val label: String, val detail: String, val minMs: Int, val maxMs: Int, val startMs: Int, val rebufferMs: Int) {
    SMALL("Small", "Starts fastest. Best on a fast, steady connection.", 5_000, 15_000, 1_500, 3_000),
    MEDIUM("Medium", "A balance of quick starts and smooth playing.", 15_000, 30_000, 2_500, 5_000),
    LARGE("Large", "Slowest to start, but rides out a poor connection.", 30_000, 60_000, 2_500, 5_000),
}

/** How video fills the screen. */
enum class StreamSize(val label: String, val mode: Int) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("Fill", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    ZOOM("Zoom", AspectRatioFrameLayout.RESIZE_MODE_ZOOM);

    fun next(): StreamSize = entries[(ordinal + 1) % entries.size]
}

enum class SubtitleMode(val label: String) { OFF("Off"), FOREIGN("When the audio isn't my language"), ALWAYS("Always") }
enum class SubtitleSize(val label: String, val scale: Float) { SMALL("Small", 0.8f), MEDIUM("Medium", 1f), LARGE("Large", 1.3f) }

/** Languages offered in Settings › Subtitles and audio (ISO 639-1 codes). Empty code = the stream's own choice. */
val TRACK_LANGUAGES = listOf(
    "" to "Stream default", "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian",
    "pt" to "Portuguese", "nl" to "Dutch", "pl" to "Polish", "ro" to "Romanian", "el" to "Greek", "tr" to "Turkish",
    "ar" to "Arabic", "hi" to "Hindi", "ur" to "Urdu", "pa" to "Punjabi", "ru" to "Russian", "uk" to "Ukrainian",
    "sv" to "Swedish", "da" to "Danish", "no" to "Norwegian", "fi" to "Finnish", "hu" to "Hungarian", "cs" to "Czech",
    "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese",
)

/** Settings › Playback. Shared by every profile on this device. */
@Singleton
class PlaybackSettings @Inject constructor(@ApplicationContext context: Context) {
    private val sp = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    var buffer: BufferSize
        get() = runCatching { BufferSize.valueOf(sp.getString("buffer", null)!!) }.getOrDefault(BufferSize.MEDIUM)
        set(v) { sp.edit().putString("buffer", v.name).apply() }

    /** Seconds the player controls stay on screen; 0 means they stay until hidden. */
    var controlsSeconds: Int
        get() = sp.getInt("controls_seconds", 5)
        set(v) { sp.edit().putInt("controls_seconds", v).apply() }

    var miniPlayer: Boolean
        get() = sp.getBoolean("mini_player", true)
        set(v) { sp.edit().putBoolean("mini_player", v).apply() }

    /** Swipe up and down to change channel, double tap for full screen. */
    var gestures: Boolean
        get() = sp.getBoolean("gestures", true)
        set(v) { sp.edit().putBoolean("gestures", v).apply() }

    var startOnLastChannel: Boolean
        get() = sp.getBoolean("start_last", false)
        set(v) { sp.edit().putBoolean("start_last", v).apply() }

    var rememberSize: Boolean
        get() = sp.getBoolean("remember_size", true)
        set(v) { sp.edit().putBoolean("remember_size", v).apply() }

    /** The stream size last picked in the player; Fit when not remembered. */
    var size: StreamSize
        get() = if (!rememberSize) StreamSize.FIT
            else runCatching { StreamSize.valueOf(sp.getString("size", null)!!) }.getOrDefault(StreamSize.FIT)
        set(v) { sp.edit().putString("size", v.name).apply() }

    // ---- Settings › Subtitles and audio ----

    /** Preferred audio language code; empty = whatever the stream picks. */
    var audioLanguage: String
        get() = sp.getString("audio_lang", "en")!!
        set(v) { sp.edit().putString("audio_lang", v).apply() }

    var subtitleLanguage: String
        get() = sp.getString("sub_lang", "en")!!
        set(v) { sp.edit().putString("sub_lang", v).apply() }

    var subtitleMode: SubtitleMode
        get() = runCatching { SubtitleMode.valueOf(sp.getString("sub_mode", null)!!) }.getOrDefault(SubtitleMode.FOREIGN)
        set(v) { sp.edit().putString("sub_mode", v.name).apply() }

    var subtitleSize: SubtitleSize
        get() = runCatching { SubtitleSize.valueOf(sp.getString("sub_size", null)!!) }.getOrDefault(SubtitleSize.MEDIUM)
        set(v) { sp.edit().putString("sub_size", v.name).apply() }

    var subtitleYellow: Boolean
        get() = sp.getBoolean("sub_yellow", false)
        set(v) { sp.edit().putBoolean("sub_yellow", v).apply() }

    var subtitleBox: Boolean
        get() = sp.getBoolean("sub_box", true)
        set(v) { sp.edit().putBoolean("sub_box", v).apply() }

    fun loadControl(): LoadControl = buffer.let {
        DefaultLoadControl.Builder().setBufferDurationsMs(it.minMs, it.maxMs, it.startMs, it.rebufferMs).build()
    }

    /** True only the first time it's asked after the app starts (for "Start on last channel"). */
    private var startHandled = false
    fun firstStart(): Boolean = if (startHandled) false else { startHandled = true; true }
}
