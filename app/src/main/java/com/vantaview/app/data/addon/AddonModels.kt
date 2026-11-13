// SPDX-License-Identifier: GPL-3.0-or-later
// Manifest parsing adapted from Stream Goat (c) Syed Ali Hamza, GPL-3.0
// https://github.com/sherazi5110/stream-goat  (Stremio.kt: Manifest.parse, serves, normalize)
package com.vantaview.app.data.addon

import org.json.JSONArray
import org.json.JSONObject

data class CatalogDef(
    val type: String,
    val id: String,
    val name: String,
    val searchable: Boolean,
    /** True when the catalog only works with an extra value (such as a search term), so it can't be a row. */
    val needsExtra: Boolean,
    /** Genre filter values the catalog accepts (used for family rows on Kids profiles). */
    val genres: List<String> = emptyList(),
    /** Names of extras the catalog can't work without. */
    val requiredExtras: List<String> = emptyList(),
)

data class ResourceDef(val name: String, val types: List<String>?, val idPrefixes: List<String>?)

data class Manifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val logo: String?,
    val types: List<String>,
    val idPrefixes: List<String>?,
    val catalogs: List<CatalogDef>,
    val resources: List<ResourceDef>,
    val configurationRequired: Boolean,
) {
    /** Whether this add-on answers [resource] requests for an item of [type] with [id]. */
    fun serves(resource: String, type: String, id: String): Boolean {
        val r = resources.firstOrNull { it.name == resource } ?: return false
        val types = r.types ?: this.types
        val prefixes = r.idPrefixes ?: idPrefixes
        return type in types && (prefixes.isNullOrEmpty() || prefixes.any { id.startsWith(it) })
    }

    fun hasResource(name: String) = resources.any { it.name == name }

    companion object {
        private fun strings(a: JSONArray?) = a?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }

        fun parse(json: JSONObject): Manifest {
            val catalogs = json.optJSONArray("catalogs")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { c ->
                    val extras = c.optJSONArray("extra")?.let { e -> (0 until e.length()).mapNotNull { e.optJSONObject(it) } }.orEmpty()
                    val legacyRequired = strings(c.optJSONArray("extraRequired")).orEmpty()
                    val names = extras.map { it.optString("name") } + strings(c.optJSONArray("extraSupported")).orEmpty()
                    val genreOptions = extras.firstOrNull { it.optString("name") == "genre" }
                        ?.let { strings(it.optJSONArray("options")) }.orEmpty()
                        .ifEmpty { strings(c.optJSONArray("genres")).orEmpty() }
                    CatalogDef(
                        type = c.optString("type"),
                        id = c.optString("id"),
                        name = c.optString("name").ifBlank { c.optString("id") },
                        searchable = "search" in names,
                        needsExtra = extras.any { it.optBoolean("isRequired") } || legacyRequired.isNotEmpty(),
                        genres = genreOptions,
                        requiredExtras = extras.filter { it.optBoolean("isRequired") }.map { it.optString("name") } + legacyRequired,
                    )
                }
            }.orEmpty()
            val resources = json.optJSONArray("resources")?.let { arr ->
                (0 until arr.length()).map { i ->
                    when (val r = arr.get(i)) {
                        is JSONObject -> ResourceDef(r.optString("name"), strings(r.optJSONArray("types")), strings(r.optJSONArray("idPrefixes")))
                        else -> ResourceDef(r.toString(), null, null)
                    }
                }
            }.orEmpty()
            return Manifest(
                id = json.getString("id"),
                name = json.optString("name").ifBlank { json.getString("id") },
                version = json.optString("version"),
                description = json.optString("description"),
                logo = json.optString("logo").takeIf { it.startsWith("http") },
                types = strings(json.optJSONArray("types")).orEmpty(),
                idPrefixes = strings(json.optJSONArray("idPrefixes")),
                catalogs = catalogs,
                resources = resources,
                configurationRequired = json.optJSONObject("behaviorHints")?.optBoolean("configurationRequired") == true,
            )
        }

        /** "stremio://host/x/manifest.json" or a base address becomes "https://host/x/manifest.json". */
        fun normalizeUrl(input: String): String {
            var url = input.trim()
            if (url.startsWith("stremio://", true)) url = "https://" + url.substring("stremio://".length)
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) url = "https://$url"
            url = url.substringBefore('#').trimEnd('/')
            if (!url.endsWith("/manifest.json")) url += "/manifest.json"
            return url
        }
    }
}

/** A title in a catalog row. */
data class MetaPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val year: String?,
    val rating: String?,
    val genres: List<String> = emptyList(),
)

data class Video(
    val id: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val thumbnail: String?,
    val overview: String?,
    val released: String?,
)

data class MetaDetail(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val logo: String?,
    val description: String?,
    val year: String?,
    val runtime: String?,
    val rating: String?,
    val genres: List<String>,
    val cast: List<String>,
    val director: List<String>,
    val videos: List<Video>,
)

