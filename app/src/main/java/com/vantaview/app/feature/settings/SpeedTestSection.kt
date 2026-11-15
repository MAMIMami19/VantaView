// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/** Measures download speed: from a public speed-test server, or from a channel of your own playlist. */
@HiltViewModel
class SpeedTestViewModel @Inject constructor(
    private val http: OkHttpClient,
    private val db: com.vantaview.app.data.db.VantaDb,
    private val repo: com.vantaview.app.data.repo.ProviderRepository,
    private val session: com.vantaview.app.data.profile.ProfileSession,
) : ViewModel() {
    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text
    private var job: Job? = null

    fun internet() = run("the internet", "https://speed.cloudflare.com/__down?bytes=50000000", emptyMap())

    fun playlist() {
        job?.cancel()
        job = viewModelScope.launch {
            _text.value = "Finding a channel…"
            val pid = session.lastProvider().takeIf { it > 0 } ?: db.providers().allFor(session.id).firstOrNull()?.id
            val ch = pid?.let { db.content().channelsPage(it, 1).firstOrNull() }
            if (ch == null) { _text.value = "Add a playlist first."; return@launch }
            val t = runCatching { repo.resolveStream(ch) }.getOrNull()
            if (t == null) { _text.value = "Couldn't open a channel to test."; return@launch }
            measure("your playlist (${com.vantaview.app.data.ChannelNames.display(ch.name)})", t.url, t.headers)
        }
    }

    private fun run(what: String, url: String, headers: Map<String, String>) {
        job?.cancel()
        job = viewModelScope.launch { measure(what, url, headers) }
    }

    /** Downloads for up to eight seconds and reports the speed. Live channels only send in real time, so that's a floor. */
    private suspend fun measure(what: String, url: String, headers: Map<String, String>) {
        _text.value = "Testing $what…"
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val b = Request.Builder().url(url)
                headers.forEach { (k, v) -> b.header(k, v) }
                val t0 = System.nanoTime()
                var first = 0L
                var bytes = 0L
                http.newCall(b.build()).execute().use { r ->
                    if (!r.isSuccessful) error("the server said ${r.code}")
                    first = System.nanoTime() - t0
                    val input = r.body!!.byteStream()
                    val buf = ByteArray(64 * 1024)
                    while (isActive && System.nanoTime() - t0 < 8_000_000_000L) {
                        val n = input.read(buf); if (n < 0) break; bytes += n
                    }
                }
                val secs = (System.nanoTime() - t0 - first) / 1e9
                Triple(bytes * 8 / 1e6 / secs.coerceAtLeast(0.2), first / 1_000_000, bytes)
            }
        }
        _text.value = result.fold(
            { (mbps, ms, bytes) ->
                val verdict = when {
                    mbps >= 25 -> "Plenty for 4K."
                    mbps >= 8 -> "Good for HD (1080p)."
                    mbps >= 4 -> "OK for HD at times; SD is safe. A bigger buffer helps."
                    else -> "Slow: expect stopping to load. Try SD streams and a Large buffer."
                }
                "%.1f Mbit/s from %s (first reply in %d ms, %.1f MB). %s".format(java.util.Locale.UK, mbps, what, ms, bytes / 1e6, verdict) +
                    if (what.startsWith("your playlist")) " Live channels only send as fast as they play, so this shows the channel's own rate at least." else ""
            },
            { "The test didn't finish (${it.message})." },
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SpeedTestSection(vm: SpeedTestViewModel = hiltViewModel()) {
    val text by vm.text.collectAsStateWithLifecycle()
    Section("Speed test") {
        Text("Check how fast this device can download, to choose a buffer size or find where stopping comes from.", color = Vanta.TextDim, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FocusCard(onClick = vm::internet) { Text("Test the internet", color = Vanta.Text, maxLines = 1, softWrap = false) }
            FocusCard(onClick = vm::playlist) { Text("Test my playlist", color = Vanta.Text, maxLines = 1, softWrap = false) }
        }
        text?.let { Text(it, color = Vanta.Cyan, fontSize = 14.sp) }
        Text("The internet test downloads from Cloudflare's speed-test server.", color = Vanta.TextDim, fontSize = 11.sp)
    }
}
