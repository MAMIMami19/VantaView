// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.ArrayDeque
import kotlin.math.ceil

/**
 * Records a live MPEG-TS channel to the device so it can be paused and rewound. The stream is saved in
 * pieces of about four seconds, listed in a live HLS playlist the player reads from the device, with the
 * last [windowSec] seconds kept. Uses the same single connection the player would (most providers allow one).
 */
class LiveRecorder(
    private val http: OkHttpClient,
    private val url: String,
    private val headers: Map<String, String>,
    private val dir: File,
    private val windowSec: Int,
) {
    val playlist = File(dir, "live.m3u8")
    /** When recording started (wall clock): the earliest point you can rewind to, until the window fills. */
    val startedAt = System.currentTimeMillis()

    @Volatile var pieces = 0; private set
    @Volatile var failed: String? = null; private set
    @Volatile private var call: Call? = null
    private var job: Job? = null

    private data class Piece(val seq: Long, val file: File, val seconds: Double)
    private val list = ArrayDeque<Piece>()
    private var seq = 0L

    fun start(scope: CoroutineScope) {
        dir.deleteRecursively(); dir.mkdirs()
        job = scope.launch(Dispatchers.IO) {
            val b = Request.Builder().url(url)
            headers.forEach { (k, v) -> b.header(k, v) }
            try {
                val c = http.newCall(b.build()).also { call = it }
                c.execute().use { r ->
                    if (!r.isSuccessful) { failed = "The provider refused the stream (error ${r.code})"; return@launch }
                    val input = r.body?.byteStream() ?: run { failed = "The stream was empty"; return@launch }
                    val buf = ByteArray(64 * 1024)
                    val piece = java.io.ByteArrayOutputStream(2 * 1024 * 1024)
                    var pieceStart = System.nanoTime()
                    while (isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        piece.write(buf, 0, n)
                        val secs = (System.nanoTime() - pieceStart) / 1e9
                        if (secs >= 4.0 && piece.size() >= 188) {
                            // Cut on a whole 188-byte TS packet; keep the remainder for the next piece.
                            val bytes = piece.toByteArray()
                            val cut = bytes.size - bytes.size % 188
                            save(bytes.copyOf(cut), secs)
                            piece.reset()
                            if (cut < bytes.size) piece.write(bytes, cut, bytes.size - cut)
                            pieceStart = System.nanoTime()
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) failed = "The recording stopped (${e.message ?: "network error"})"
            }
        }
    }

    @Synchronized
    private fun save(bytes: ByteArray, seconds: Double) {
        val f = File(dir, "p$seq.ts")
        f.writeBytes(bytes)
        list.addLast(Piece(seq, f, seconds))
        seq++
        // Keep the window: drop the oldest pieces.
        var total = list.sumOf { it.seconds }
        while (total > windowSec && list.size > 3) {
            val old = list.removeFirst()
            old.file.delete()
            total -= old.seconds
        }
        writePlaylist()
        pieces++
    }

    private fun writePlaylist() {
        val target = ceil(list.maxOf { it.seconds }).toInt().coerceAtLeast(5)
        val text = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:$target\n#EXT-X-MEDIA-SEQUENCE:${list.first().seq}\n")
            list.forEach { append("#EXTINF:%.3f,\n%s\n".format(java.util.Locale.US, it.seconds, it.file.name)) }
        }
        val tmp = File(dir, "live.m3u8.tmp")
        tmp.writeText(text)
        tmp.renameTo(playlist)
    }

    /** Seconds of the channel kept on the device right now. */
    @get:Synchronized val keptSeconds: Double get() = list.sumOf { it.seconds }

    fun stop() {
        job?.cancel()
        runCatching { call?.cancel() }
        dir.deleteRecursively()
    }
}