/** One playable stream from an add-on. Only direct links are kept. */
data class AddonStream(
    val url: String,
    val addonName: String,
    val label: String,
    val headers: Map<String, String>,
    val quality: Int,
    val sizeBytes: Long?,
    val addonOrder: Int,
)

object AddonJson {
    private fun strings(a: JSONArray?) = a?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() } }.orEmpty()

    fun preview(o: JSONObject, fallbackType: String): MetaPreview? {
        val id = o.optString("id").ifBlank { return null }
        return MetaPreview(
            id = id,
            type = o.optString("type").ifBlank { fallbackType },
            name = o.optString("name").ifBlank { return null },
            poster = o.optString("poster").takeIf { it.startsWith("http") },
            year = o.optString("releaseInfo").ifBlank { o.optString("year") }.ifBlank { null },
            rating = o.optString("imdbRating").ifBlank { null },
            genres = strings(o.optJSONArray("genres")).ifEmpty { strings(o.optJSONArray("genre")) },
        )
    }

    fun detail(o: JSONObject, fallbackType: String): MetaDetail {
        val videos = o.optJSONArray("videos")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { v ->
                val vid = v.optString("id").ifBlank { return@mapNotNull null }
                Video(
                    id = vid,
                    title = v.optString("title").ifBlank { v.optString("name") },
                    season = v.optInt("season", 0),
                    episode = v.optInt("episode", v.optInt("number", 0)),
                    thumbnail = v.optString("thumbnail").takeIf { it.startsWith("http") },
                    overview = v.optString("overview").ifBlank { v.optString("description") }.ifBlank { null },
                    released = v.optString("released").ifBlank { null },
                )
            }
        }.orEmpty().sortedWith(compareBy({ if (it.season == 0) Int.MAX_VALUE else it.season }, { it.episode }))
        return MetaDetail(
            id = o.optString("id"),
            type = o.optString("type").ifBlank { fallbackType },
            name = o.optString("name"),
            poster = o.optString("poster").takeIf { it.startsWith("http") },
            background = o.optString("background").takeIf { it.startsWith("http") },
            logo = o.optString("logo").takeIf { it.startsWith("http") },
            description = o.optString("description").ifBlank { null },
            year = o.optString("releaseInfo").ifBlank { o.optString("year") }.ifBlank { null },
            runtime = o.optString("runtime").ifBlank { null },
            rating = o.optString("imdbRating").ifBlank { null },
            genres = strings(o.optJSONArray("genres")).ifEmpty { strings(o.optJSONArray("genre")) },
            cast = strings(o.optJSONArray("cast")),
            director = strings(o.optJSONArray("director")),
            videos = videos,
        )
    }

    private val qualityRegex = Regex("(?i)\\b(2160p|4k|uhd|1440p|1080p|fhd|720p|480p|360p)\\b")

    fun qualityOf(text: String): Int = when (qualityRegex.find(text)?.value?.lowercase()) {
        "2160p", "4k", "uhd" -> 2160
        "1440p" -> 1440
        "1080p", "fhd" -> 1080
        "720p" -> 720
        "480p" -> 480
        "360p" -> 360
        else -> 0
    }

    private val sizeRegex = Regex("(?i)(\\d+(?:[.,]\\d+)?)\\s*(GB|MB)")

    private fun sizeOf(text: String): Long? = sizeRegex.find(text)?.let { m ->
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        (n * if (m.groupValues[2].equals("GB", true)) 1_073_741_824.0 else 1_048_576.0).toLong()
    }

    /** Reads an add-on's stream list, keeping only direct links (torrent-only and external links are left out). */
    fun streams(json: JSONObject, addonName: String, addonOrder: Int): List<AddonStream> {
        val arr = json.optJSONArray("streams") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { s ->
            val url = s.optString("url").takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return@mapNotNull null
            val hints = s.optJSONObject("behaviorHints")
            val headers = hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")
                ?.let { h -> h.keys().asSequence().toList().associateWith { h.optString(it) } }.orEmpty()
            val name = s.optString("name").replace('\n', ' ').trim()
            val title = s.optString("title").ifBlank { s.optString("description") }.replace('\n', ' ').trim()
            val all = "$name $title ${hints?.optString("filename").orEmpty()}"
            AddonStream(
                url = url,
                addonName = addonName,
                label = listOf(name, title).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { addonName },
                headers = headers,
                quality = qualityOf(all),
                sizeBytes = hints?.optLong("videoSize")?.takeIf { it > 0 } ?: sizeOf(all),
                addonOrder = addonOrder,
            )
        }
    }

    /** Best first: highest resolution, then the add-on's own order. Unknown resolution sorts below 720p. */
    fun rank(list: List<AddonStream>): List<AddonStream> =
        list.withIndex().sortedWith(
            compareByDescending<IndexedValue<AddonStream>> { if (it.value.quality == 0) 600 else it.value.quality }
                .thenBy { it.value.addonOrder }
                .thenBy { it.index }
        ).map { it.value }
}
