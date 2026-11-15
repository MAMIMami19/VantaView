// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.stalker

import android.content.Context
import android.util.Base64
import com.vantaview.app.data.addon.AddonJson
import com.vantaview.app.data.addon.AddonStream
import com.vantaview.app.data.addon.CatalogDef
import com.vantaview.app.data.addon.CatalogRef
import com.vantaview.app.data.addon.InstalledAddon
import com.vantaview.app.data.addon.Manifest
import com.vantaview.app.data.addon.MetaDetail
import com.vantaview.app.data.addon.MetaPreview
import com.vantaview.app.data.addon.Video
import com.vantaview.app.data.db.AddonEntity
import com.vantaview.app.data.db.ProviderEntity
import com.vantaview.app.data.db.VantaDb
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Films and series from Stalker portals, shown on the Movies and TV Series pages next to add-on
 * catalogues. Each portal acts like an add-on with one row per category.
 *
 * Ids: "stalker:<playlist id>:<vod|series>:<item id>" for a title, and
 * "stalker:<playlist id>:ep:<season cmd, base64>:<episode>" for an episode.
 */
@Singleton
class StalkerVod @Inject constructor(
    @ApplicationContext context: Context,
    private val db: VantaDb,
    private val client: StalkerClient,
) {
    // Titles opened on a details page are kept, so Continue watching and the Watchlist can open them later.
    private val saved = context.getSharedPreferences("stalker_titles", Context.MODE_PRIVATE)
    private val items = ConcurrentHashMap<String, JSONObject>()
    private val genreCache = ConcurrentHashMap<String, Pair<Long, List<StalkerClient.Genre>>>()

    fun isPortal(ref: CatalogRef) = ref.addon.entity.id < 0
    fun isPortalId(id: String) = id.startsWith("stalker:")

    private fun kindOf(type: String) = if (type == "series") "series" else "vod"

    /** A stand-in add-on for a portal, so its rows fit the Movies and TV Series pages. */
    private fun asAddon(p: ProviderEntity) = InstalledAddon(
        AddonEntity(id = -p.id, profileId = p.profileId, manifestUrl = "stalker://${p.id}", name = p.name, manifestJson = "{}"),
        Manifest(
            id = "stalker.${p.id}", name = p.name, version = "", description = "", logo = null, types = listOf("movie", "series"),
            idPrefixes = listOf("stalker:${p.id}:"), catalogs = emptyList(), resources = emptyList(), configurationRequired = false,
        ),
    )

    private suspend fun genres(p: ProviderEntity, kind: String): List<StalkerClient.Genre> {
        val key = "${p.id}/$kind"
        genreCache[key]?.let { (at, g) -> if (System.currentTimeMillis() - at < 6 * 3_600_000L) return g }
        val g = runCatching { client.vodGenres(p, kind) }.getOrDefault(emptyList())
        if (g.isNotEmpty()) genreCache[key] = System.currentTimeMillis() to g
        return g
    }

    /** Rows for Movies ("movie") or TV Series ("series"): an "All" row first, then each category. */
    suspend fun catalogs(portals: List<ProviderEntity>, type: String, hideAdult: Boolean): List<CatalogRef> = coroutineScope {
        portals.map { p ->
            async {
                val g = genres(p, kindOf(type)).filter { !(hideAdult && it.censored) }
                if (g.isEmpty()) return@async emptyList()
                val addon = asAddon(p)
                val all = CatalogRef(addon, CatalogDef(type, "*", "${p.name} · All", searchable = true, needsExtra = false))
                listOf(all) + g.map { CatalogRef(addon, CatalogDef(type, it.id, "${p.name} · ${it.title}", searchable = false, needsExtra = false)) }
            }
        }.awaitAll().flatten()
    }

    suspend fun load(p: ProviderEntity, ref: CatalogRef): List<MetaPreview> {
        val kind = kindOf(ref.catalog.type)
        val pages = if (ref.catalog.id == "*") 3 else 2
        val out = ArrayList<MetaPreview>()
        for (page in 1..pages) {
            val list = client.vodPage(p, kind, ref.catalog.id, page)
            out += list.mapNotNull { preview(p, kind, ref.catalog.type, it) }
            if (list.size < 10) break
        }
        return out.distinctBy { it.id }
    }

    suspend fun search(portals: List<ProviderEntity>, type: String, query: String): List<MetaPreview> = coroutineScope {
        portals.map { p ->
            async {
                runCatching { client.vodPage(p, kindOf(type), "*", 1, search = query) }.getOrDefault(emptyList())
                    .mapNotNull { preview(p, kindOf(type), type, it) }
            }
        }.awaitAll().flatten()
    }

    private fun preview(p: ProviderEntity, kind: String, type: String, o: JSONObject): MetaPreview? {
        val itemId = o.optString("id").ifBlank { return null }
        val name = o.optString("name").trim().ifBlank { return null }
        val id = "stalker:${p.id}:$kind:$itemId"
        items[id] = o
        return MetaPreview(
            id = id, type = if (o.optString("is_series") == "1") "series" else type, name = name,
            poster = client.absolute(p, o.optString("screenshot_uri").ifBlank { o.optString("cover_big") }),
            year = o.optString("year").takeIf { it.isNotBlank() && it != "N/A" },
            rating = o.optString("rating_imdb").takeIf { it.isNotBlank() && it != "N/A" && it != "0" },
            genres = o.optString("genres_str").split(',').map { it.trim() }.filter { it.isNotBlank() },
        )
    }

    private fun parts(id: String): List<String> = id.split(':', limit = 4)

    private suspend fun portal(id: String): ProviderEntity? =
        parts(id).getOrNull(1)?.toLongOrNull()?.let { db.providers().get(it) }?.takeIf { it.kind == "STALKER" }

    private fun item(id: String): JSONObject? =
        items[id] ?: saved.getString(id, null)?.let { runCatching { JSONObject(it) }.getOrNull() }?.also { items[id] = it }

    suspend fun meta(type: String, id: String): MetaDetail? {
        val p = portal(id) ?: return null
        val o = item(id) ?: return null
        saved.edit().putString(id, o.toString()).apply()
        val kind = parts(id)[2]
        val itemId = parts(id)[3]
        val videos = if (type == "series" || o.optString("is_series") == "1") episodes(p, kind, itemId, o) else emptyList()
        fun list(key: String) = o.optString(key).split(',').map { it.trim() }.filter { it.isNotBlank() && it != "N/A" }
        return MetaDetail(
            id = id, type = type, name = o.optString("name"),
            poster = client.absolute(p, o.optString("screenshot_uri").ifBlank { o.optString("cover_big") }),
            background = null, logo = null,
            description = o.optString("description").ifBlank { null },
            year = o.optString("year").takeIf { it.isNotBlank() && it != "N/A" },
            runtime = o.optString("time").takeIf { it.isNotBlank() && it != "0" }?.let { "$it min" },
            rating = o.optString("rating_imdb").takeIf { it.isNotBlank() && it != "N/A" && it != "0" },
            genres = list("genres_str"), cast = list("actors"), director = list("director"),
            videos = videos,
        )
    }

    private fun epId(p: ProviderEntity, cmd: String, episode: Int) =
        "stalker:${p.id}:ep:" + Base64.encodeToString(cmd.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ":$episode"

    private fun numbers(o: JSONObject): List<Int> =
        o.optJSONArray("series")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).toIntOrNull() } }.orEmpty()

    /** Older portals list episode numbers on the title; newer ones have seasons, each with episodes. */
    private suspend fun episodes(p: ProviderEntity, kind: String, itemId: String, o: JSONObject): List<Video> {
        val own = numbers(o)
        if (own.isNotEmpty()) return own.map { n -> Video(epId(p, o.optString("cmd"), n), "Episode $n", 1, n, null, null, null) }
        val seasons = runCatching { client.seasons(p, kind, itemId) }.getOrDefault(emptyList())
        return seasons.flatMapIndexed { i, s ->
            val season = Regex("""\d+""").find(s.optString("name"))?.value?.toIntOrNull() ?: (i + 1)
            val nums = numbers(s)
            if (nums.isNotEmpty() && s.optString("cmd").isNotBlank()) {
                nums.map { n -> Video(epId(p, s.optString("cmd"), n), "Episode $n", season, n, null, null, null) }
            } else {
                runCatching { client.episodes(p, kind, itemId, s.optString("id")) }.getOrDefault(emptyList()).mapIndexedNotNull { j, e ->
                    val cmd = e.optString("cmd").ifBlank { return@mapIndexedNotNull null }
                    val n = e.optString("series_number").toIntOrNull() ?: (j + 1)
                    Video(epId(p, cmd, n), e.optString("name").ifBlank { "Episode $n" }, season, n, null, e.optString("description").ifBlank { null }, null)
                }
            }
        }
    }

    /** The address to play, asked for just before playback (portal links expire). */
    suspend fun streams(id: String): List<AddonStream> {
        val p = portal(id) ?: return emptyList()
        val bits = parts(id)
        val (cmd, series) = if (bits[2] == "ep") {
            val enc = bits[3].substringBeforeLast(':')
            String(Base64.decode(enc, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)) to bits[3].substringAfterLast(':').toIntOrNull()
        } else {
            (item(id)?.optString("cmd").orEmpty()) to null
        }
        if (cmd.isBlank()) return emptyList()
        val url = client.createLink(p, "vod", cmd, series)
        val label = item(id)?.optString("name").orEmpty()
        return listOf(
            AddonStream(
                url = url, addonName = p.name, label = "From your portal", headers = client.playHeaders,
                quality = AddonJson.qualityOf("$label $url"), sizeBytes = null, addonOrder = -1,
            )
        )
    }
}
