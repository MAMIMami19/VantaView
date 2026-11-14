// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.guide

import android.content.Context
import android.util.Xml
import com.vantaview.app.BuildConfig
import com.vantaview.app.data.country.CountryStore
import com.vantaview.app.data.db.ProgrammeEntity
import com.vantaview.app.data.db.ProviderEntity
import com.vantaview.app.data.db.VantaDb
import com.vantaview.app.data.profile.ProfileSession
import com.vantaview.app.data.security.CredentialCrypto
import com.vantaview.app.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed interface GuideState {
    data class Loading(val stage: String) : GuideState
    data class Ready(val programmes: Int) : GuideState
    data class Failed(val message: String) : GuideState
}

/**
 * Downloads a playlist's TV guide (XMLTV, plain or gzipped), keeps yesterday to 7 days ahead for the
 * channels in use, and answers "what's on" questions for the Guide, Live TV and Home pages.
 */
@Singleton
class GuideRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val db: VantaDb,
    http: OkHttpClient,
    private val crypto: CredentialCrypto,
    private val session: ProfileSession,
    private val countries: CountryStore,
    @AppScope private val scope: CoroutineScope,
) {
    private val sp = context.getSharedPreferences("guide", Context.MODE_PRIVATE)
    // Guides can be large and slow to send.
    private val http = http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()

    private val _states = MutableStateFlow<Map<Long, GuideState>>(emptyMap())
    val states: StateFlow<Map<Long, GuideState>> = _states

    /** Goes up by one each time a guide finishes loading, so pages can re-read. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private fun setState(id: Long, s: GuideState) = _states.update { it + (id to s) }

    /** Loads the guide if it has never loaded, the playlist was re-synced since, or it is over 12 hours old. */
    fun ensureFresh(p: ProviderEntity) {
        if (_states.value[p.id] is GuideState.Loading) return
        val loadedAt = sp.getLong("at_${p.id}", 0)
        val stale = loadedAt < p.lastSyncAt || System.currentTimeMillis() - loadedAt > 12 * 3_600_000L
        if (stale && p.lastSyncAt > 0) refresh(p)
        else if (_states.value[p.id] == null && loadedAt > 0) setState(p.id, GuideState.Ready(sp.getInt("n_${p.id}", 0)))
    }

    fun refresh(p: ProviderEntity) {
        if (_states.value[p.id] is GuideState.Loading) return
        setState(p.id, GuideState.Loading("Loading the TV guide"))
        scope.launch(Dispatchers.IO) {
            try {
                val n = load(p)
                sp.edit().putLong("at_${p.id}", System.currentTimeMillis()).putInt("n_${p.id}", n).apply()
                setState(p.id, GuideState.Ready(n))
                _version.update { it + 1 }
            } catch (e: Exception) {
                setState(p.id, GuideState.Failed(e.message ?: "The TV guide could not be loaded"))
            }
        }
    }

    private fun guideUrl(p: ProviderEntity): String? {
        p.epgUrl?.trim()?.takeIf { it.startsWith("http", true) }?.let { return it }
        if (p.kind == "XTREAM") {
            val u = URLEncoder.encode(p.username, "UTF-8")
            val pw = URLEncoder.encode(crypto.decrypt(p.passwordEnc), "UTF-8")
            return "${p.baseUrl}/xmltv.php?username=$u&password=$pw"
        }
        return null
    }

    private suspend fun load(p: ProviderEntity): Int {
        val url = guideUrl(p) ?: throw IllegalStateException("This playlist doesn't include a TV guide link")
        val pick = countries.chosen(session.id, p.id)
        val keys = (if (pick == null) db.content().guideKeys(p.id) else db.content().guideKeysIn(p.id, pick.toList())).toHashSet()
        if (keys.isEmpty()) throw IllegalStateException("The channels in this playlist don't have guide IDs")

        val from = XmltvTime.dayStart(-1)
        val to = XmltvTime.dayStart(8)
        val agents = listOf("VantaView/${BuildConfig.VERSION_NAME}", "IPTVSmartersPro", null)
        var lastError: Exception? = null
        for (ua in agents) {
            try {
                val b = Request.Builder().url(url)
                if (ua != null) b.header("User-Agent", ua)
                return http.newCall(b.build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("The guide server answered with error ${r.code}")
                    val body = r.body ?: throw IllegalStateException("The guide was empty")
                    parseInto(p.id, unzip(body.byteStream()), keys, from, to)
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("The TV guide could not be loaded")
    }

    /** Guides are often gzipped whatever the address says; look at the first two bytes. */
    private fun unzip(raw: InputStream): InputStream {
        val s = BufferedInputStream(raw, 64 * 1024)
        s.mark(2)
        val a = s.read(); val b = s.read()
        s.reset()
        return if (a == 0x1f && b == 0x8b) BufferedInputStream(GZIPInputStream(s, 64 * 1024), 64 * 1024) else s
    }

    private suspend fun parseInto(providerId: Long, input: InputStream, keys: Set<String>, from: Long, to: Long): Int {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val dao = db.guide()
        var cleared = false
        val batch = ArrayList<ProgrammeEntity>(2000)
        var total = 0

        suspend fun flush() {
            if (batch.isEmpty()) return
            if (!cleared) { dao.deleteFor(providerId); cleared = true }
            dao.insert(batch.toList())
            total += batch.size
            batch.clear()
            setState(providerId, GuideState.Loading("Loading the TV guide: ${"%,d".format(total)} programmes"))
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                val channel = parser.getAttributeValue(null, "channel")
                val start = XmltvTime.parse(parser.getAttributeValue(null, "start"))
                val stop = XmltvTime.parse(parser.getAttributeValue(null, "stop"))
                val wanted = channel != null && channel in keys && start != null && stop != null && stop > from && start < to
                var title: String? = null
                var desc: String? = null
                var cat: String? = null
                // Read (or skip) everything inside <programme>.
                var depth = 1
                while (depth > 0) {
                    event = parser.next()
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            depth++
                            if (wanted && depth == 2) {
                                when (parser.name) {
                                    "title" -> if (title == null) { title = parser.nextText().trim(); depth-- }
                                    "desc" -> if (desc == null) { desc = parser.nextText().trim().take(400); depth-- }
                                    "category" -> if (cat == null) { cat = parser.nextText().trim(); depth-- }
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> depth--
                        XmlPullParser.END_DOCUMENT -> depth = 0
                    }
                }
                if (wanted && !title.isNullOrBlank()) {
                    batch += ProgrammeEntity(
                        providerId = providerId, channelKey = channel!!, start = start!!, stop = stop!!,
                        title = title, description = desc?.ifBlank { null }, category = cat?.ifBlank { null },
                    )
                    if (batch.size >= 2000) flush()
                }
            }
            if (event != XmlPullParser.END_DOCUMENT) event = parser.next()
        }
        flush()
        if (!cleared) {
            dao.deleteFor(providerId)
            throw IllegalStateException("The guide had nothing for your channels in the next week")
        }
        return total
    }

    // ---- Questions the pages ask ----

    suspend fun range(providerId: Long, key: String, from: Long, to: Long): List<ProgrammeEntity> =
        db.guide().range(providerId, key, from, to)

    suspend fun nowNext(providerId: Long, key: String, now: Long = System.currentTimeMillis()): List<ProgrammeEntity> =
        db.guide().nowNext(providerId, key, now)

    suspend fun onNow(providerId: Long, keys: List<String>, now: Long = System.currentTimeMillis()): List<ProgrammeEntity> =
        if (keys.isEmpty()) emptyList() else keys.chunked(500).flatMap { db.guide().onNow(providerId, it, now) }
}
