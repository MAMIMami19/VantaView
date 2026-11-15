// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.backup

import com.vantaview.app.data.history.HistoryEntry
import com.vantaview.app.data.history.WatchHistory
import com.vantaview.app.data.library.LibraryStore
import com.vantaview.app.data.library.SavedTitle
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** What a Nuvio (NuvioSync) backup holds besides its add-ons. */
data class NuvioData(
    val watchlist: List<SavedTitle>,
    val watchedFilms: List<SavedTitle>,
    /** Continue watching: one entry per film or series, newest last. */
    val history: List<HistoryEntry>,
) {
    val empty get() = watchlist.isEmpty() && watchedFilms.isEmpty() && history.isEmpty()
    fun summary(): String = listOfNotNull(
        watchlist.size.takeIf { it > 0 }?.let { com.vantaview.app.ui.tr("{0} on the Watchlist", it) },
        watchedFilms.size.takeIf { it > 0 }?.let { com.vantaview.app.ui.tr("{0} watched films", it) },
        history.size.takeIf { it > 0 }?.let { com.vantaview.app.ui.tr("{0} in Continue watching", it) },
    ).joinToString(", ")
}

/**
 * Reads a NuvioSync backup ({"app":"NuvioSync","original":{library, watched, progress, addons, collections}}).
 * Library becomes the Watchlist, watched films are marked Watched, and progress (plus watched episodes)
 * becomes Continue watching, so series carry on from the next episode. Collections aren't brought over.
 */
object NuvioImport {
    fun isNuvio(text: String): Boolean = runCatching {
        val o = JSONObject(text.trim().removePrefix("﻿"))
        o.optJSONObject("original") != null && (o.optString("app").contains("nuvio", true) || o.optJSONObject("original")!!.has("progress"))
    }.getOrDefault(false)

    private fun list(a: JSONArray?) = a?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }.orEmpty()

    private fun type(o: JSONObject) = when (o.optString("content_type")) { "movie" -> "movie"; "series", "tv" -> "series"; else -> null }

    /** Cinemeta's poster for an IMDb id, when the backup has none. */
    private fun poster(id: String) = if (id.startsWith("tt")) "https://images.metahub.space/poster/medium/$id/img" else null

    fun parse(text: String): NuvioData {
        val o = JSONObject(text.trim().removePrefix("﻿")).optJSONObject("original") ?: return NuvioData(emptyList(), emptyList(), emptyList())
        val library = list(o.optJSONArray("library"))
        val names = HashMap<String, String>()
        val posters = HashMap<String, String>()
        library.forEach { l ->
            val id = l.optString("content_id")
            l.optString("name").ifBlank { null }?.let { names[id] = it }
            l.optString("poster").takeIf { it.startsWith("http") }?.let { posters[id] = it }
        }
        val watched = list(o.optJSONArray("watched"))
        watched.forEach { w -> w.optString("title").ifBlank { null }?.let { names.putIfAbsent(w.optString("content_id"), it) } }

        val watchlist = library.mapNotNull { l ->
            val t = type(l) ?: return@mapNotNull null
            val id = l.optString("content_id").ifBlank { return@mapNotNull null }
            SavedTitle(t, id, names[id] ?: id, posters[id] ?: poster(id), l.optString("release_info").ifBlank { null }, l.optLong("added_at", System.currentTimeMillis()))
        }
        val watchedFilms = watched.filter { type(it) == "movie" }.mapNotNull { w ->
            val id = w.optString("content_id").ifBlank { return@mapNotNull null }
            SavedTitle("movie", id, names[id] ?: id, posters[id] ?: poster(id), null, w.optLong("watched_at", System.currentTimeMillis()))
        }.distinctBy { it.id }

        // Where each title got to: progress first, then a finished watched episode if newer.
        val latest = HashMap<String, HistoryEntry>()
        fun offer(e: HistoryEntry) {
            val k = e.key
            if ((latest[k]?.updatedAt ?: Long.MIN_VALUE) < e.updatedAt) latest[k] = e
        }
        list(o.optJSONArray("progress")).forEach { p ->
            val t = type(p) ?: return@forEach
            val id = p.optString("content_id").ifBlank { return@forEach }
            val season = p.optInt("season", 0).takeIf { it > 0 }
            val episode = p.optInt("episode", 0).takeIf { it > 0 }
            val duration = p.optLong("duration", 0)
            if (duration <= 0) return@forEach
            offer(
                HistoryEntry(
                    type = t, metaId = id, videoId = p.optString("video_id").ifBlank { if (season != null && episode != null) "$id:$season:$episode" else id },
                    title = names[id] ?: id, poster = posters[id] ?: poster(id), background = null,
                    season = season, episode = episode, episodeTitle = null,
                    positionMs = p.optLong("position", 0).coerceIn(0, duration), durationMs = duration,
                    updatedAt = p.optLong("last_watched", 0), knownSeason = season ?: 0,
                )
            )
        }
        watched.filter { type(it) == "series" }.forEach { w ->
            val id = w.optString("content_id").ifBlank { return@forEach }
            val season = w.optInt("season", 0).takeIf { it > 0 } ?: return@forEach
            val episode = w.optInt("episode", 0).takeIf { it > 0 } ?: return@forEach
            offer(
                HistoryEntry(
                    type = "series", metaId = id, videoId = "$id:$season:$episode", title = names[id] ?: id,
                    poster = posters[id] ?: poster(id), background = null, season = season, episode = episode, episodeTitle = null,
                    // Finished: Continue watching offers the next episode.
                    positionMs = 1, durationMs = 1, updatedAt = w.optLong("watched_at", 0), knownSeason = season,
                )
            )
        }
        return NuvioData(watchlist, watchedFilms, latest.values.sortedBy { it.updatedAt })
    }
}

/** Puts a Nuvio backup's Watchlist, Watched and Continue watching into the profile in use. */
@Singleton
class NuvioRestore @Inject constructor(private val library: LibraryStore, private val history: WatchHistory) {
    fun apply(d: NuvioData) {
        d.watchlist.forEach { library.setWatchlist(it, true) }
        d.watchedFilms.forEach { library.setWatched(it, true) }
        // Oldest first, so the newest ends up at the front of Continue watching.
        d.history.forEach { history.record(it) }
    }
}
