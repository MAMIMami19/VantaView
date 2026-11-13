// SPDX-License-Identifier: GPL-3.0-or-later
// Stream collection adapted from Stream Goat (c) Syed Ali Hamza, GPL-3.0
// https://github.com/sherazi5110/stream-goat  (Stremio.kt: install, loadLinks)
package com.vantaview.app.data.addon

import com.vantaview.app.BuildConfig
import com.vantaview.app.data.db.AddonEntity
import com.vantaview.app.data.db.VantaDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class InstalledAddon(val entity: AddonEntity, val manifest: Manifest) {
    val base: String get() = entity.manifestUrl.removeSuffix("/manifest.json")
}

/** A catalog row: which add-on, which catalog, and an optional genre filter. */
data class CatalogRef(val addon: InstalledAddon, val catalog: CatalogDef, val genre: String? = null) {
    val key: String get() = "${addon.entity.id}/${catalog.type}/${catalog.id}/${genre.orEmpty()}"
    val title: String get() = listOfNotNull(genre, catalog.name).joinToString(" · ") + " · ${addon.manifest.name}"
}

@Singleton
class AddonRepository @Inject constructor(
    private val db: VantaDb,
    private val http: OkHttpClient,
    private val session: com.vantaview.app.data.profile.ProfileSession,
) {
    private val userAgent = "VantaView/${BuildConfig.VERSION_NAME}"
    private val cache = ConcurrentHashMap<String, Pair<Long, JSONObject>>()

    /** Add-ons of the profile in use. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(): Flow<List<InstalledAddon>> =
        session.current.flatMapLatest { db.addons().observeFor(it) }.map { list -> list.mapNotNull(::parse) }

    suspend fun count(): Int = db.addons().countFor(session.id)

    private fun parse(e: AddonEntity): InstalledAddon? =
        runCatching { InstalledAddon(e, Manifest.parse(JSONObject(e.manifestJson))) }.getOrNull()

    suspend fun enabled(): List<InstalledAddon> = db.addons().allFor(session.id).filter { it.enabled }.mapNotNull(::parse)

    /** Downloads the manifest and installs (or updates) the add-on. Throws with a readable message. */
    suspend fun install(input: String): InstalledAddon = withContext(Dispatchers.IO) {
        require(input.isNotBlank()) { "Paste the add-on's install link first" }
        val url = Manifest.normalizeUrl(input)
        val body = try {
            getText(url)
        } catch (e: Exception) {
            throw IllegalArgumentException("Couldn't reach that add-on (${e.message ?: "network error"})")
        }
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw IllegalArgumentException("That link isn't an add-on install link (no manifest.json found)")
        val manifest = runCatching { Manifest.parse(json) }.getOrNull()
            ?: throw IllegalArgumentException("The add-on's manifest is missing its id")
        if (manifest.configurationRequired) {
            throw IllegalArgumentException("This add-on needs setting up first. Open its configure page, then copy the install link it gives you.")
        }
        if (manifest.resources.isEmpty() && manifest.catalogs.isEmpty()) {
            throw IllegalArgumentException("This add-on doesn't offer anything VantaView can use")
        }
        val existing = db.addons().byUrl(session.id, url)
        val position = existing?.position ?: db.addons().allFor(session.id).size
        val entity = AddonEntity(
            id = existing?.id ?: 0,
            profileId = session.id,
            manifestUrl = url,
            name = manifest.name,
            manifestJson = json.toString(),
            enabled = existing?.enabled ?: true,
            position = position,
            addedAt = existing?.addedAt ?: System.currentTimeMillis(),
        )
        val id = db.addons().upsert(entity)
        cache.clear()
        InstalledAddon(entity.copy(id = if (entity.id == 0L) id else entity.id), manifest)
    }

    suspend fun remove(id: Long) { db.addons().delete(id); cache.clear() }

    suspend fun setEnabled(id: Long, on: Boolean) { db.addons().setEnabled(id, on); cache.clear() }

    /** Rows for a page: every catalog of [type] that works without a required extra. */
    suspend fun catalogs(type: String): List<CatalogRef> =
        enabled().flatMap { a -> a.manifest.catalogs.filter { it.type == type && !it.needsExtra }.map { CatalogRef(a, it) } }

    /**
     * Rows for a Kids profile: each catalogue filtered to a family genre where it offers one, plus catalogues
     * that are themselves for children.
     */
    suspend fun kidsCatalogs(type: String): List<CatalogRef> = enabled().flatMap { a ->
        a.manifest.catalogs.filter { it.type == type }.mapNotNull { c ->
            val family = c.genres.firstOrNull { com.vantaview.app.data.profile.KidsFilter.isFamilyGenre(it) }
            val onlyGenreRequired = c.requiredExtras.all { it == "genre" }
            when {
                family != null && onlyGenreRequired -> CatalogRef(a, c, family)
                !c.needsExtra && com.vantaview.app.data.profile.KidsFilter.looksFamily(c.name + " " + c.id) -> CatalogRef(a, c)
                else -> null
            }
        }
    }

    suspend fun loadCatalog(ref: CatalogRef): List<MetaPreview> {
        val extra = ref.genre?.let { "/genre=${enc(it)}" }.orEmpty()
        val url = "${ref.addon.base}/catalog/${enc(ref.catalog.type)}/${enc(ref.catalog.id)}$extra.json"
        return json(url, maxAgeMs = 6 * 3_600_000L)?.optJSONArray("metas")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { AddonJson.preview(it, ref.catalog.type) }
        }.orEmpty().distinctBy { it.id }
    }

    /** Searches every searchable catalog of [type] at once. */
    suspend fun search(type: String, query: String): List<MetaPreview> = coroutineScope {
        val q = query.trim()
        if (q.isEmpty()) return@coroutineScope emptyList()
        enabled().flatMap { a -> a.manifest.catalogs.filter { it.type == type && it.searchable }.map { a to it } }
            .map { (a, c) ->
                async {
                    val url = "${a.base}/catalog/${enc(c.type)}/${enc(c.id)}/search=${enc(q)}.json"
                    json(url, maxAgeMs = 600_000L)?.optJSONArray("metas")?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { AddonJson.preview(it, type) }
                    }.orEmpty()
                }
            }.awaitAll().flatten().distinctBy { it.id }
    }

    /** Full details from the first add-on that has them. */
    suspend fun meta(type: String, id: String): MetaDetail? {
        for (a in enabled().filter { it.manifest.serves("meta", type, id) }) {
            val o = json("${a.base}/meta/${enc(type)}/${enc(id)}.json", maxAgeMs = 6 * 3_600_000L)?.optJSONObject("meta")
            if (o != null && o.optString("name").isNotBlank()) return AddonJson.detail(o, type)
        }
        return null
    }

    /** True when at least one enabled add-on can supply streams for this kind of title. */
    suspend fun hasStreamSource(type: String, id: String): Boolean =
        enabled().any { it.manifest.serves("stream", type, id) }

    /** Streams from every enabled add-on, best first. [id] is the film's id or the episode's video id. */
    suspend fun streams(type: String, id: String): List<AddonStream> = coroutineScope {
        val addons = enabled()
        val jobs = addons.withIndex().filter { it.value.manifest.serves("stream", type, id) }.map { (order, a) ->
            async {
                withTimeoutOrNull(25_000) {
                    json("${a.base}/stream/${enc(type)}/${enc(id)}.json", maxAgeMs = 0)
                }?.let { AddonJson.streams(it, a.manifest.name, order) }.orEmpty()
            }
        }
        AddonJson.rank(jobs.awaitAll().flatten().distinctBy { it.url })
    }

    // ---- Restore: Stremio account import and backup files ----

    /** An add-on found in a Stremio account or a backup file. */
    data class RemoteAddon(val url: String, val name: String, val description: String, val alreadyInstalled: Boolean)

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(request).execute().use { r -> JSONObject(r.body?.string().orEmpty().ifBlank { "{}" }) }
    }

    /**
     * Signs in to Stremio once and returns the add-ons installed on that account.
     * The email, password and session key are not stored.
     */
    suspend fun stremioAccountAddons(email: String, password: String): List<RemoteAddon> = withContext(Dispatchers.IO) {
        require(email.isNotBlank() && password.isNotBlank()) { "Enter your Stremio email and password" }
        val login = try {
            postJson(
                "https://api.strem.io/api/login",
                JSONObject().put("type", "Login").put("email", email.trim()).put("password", password).put("facebook", false),
            )
        } catch (e: Exception) {
            throw IllegalStateException("Couldn't reach Stremio (${e.message ?: "network error"})")
        }
        login.optJSONObject("error")?.let { err ->
            throw IllegalStateException(err.optString("message").ifBlank { "Stremio didn't accept that email or password" })
        }
        val authKey = login.optJSONObject("result")?.optString("authKey").orEmpty()
        if (authKey.isBlank()) throw IllegalStateException("Stremio didn't accept that email or password")

        val got = postJson(
            "https://api.strem.io/api/addonCollectionGet",
            JSONObject().put("type", "AddonCollectionGet").put("authKey", authKey).put("update", true),
        )
        got.optJSONObject("error")?.let { err ->
            throw IllegalStateException(err.optString("message").ifBlank { "Couldn't read your Stremio add-ons" })
        }
        val arr = got.optJSONObject("result")?.optJSONArray("addons") ?: throw IllegalStateException("No add-ons found on that account")
        val installed = db.addons().allFor(session.id).map { it.manifestUrl }.toSet()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { a ->
            val raw = a.optString("transportUrl")
            // Add-ons that only run inside Stremio itself (such as Local Files on 127.0.0.1) can't be used here.
            if (!raw.startsWith("http") || raw.contains("127.0.0.1") || raw.contains("localhost")) return@mapNotNull null
            val url = Manifest.normalizeUrl(raw)
            val m = a.optJSONObject("manifest")
            RemoteAddon(
                url = url,
                name = m?.optString("name").orEmpty().ifBlank { url.substringAfter("://").substringBefore('/') },
                description = m?.optString("description").orEmpty(),
                alreadyInstalled = url in installed,
            )
        }.distinctBy { it.url }
    }

    /**
     * The add-on list as a backup file. Install links can contain personal keys (debrid, API keys),
     * so the file should be kept private.
     */
    suspend fun backupJson(): String {
        val list = org.json.JSONArray()
        db.addons().allFor(session.id).forEach { a ->
            list.put(JSONObject().put("manifestUrl", a.manifestUrl).put("name", a.name).put("enabled", a.enabled))
        }
        return JSONObject()
            .put("app", "VantaView")
            .put("kind", "addons")
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("addons", list)
            .toString(2)
    }

    /** Reads a VantaView add-on backup, or a plain list of install links (one per line). */
    suspend fun readBackup(text: String): List<RemoteAddon> {
        val installed = db.addons().allFor(session.id).map { it.manifestUrl }.toSet()
        val trimmed = text.trim()
        val pairs: List<Pair<String, String>> = when {
            trimmed.startsWith("{") -> {
                val o = runCatching { JSONObject(trimmed) }.getOrNull() ?: throw IllegalArgumentException("That file isn't a VantaView add-on backup")
                val arr = o.optJSONArray("addons") ?: throw IllegalArgumentException("That file has no add-ons in it")
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { a ->
                    val u = a.optString("manifestUrl").ifBlank { a.optString("transportUrl") }.ifBlank { return@mapNotNull null }
                    u to a.optString("name").ifBlank { a.optJSONObject("manifest")?.optString("name").orEmpty() }
                }
            }
            trimmed.startsWith("[") -> {
                val arr = runCatching { org.json.JSONArray(trimmed) }.getOrNull() ?: throw IllegalArgumentException("That file couldn't be read")
                (0 until arr.length()).mapNotNull { i ->
                    when (val v = arr.get(i)) {
                        is JSONObject -> v.optString("manifestUrl").ifBlank { v.optString("transportUrl") }.ifBlank { null }
                            ?.let { it to v.optJSONObject("manifest")?.optString("name").orEmpty() }
                        else -> v.toString().takeIf { it.startsWith("http") || it.startsWith("stremio://") }?.let { it to "" }
                    }
                }
            }
            else -> trimmed.lines().map { it.trim() }.filter { it.startsWith("http") || it.startsWith("stremio://") }.map { it to "" }
        }
        if (pairs.isEmpty()) throw IllegalArgumentException("No add-on links were found in that file")
        return pairs.map { (u, n) ->
            val url = Manifest.normalizeUrl(u)
            RemoteAddon(url, n.ifBlank { url.substringAfter("://").substringBefore('/') }, "", url in installed)
        }.distinctBy { it.url }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun getText(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        return http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("error ${r.code}")
            r.body?.string().orEmpty()
        }
    }

    private suspend fun json(url: String, maxAgeMs: Long): JSONObject? = withContext(Dispatchers.IO) {
        if (maxAgeMs > 0) cache[url]?.let { (at, j) -> if (System.currentTimeMillis() - at < maxAgeMs) return@withContext j }
        runCatching { JSONObject(getText(url)) }.getOrNull()?.also { if (maxAgeMs > 0) cache[url] = System.currentTimeMillis() to it }
    }
}
