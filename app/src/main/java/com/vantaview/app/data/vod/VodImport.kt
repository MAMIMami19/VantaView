// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.vod

import android.util.JsonReader
import android.util.JsonToken
import com.vantaview.app.data.db.VodEntity
import com.vantaview.app.data.db.VodEpisodeEntity
import com.vantaview.app.data.remote.M3uEntry
import java.io.Reader

/** Turns a playlist's film and series entries into rows for the Movies and TV Series pages. */
object VodImport {

    /**
     * M3U entries: an address with /series/ or a name with S01E02 is an episode (grouped into its show);
     * anything else is a film. [stored] turns an address into how it's kept (logins swapped for placeholders).
     */
    fun fromM3u(providerId: Long, entries: List<M3uEntry>, stored: (String) -> String): Pair<List<VodEntity>, List<VodEpisodeEntity>> {
        val films = ArrayList<VodEntity>()
        val shows = LinkedHashMap<String, VodEntity>()
        val episodes = ArrayList<VodEpisodeEntity>()
        val counts = HashMap<String, Int>()
        entries.forEachIndexed { i, e ->
            val ep = VodNames.episode(e.name)
            val isSeries = ep != null || e.url.contains("/series/")
            if (isSeries) {
                val show = ep?.first ?: VodNames.clean(e.name)
                val key = VodNames.key(show)
                val n = (counts[key] ?: 0) + 1
                counts[key] = n
                val season = ep?.second ?: 1
                val episode = ep?.third ?: n
                if (key !in shows) shows[key] = VodEntity(
                    providerId = providerId, kind = "series", remoteId = key, name = show, norm = VodNames.norm(show),
                    year = VodNames.year(e.name), groupName = e.group, poster = e.logo, position = i,
                )
                episodes += VodEpisodeEntity(providerId = providerId, seriesId = key, season = season, episode = episode, title = null, url = stored(e.url))
            } else {
                films += VodEntity(
                    providerId = providerId, kind = "movie", remoteId = sha1(e.url), name = VodNames.clean(e.name),
                    norm = VodNames.norm(e.name), year = VodNames.year(e.name), groupName = e.group, poster = e.logo,
                    url = stored(e.url), position = i,
                )
            }
        }
        return (films + shows.values) to episodes
    }

    /** Reads an Xtream list (an array, or an object keyed by number) one item at a time, as plain text fields. */
    fun readItems(reader: Reader, each: (Map<String, String>) -> Unit) {
        JsonReader(reader).use { r ->
            r.isLenient = true
            when (r.peek()) {
                JsonToken.BEGIN_ARRAY -> {
                    r.beginArray()
                    while (r.hasNext()) item(r)?.let(each)
                    r.endArray()
                }
                JsonToken.BEGIN_OBJECT -> {
                    r.beginObject()
                    while (r.hasNext()) { r.nextName(); item(r)?.let(each) }
                    r.endObject()
                }
                else -> r.skipValue()
            }
        }
    }

    private fun item(r: JsonReader): Map<String, String>? {
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return null }
        val m = HashMap<String, String>()
        r.beginObject()
        while (r.hasNext()) {
            val k = r.nextName()
            when (r.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> m[k] = r.nextString()
                JsonToken.BOOLEAN -> m[k] = r.nextBoolean().toString()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return m
    }

    /** An Xtream film from get_vod_streams; [groups] maps category ids to names. */
    fun xtreamFilm(providerId: Long, o: Map<String, String>, groups: Map<String, String>, i: Int): VodEntity? {
        val id = o["stream_id"]?.ifBlank { null } ?: return null
        val name = o["name"]?.trim()?.ifBlank { null } ?: return null
        return VodEntity(
            providerId = providerId, kind = "movie", remoteId = id, name = VodNames.clean(name), norm = VodNames.norm(name),
            year = o["year"]?.take(4)?.toIntOrNull() ?: VodNames.year(name), groupName = o["category_id"]?.let { groups[it] },
            poster = o["stream_icon"]?.ifBlank { null }, ext = o["container_extension"]?.ifBlank { null } ?: "mp4",
            rating = o["rating"]?.takeIf { it.isNotBlank() && it != "0" }, added = o["added"]?.toLongOrNull() ?: 0, position = i,
        )
    }

    /** An Xtream series from get_series. */
    fun xtreamSeries(providerId: Long, o: Map<String, String>, groups: Map<String, String>, i: Int): VodEntity? {
        val id = o["series_id"]?.ifBlank { null } ?: return null
        val name = o["name"]?.trim()?.ifBlank { null } ?: return null
        val date = o["releaseDate"] ?: o["release_date"]
        return VodEntity(
            providerId = providerId, kind = "series", remoteId = id, name = VodNames.clean(name), norm = VodNames.norm(name),
            year = date?.take(4)?.toIntOrNull() ?: VodNames.year(name), groupName = o["category_id"]?.let { groups[it] },
            poster = o["cover"]?.ifBlank { null }, plot = o["plot"]?.ifBlank { null },
            rating = o["rating"]?.takeIf { it.isNotBlank() && it != "0" }, added = o["last_modified"]?.toLongOrNull() ?: 0, position = i,
        )
    }

    private fun sha1(s: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
