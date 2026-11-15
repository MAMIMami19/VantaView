// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Where an episode's intro and end credits are, in milliseconds (null when nobody has timed them). */
data class Segments(val introStart: Long?, val introEnd: Long?, val outroStart: Long?) {
    val hasIntro get() = introEnd != null && introEnd > (introStart ?: 0L)
}

/**
 * Intro and end-credits times from the open, community-run skip databases: IntroDB first, then SkipDB.
 * Looked up by IMDb id, season and episode; reads need no account.
 */
@Singleton
class SkipSegments @Inject constructor(private val http: OkHttpClient) {
    private val cache = ConcurrentHashMap<String, Segments>()

    suspend fun lookup(imdb: String, season: Int?, episode: Int?, durationMs: Long): Segments? {
        if (!imdb.startsWith("tt")) return null
        val key = "$imdb:$season:$episode"
        cache[key]?.let { return it }
        val found = runCatching { introDb(imdb, season, episode) }.getOrNull()?.takeIf { it.hasIntro || it.outroStart != null }
            ?: runCatching { skipDb(imdb, season, episode, durationMs) }.getOrNull()
            ?: Segments(null, null, null)
        cache[key] = found
        return found
    }

    private suspend fun get(url: String): JSONObject? = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("User-Agent", "VantaView").build()).execute().use { r ->
            if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty())
        }
    }

    private suspend fun introDb(imdb: String, season: Int?, episode: Int?): Segments? {
        val q = if (season != null && episode != null) "season=$season&episode=$episode" else "is_movie=true"
        val o = get("https://api.introdb.app/segments?imdb_id=$imdb&$q") ?: return null
        return parse(o.optJSONObject("intro"), o.optJSONObject("outro"))
    }

    private suspend fun skipDb(imdb: String, season: Int?, episode: Int?, durationMs: Long): Segments? {
        val q = buildString {
            append("imdb_id=$imdb")
            if (season != null && episode != null) append("&season=$season&episode=$episode")
            if (durationMs > 0) append("&duration=${durationMs / 1000}")
        }
        val o = get("https://api.skipdb.tv/api/segments?$q")?.optJSONObject("segments") ?: return null
        // A match the database marks as out of range is for a different cut of the episode: ignore it.
        fun usable(s: JSONObject?) = s?.takeIf { it.optString("match") != "out-of-range" }
        return parse(usable(o.optJSONObject("intro")), usable(o.optJSONObject("outro")))
    }

    companion object {
        /** Reads a segment pair; times in "start_ms"/"end_ms", or "start_sec"/"end_sec". */
        fun parse(intro: JSONObject?, outro: JSONObject?): Segments {
            fun ms(o: JSONObject?, name: String): Long? = when {
                o == null -> null
                o.has("${name}_ms") && !o.isNull("${name}_ms") -> o.optLong("${name}_ms")
                o.has("${name}_sec") && !o.isNull("${name}_sec") -> (o.optDouble("${name}_sec") * 1000).toLong()
                else -> null
            }
            return Segments(ms(intro, "start"), ms(intro, "end"), ms(outro, "start"))
        }
    }
}
