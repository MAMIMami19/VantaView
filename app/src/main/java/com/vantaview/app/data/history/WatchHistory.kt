// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.history

import android.content.Context
import com.vantaview.app.data.profile.ProfileSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a profile got to in a film or series. One entry per film, and one per series (its latest episode).
 */
data class HistoryEntry(
    /** "movie" or "series". */
    val type: String,
    /** The film's id, or the series' id. */
    val metaId: String,
    /** The id that was played: the film id, or the episode's video id. */
    val videoId: String,
    val title: String,
    val poster: String?,
    val background: String?,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    /** Highest season with released episodes when this was last watched (series only). */
    val knownSeason: Int = 0,
) {
    val key: String get() = "$type:$metaId"
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    /** Counted as watched once 92% has been played or under 3 minutes remain. */
    val finished: Boolean get() = durationMs > 0 && (fraction >= 0.92f || durationMs - positionMs < 180_000)
    /** Worth resuming: past the first minute and not finished. */
    val resumable: Boolean get() = positionMs > 60_000 && !finished
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)
}

@Singleton
class WatchHistory @Inject constructor(
    @ApplicationContext context: Context,
    private val session: ProfileSession,
) {
    private val sp = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    private val changed = MutableStateFlow(0)

    /** History of the profile in use, most recent first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val entries: Flow<List<HistoryEntry>> = combine(session.current, changed) { id, _ -> load(id) }

    fun all(): List<HistoryEntry> = load(session.id)

    fun get(type: String, metaId: String): HistoryEntry? = all().firstOrNull { it.type == type && it.metaId == metaId }

    @Synchronized
    fun record(e: HistoryEntry) {
        val list = load(session.id).filter { it.key != e.key }.toMutableList()
        list.add(0, e)
        save(session.id, list.take(MAX))
    }

    @Synchronized
    fun remove(type: String, metaId: String) = save(session.id, load(session.id).filter { !(it.type == type && it.metaId == metaId) })

    /** "New season" pop-ups already seen, per profile, as "seriesId:season". */
    fun newSeasonSeen(seriesId: String, season: Int): Boolean =
        sp.getStringSet("seen_${session.id}", emptySet())!!.contains("$seriesId:$season")

    fun markNewSeasonSeen(seriesId: String, season: Int) {
        val k = "seen_${session.id}"
        sp.edit().putStringSet(k, sp.getStringSet(k, emptySet())!! + "$seriesId:$season").apply()
    }

    private fun load(profile: Long): List<HistoryEntry> = runCatching {
        val arr = JSONArray(sp.getString("p_$profile", "[]"))
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
            HistoryEntry(
                type = o.optString("type"),
                metaId = o.optString("metaId"),
                videoId = o.optString("videoId"),
                title = o.optString("title"),
                poster = o.optString("poster").ifBlank { null },
                background = o.optString("background").ifBlank { null },
                season = if (o.has("season")) o.optInt("season") else null,
                episode = if (o.has("episode")) o.optInt("episode") else null,
                episodeTitle = o.optString("episodeTitle").ifBlank { null },
                positionMs = o.optLong("positionMs"),
                durationMs = o.optLong("durationMs"),
                updatedAt = o.optLong("updatedAt"),
                knownSeason = o.optInt("knownSeason"),
            )
        }
    }.getOrDefault(emptyList())

    private fun save(profile: Long, list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("type", e.type); put("metaId", e.metaId); put("videoId", e.videoId); put("title", e.title)
                put("poster", e.poster.orEmpty()); put("background", e.background.orEmpty())
                e.season?.let { put("season", it) }; e.episode?.let { put("episode", it) }
                put("episodeTitle", e.episodeTitle.orEmpty())
                put("positionMs", e.positionMs); put("durationMs", e.durationMs); put("updatedAt", e.updatedAt)
                put("knownSeason", e.knownSeason)
            })
        }
        sp.edit().putString("p_$profile", arr.toString()).apply()
        changed.value++
    }

    private companion object { const val MAX = 200 }
}
