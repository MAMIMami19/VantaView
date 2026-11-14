// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.aisubs

import android.app.ActivityManager
import android.content.Context
import com.vantaview.app.BuildConfig
import com.vantaview.app.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class AiMode(val label: String, val detail: String) {
    OFF("Off", ""),
    ENGLISH("English subtitles", "Any language turned into English text. Foreign channels are translated."),
    ORIGINAL("Same-language captions", "Text in the language being spoken."),
}

/** Speech models from whisper.cpp (MIT licence), downloaded only when chosen. */
enum class AiModel(val label: String, val detail: String, val file: String, val mb: Int) {
    FAST("Fast", "Works on most devices. Less accurate.", "ggml-tiny-q5_1.bin", 31),
    BETTER("Better", "More accurate. Needs a recent phone or a strong TV box.", "ggml-base-q5_1.bin", 57),
}

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val percent: Int) : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

/** Settings and speech models for AI subtitles (beta). Everything runs on the device. */
@Singleton
class AiSubtitles @Inject constructor(
    @ApplicationContext private val context: Context,
    http: OkHttpClient,
    @AppScope private val scope: CoroutineScope,
) {
    private val http = http.newBuilder().followRedirects(true).build()
    private val sp = context.getSharedPreferences("aisubs", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "aisubs")

    var mode: AiMode
        get() = runCatching { AiMode.valueOf(sp.getString("mode", null)!!) }.getOrDefault(AiMode.OFF)
        set(v) { sp.edit().putString("mode", v.name).apply() }

    var model: AiModel
        get() = runCatching { AiModel.valueOf(sp.getString("model", null)!!) }.getOrDefault(AiModel.FAST)
        set(v) { sp.edit().putString("model", v.name).apply(); _model.value = stateOf(v) }

    private val _model = MutableStateFlow(stateOf(model))
    val modelState: StateFlow<ModelState> = _model

    fun file(m: AiModel = model) = File(dir, m.file)
    private fun stateOf(m: AiModel): ModelState = if (file(m).length() > 1_000_000) ModelState.Ready else ModelState.Missing

    /** True when the app has the speech engine for this device's processor. */
    val supported: Boolean get() = WhisperNative.available

    /** Devices with under 2 GB of memory (like the 1 GB Fire TV Stick) will struggle. */
    val lowMemory: Boolean
        get() = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem < 1_800_000_000L
        }.getOrDefault(false)

    /** Ready to run in a player now. */
    val active: Boolean get() = mode != AiMode.OFF && supported && file().length() > 1_000_000

    fun download() {
        if (_model.value is ModelState.Downloading) return
        val m = model
        scope.launch(Dispatchers.IO) {
            try {
                dir.mkdirs()
                val tmp = File(dir, m.file + ".part")
                _model.value = ModelState.Downloading(0)
                http.newCall(
                    Request.Builder().url("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${m.file}")
                        .header("User-Agent", "VantaView/${BuildConfig.VERSION_NAME}").build()
                ).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    val body = r.body ?: error("empty")
                    val total = body.contentLength().takeIf { it > 0 } ?: (m.mb * 1_048_576L)
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(128 * 1024)
                            var done = 0L; var last = -1
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != last) { last = pct; _model.value = ModelState.Downloading(pct) }
                            }
                        }
                    }
                }
                tmp.renameTo(file(m))
                _model.value = ModelState.Ready
            } catch (e: Exception) {
                _model.value = ModelState.Failed("The speech model couldn't be downloaded. Check the connection and try again.")
            }
        }
    }

    fun deleteModels() {
        dir.listFiles()?.forEach { it.delete() }
        _model.value = stateOf(model)
    }
}
