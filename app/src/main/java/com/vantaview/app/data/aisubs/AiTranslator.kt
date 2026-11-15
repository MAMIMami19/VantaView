// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.aisubs

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.vantaview.app.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the Arabic AI-subtitle mode can translate foreign speech right now. */
sealed interface TranslationState {
    /** The Arabic model is on the device: foreign speech can be translated. */
    data object Ready : TranslationState
    /** The Arabic model isn't downloaded yet: Arabic speech still captions, foreign speech shows as spoken. */
    data object Missing : TranslationState
    /** A translation model is downloading now. */
    data object Downloading : TranslationState
    data class Failed(val message: String) : TranslationState
}

/** What translating one subtitle line came to. */
sealed interface TranslationResult {
    data class Done(val text: String) : TranslationResult
    /** The models for this language aren't downloaded yet; the download has started in the background. */
    data object Downloading : TranslationResult
    /** ML Kit has no translator for this spoken language; the line stays as spoken. */
    data object Unsupported : TranslationResult
}

/**
 * On-device translation into Arabic for AI subtitles (Google ML Kit: free, no account, nothing
 * leaves the device once the models are downloaded).
 *
 * Whisper itself can only translate speech into English — its translate task always outputs
 * English, by model design — so foreign speech is transcribed in its own language and translated
 * here. Arabic speech needs no translation at all.
 *
 * Each language model is about 30 MB, downloaded once when first needed and kept on the device.
 * English is built into the library, so English speech only needs the Arabic model.
 */
@Singleton
class AiTranslator @Inject constructor(
    @AppScope private val scope: CoroutineScope,
) {
    private val manager = RemoteModelManager.getInstance()
    private val translators = HashMap<String, Translator>()
    private val downloading = HashSet<String>()

    /** Languages whose translation model is on the device (English is built in, so always present). */
    @Volatile
    private var downloaded: Set<String> = setOf(TranslateLanguage.ENGLISH)

    private val _state = MutableStateFlow<TranslationState>(TranslationState.Missing)
    /** Whether the Arabic model is on the device. */
    val state: StateFlow<TranslationState> = _state

    init {
        scope.launch(Dispatchers.IO) { refresh() }
    }

    /** Re-reads which models are on the device. */
    private suspend fun refresh() {
        runCatching {
            val models = manager.getDownloadedModels(TranslateRemoteModel::class.java).await()
            downloaded = models.map { it.language }.toSet() + TranslateLanguage.ENGLISH
        }
        _state.value = when {
            synchronized(downloading) { downloading.isNotEmpty() } -> TranslationState.Downloading
            TranslateLanguage.ARABIC in downloaded -> TranslationState.Ready
            else -> TranslationState.Missing
        }
    }

    /** True when [source] can be translated into Arabic right now. */
    private fun ready(source: String) = source in downloaded && TranslateLanguage.ARABIC in downloaded

    private fun translatorFor(source: String): Translator = synchronized(translators) {
        translators.getOrPut(source) {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(TranslateLanguage.ARABIC).build()
            )
        }
    }

    /**
     * Translates one subtitle line into Arabic. Never blocks on a download: when a model is
     * missing, the download starts in the background and [TranslationResult.Downloading] comes back,
     * so the line shows in its own language this time and in Arabic from the next lines on.
     */
    suspend fun toArabic(text: String, sourceLang: String): TranslationResult = withContext(Dispatchers.IO) {
        val src = TranslateLanguage.fromLanguageTag(sourceLang) ?: return@withContext TranslationResult.Unsupported
        if (src == TranslateLanguage.ARABIC) return@withContext TranslationResult.Done(text)
        if (!ready(src)) {
            // The set may be stale (models downloaded before this class started): re-read once.
            refresh()
            if (!ready(src)) {
                downloadInBackground(src)
                return@withContext TranslationResult.Downloading
            }
        }
        runCatching {
            TranslationResult.Done(translatorFor(src).translate(text).await())
        }.getOrElse {
            // The model may have been deleted since the last check: try downloading it again.
            downloadInBackground(src)
            TranslationResult.Downloading
        }
    }

    /** Starts downloading the models for [source] → Arabic, once per language. */
    private fun downloadInBackground(source: String) {
        synchronized(downloading) { if (!downloading.add(source)) return }
        _state.value = TranslationState.Downloading
        scope.launch(Dispatchers.IO) {
            runCatching {
                translatorFor(source).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            }
            synchronized(downloading) { downloading.remove(source) }
            refresh()
        }
    }

    /** Downloads the Arabic model now (from Settings). */
    fun downloadArabic() {
        synchronized(downloading) { if (!downloading.add(TranslateLanguage.ARABIC)) return }
        _state.value = TranslationState.Downloading
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                manager.download(
                    TranslateRemoteModel.Builder(TranslateLanguage.ARABIC).build(),
                    DownloadConditions.Builder().build(),
                ).await()
            }.isSuccess
            synchronized(downloading) { downloading.remove(TranslateLanguage.ARABIC) }
            if (!ok) {
                _state.value = TranslationState.Failed(
                    "The translation model couldn't be downloaded. Check the connection and try again."
                )
            } else refresh()
        }
    }

    /** Deletes every downloaded translation model. */
    fun deleteModels() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                manager.getDownloadedModels(TranslateRemoteModel::class.java).await()
                    .forEach { runCatching { manager.deleteDownloadedModel(it).await() } }
            }
            refresh()
        }
    }
}
