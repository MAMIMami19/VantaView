// SPDX-License-Identifier: GPL-3.0-or-later
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.vantaview.app.data.aisubs

import android.content.Context
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.sqrt

/** One subtitle line made by AI. [lang] is the spoken language whisper heard. */
data class AiLine(val text: String, val lang: String)

/**
 * AI subtitles for one player: copies the sound as it plays, turns ~5 second pieces into text with
 * whisper.cpp, and shows each line until the next. Nothing leaves the device.
 */
class AiSubtitleEngine(private val ai: AiSubtitles) : TeeAudioProcessor.AudioBufferSink {
    private val _line = MutableStateFlow<AiLine?>(null)
    val line: StateFlow<AiLine?> = _line
    private val _status = MutableStateFlow<String?>(null)
    /** Shown while the engine starts or when it can't keep up. */
    val status: StateFlow<String?> = _status

    private val single = Executors.newSingleThreadExecutor { r -> Thread(r, "ai-subtitles").apply { priority = Thread.MIN_PRIORITY + 1 } }
    private val worker = single.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ctx = 0L
    private var job: Job? = null

    // Incoming audio, already 16 kHz mono.
    private val lock = Any()
    private var buf = FloatArray(16_000 * 30)
    private var len = 0
    private var rate = 44_100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var pos = 0.0 // resampling position

    /** A renderers factory that copies the audio to this engine. */
    fun renderers(context: Context): RenderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(TeeAudioProcessor(this@AiSubtitleEngine)))
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
    }

    fun start() {
        if (job != null) return
        _status.value = "Starting AI subtitles…"
        job = scope.launch {
            val path = ai.file().absolutePath
            ctx = withContext(worker) { WhisperNative.init(path) }
            if (ctx == 0L) { _status.value = "AI subtitles couldn't start on this device."; return@launch }
            _status.value = null
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            var prompt: String? = null
            while (isActive) {
                val chunk = take(seconds = 5.0) ?: run { delay(250); null } ?: continue
                if (rms(chunk) < 0.008f) { _line.value = null; continue } // quiet: nothing to say
                val started = System.currentTimeMillis()
                val out = withContext(worker) {
                    WhisperNative.transcribe(ctx, chunk, ai.mode == AiMode.ENGLISH, threads, prompt)
                }
                val lang = out.substringBefore('\n')
                val text = clean(out.substringAfter('\n', ""))
                _line.value = text?.let { AiLine(it, lang) }
                if (text != null) prompt = text.takeLast(120)
                // Falling behind: drop the oldest audio so the words stay close to the picture.
                val took = System.currentTimeMillis() - started
                _status.value = if (took > 6_000) "This device is too slow for live AI subtitles. Try the Fast model." else null
                synchronized(lock) { if (len > 16_000 * 10) { System.arraycopy(buf, len - 16_000 * 5, buf, 0, 16_000 * 5); len = 16_000 * 5 } }
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        val c = ctx; ctx = 0L
        if (c != 0L) single.execute { WhisperNative.free(c) }
        _line.value = null
    }

    fun release() { stop(); scope.cancel(); single.execute { worker.close() } }


    private fun take(seconds: Double): FloatArray? = synchronized(lock) {
        val n = (16_000 * seconds).toInt()
        if (len < n) return null
        val out = buf.copyOfRange(0, n)
        System.arraycopy(buf, n, buf, 0, len - n)
        len -= n
        out
    }

    private fun rms(a: FloatArray): Float { var s = 0.0; for (x in a) s += x * x; return sqrt(s / a.size).toFloat() }

    // ---- TeeAudioProcessor.AudioBufferSink: called on the playback thread ----

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        synchronized(lock) {
            rate = sampleRateHz; channels = channelCount.coerceAtLeast(1); this.encoding = encoding
            len = 0; pos = 0.0
        }
        _line.value = null
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (job == null) return
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val step = rate / 16_000.0
        synchronized(lock) {
            val frameBytes = channels * when (encoding) { C.ENCODING_PCM_FLOAT -> 4; C.ENCODING_PCM_16BIT -> 2; else -> return }
            val frames = b.remaining() / frameBytes
            if (len + frames > buf.size) { // keep the newest 30 seconds
                val drop = (len + frames - buf.size).coerceAtMost(len)
                System.arraycopy(buf, drop, buf, 0, len - drop); len -= drop
            }
            val start = b.position()
            var f = pos
            while (f < frames) {
                val at = start + f.toInt() * frameBytes
                var sum = 0f
                for (c in 0 until channels) {
                    sum += if (encoding == C.ENCODING_PCM_FLOAT) b.getFloat(at + c * 4) else b.getShort(at + c * 2) / 32768f
                }
                if (len < buf.size) buf[len++] = sum / channels
                f += step
            }
            pos = f - frames
        }
    }

    companion object {
        private val junk = Regex("""(?i)^\s*(\[.*]|\(.*\)|♪+|thank you\.?|thanks for watching!?|subtitles by .*|you)\s*$""")

        /** Drops whisper's guesses on music or silence, and bracketed sound labels. */
        fun clean(raw: String): String? {
            val t = raw.replace(Regex("""\[[^]]*]|\([^)]*\)|♪"""), " ").replace(Regex("""\s+"""), " ").trim()
            if (t.length < 2 || junk.matches(t)) return null
            return t
        }
    }
}
