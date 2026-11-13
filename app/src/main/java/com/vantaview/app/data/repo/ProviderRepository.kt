// SPDX-License-Identifier: GPL-3.0-or-later
// Xtream login check adapted from Stream Goat (c) Syed Ali Hamza, GPL-3.0
// https://github.com/sherazi5110/stream-goat  (LiveTv.kt: xtream)
package com.vantaview.app.data.repo

import com.vantaview.app.BuildConfig
import com.vantaview.app.data.db.CategoryEntity
import com.vantaview.app.data.db.ChannelEntity
import com.vantaview.app.data.db.FavouriteEntity
import com.vantaview.app.data.db.ProviderEntity
import com.vantaview.app.data.db.VantaDb
import com.vantaview.app.data.remote.M3uParser
import com.vantaview.app.data.remote.SmartAdd
import com.vantaview.app.data.security.CredentialCrypto
import com.vantaview.app.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SyncState {
    data class Running(val stage: String) : SyncState
    data class Done(val channels: Int, val skippedVod: Int) : SyncState
    data class Failed(val message: String) : SyncState
}

/** What the player needs to open a channel. */
data class PlayTarget(val url: String, val altUrl: String?, val headers: Map<String, String>)

@Singleton
class ProviderRepository @Inject constructor(
    private val db: VantaDb,
    private val http: OkHttpClient,
    private val crypto: CredentialCrypto,
    @AppScope private val scope: CoroutineScope,
) {
    private val userAgent = "VantaView/${BuildConfig.VERSION_NAME}"

    private val _syncStates = MutableStateFlow<Map<Long, SyncState>>(emptyMap())
    val syncStates: StateFlow<Map<Long, SyncState>> = _syncStates

    fun observe(): Flow<List<ProviderEntity>> = db.providers().observeAll()

    suspend fun count(): Int = db.providers().count()

    suspend fun addM3u(name: String, url: String): Long {
        val clean = M3uParser.rawGithub(url)
        require(clean.startsWith("http://", true) || clean.startsWith("https://", true)) {
            "The playlist link must start with http:// or https://"
        }
        val id = db.providers().insert(
            ProviderEntity(name = name.ifBlank { "My playlist" }, kind = "M3U", baseUrl = clean)
        )
        sync(id)
        return id
    }

    suspend fun addXtream(name: String, server: String, username: String, password: String): Long {
        require(server.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
            "Server, username and password are all needed"
        }
        val id = db.providers().insert(
            ProviderEntity(
                name = name.ifBlank { "My playlist" },
                kind = "XTREAM",
                baseUrl = SmartAdd.normalizeServer(server),
                username = username.trim(),
                passwordEnc = crypto.encrypt(password.trim()),
            )
        )
        sync(id)
        return id
    }

    suspend fun delete(id: Long) {
        db.content().deleteChannels(id)
        db.content().deleteCategories(id)
        db.content().deleteFavourites(id)
        db.providers().delete(id)
        _syncStates.update { it - id }
    }

    suspend fun setFavourite(channel: ChannelEntity, on: Boolean) {
        if (on) db.content().addFavourite(FavouriteEntity(channel.providerId, channel.remoteId))
        else db.content().removeFavourite(channel.providerId, channel.remoteId)
    }

    fun sync(id: Long) {
        if (_syncStates.value[id] is SyncState.Running) return
        setState(id, SyncState.Running("Connecting"))
        scope.launch(Dispatchers.IO) {
            val provider = db.providers().get(id) ?: return@launch
            try {
                val done = if (provider.kind == "XTREAM") syncXtream(provider) else syncM3u(provider)
                db.providers().get(id)?.let {
                    db.providers().update(
                        it.copy(lastSyncAt = System.currentTimeMillis(), channelCount = done.channels, lastError = null)
                    )
                }
                setState(id, done)
            } catch (e: Exception) {
                val message = friendly(e)
                db.providers().get(id)?.let { db.providers().update(it.copy(lastError = message)) }
                setState(id, SyncState.Failed(message))
            }
        }
    }

    private fun setState(id: Long, state: SyncState) = _syncStates.update { it + (id to state) }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "Could not find that server. Check the address."
        is java.net.SocketTimeoutException -> "The server took too long to answer."
        is java.net.ConnectException -> "Could not connect to the server."
        is javax.net.ssl.SSLException -> "Secure connection failed. Try http:// instead of https://."
        is org.json.JSONException -> "The server sent an answer the app could not read."
        else -> e.message ?: "Something went wrong"
    }

    private suspend fun syncM3u(p: ProviderEntity): SyncState.Done {
        setState(p.id, SyncState.Running("Downloading playlist"))
        val request = Request.Builder().url(p.baseUrl).header("User-Agent", userAgent).build()
        val result = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("The server answered with error ${response.code}")
            val body = response.body ?: throw IllegalStateException("The playlist is empty")
            body.charStream().buffered().useLines { M3uParser.parse(it) }
        }
        if (result.entries.isEmpty()) {
            throw IllegalStateException(
                if (result.skippedVod > 0) "This playlist only has movies or series, which this test build does not show yet"
                else "No channels were found in this playlist"
            )
        }
        setState(p.id, SyncState.Running("Saving ${result.entries.size} channels"))
        val groups = LinkedHashMap<String, Int>()
        result.entries.forEach { e -> e.group?.let { g -> groups.getOrPut(g) { groups.size } } }
        val categories = groups.map { (name, pos) -> CategoryEntity(providerId = p.id, remoteId = name, name = name, position = pos) }
        val channels = result.entries.mapIndexed { i, e ->
            ChannelEntity(
                providerId = p.id,
                remoteId = sha1(e.url),
                name = e.name,
                sortKey = e.name.lowercase(),
                number = e.number ?: (i + 1),
                logo = e.logo,
                categoryRemoteId = e.group,
                streamUrl = e.url,
                epgChannelId = e.tvgId,
                headersJson = if (e.headers.isEmpty()) null else JSONObject(e.headers as Map<*, *>).toString(),
            )
        }
        db.content().replaceLive(p.id, categories, channels)
        if (result.epgUrl != null) db.providers().get(p.id)?.let { db.providers().update(it.copy(epgUrl = result.epgUrl)) }
        return SyncState.Done(channels.size, result.skippedVod)
    }

    private fun xtreamApi(p: ProviderEntity, action: String? = null): String {
        val user = URLEncoder.encode(p.username, "UTF-8")
        val pass = URLEncoder.encode(crypto.decrypt(p.passwordEnc), "UTF-8")
        return "${p.baseUrl}/player_api.php?username=$user&password=$pass" + (action?.let { "&action=$it" } ?: "")
    }

    /** Fetches a page as text. A null userAgent sends the HTTP library's own default one. */
    private fun text(url: String, ua: String? = userAgent): String {
        val builder = Request.Builder().url(url)
        if (ua != null) builder.header("User-Agent", ua)
        return http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("The server answered with error ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    /**
     * Xtream servers answer list calls in different shapes: usually an array, sometimes an object
     * keyed by number. Both are read here; anything else gives an empty list.
     */
    private fun jsonItems(body: String): List<JSONObject> {
        val t = body.trim().removePrefix("\uFEFF").trim()
        return when {
            t.startsWith("[") -> {
                val a = JSONArray(t)
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            }
            t.startsWith("{") -> {
                val o = JSONObject(t)
                o.keys().asSequence().mapNotNull { o.optJSONObject(it) }.toList()
            }
            else -> emptyList()
        }
    }

    private fun streamId(o: JSONObject): String = o.optString("stream_id").ifBlank { o.optString("id") }

    private suspend fun syncXtream(p: ProviderEntity): SyncState.Done {
        setState(p.id, SyncState.Running("Signing in"))
        val login = JSONObject(text(xtreamApi(p)))
        val info = login.optJSONObject("user_info")
        val auth = info?.optString("auth")
        if (auth == null || auth == "0") throw IllegalStateException("The provider refused the username or password")
        val status = info?.optString("status").orEmpty().ifBlank { "unknown" }

        var lastReply = ""

        // Tries the whole list first, then one category at a time (some servers only answer that way).
        fun load(ua: String?): Pair<List<CategoryEntity>, List<JSONObject>> {
            setState(p.id, SyncState.Running("Loading categories"))
            val cats = runCatching { jsonItems(text(xtreamApi(p, "get_live_categories"), ua)) }.getOrDefault(emptyList())
            val categories = cats.mapIndexedNotNull { i, o ->
                val id = o.optString("category_id")
                if (id.isBlank()) null
                else CategoryEntity(
                    providerId = p.id,
                    remoteId = id,
                    name = o.optString("category_name").ifBlank { "Category ${i + 1}" },
                    position = i,
                )
            }

            setState(p.id, SyncState.Running("Loading channels"))
            val all = runCatching {
                val body = text(xtreamApi(p, "get_live_streams"), ua)
                lastReply = body.take(120)
                jsonItems(body)
            }.getOrElse { lastReply = it.message.orEmpty(); emptyList() }
                .filter { streamId(it).isNotBlank() }
            if (all.isNotEmpty() || categories.isEmpty()) return categories to all

            val perCategory = ArrayList<JSONObject>()
            categories.forEachIndexed { i, c ->
                setState(p.id, SyncState.Running("Loading channels, category ${i + 1} of ${categories.size}"))
                val url = xtreamApi(p, "get_live_streams") + "&category_id=" + URLEncoder.encode(c.remoteId, "UTF-8")
                runCatching { jsonItems(text(url, ua)) }.getOrDefault(emptyList())
                    .filter { streamId(it).isNotBlank() }
                    .forEach { o ->
                        if (o.optString("category_id").isBlank()) o.put("category_id", c.remoteId)
                        perCategory += o
                    }
            }
            return categories to perCategory
        }

        var (categories, streams) = load(userAgent)
        if (streams.isEmpty()) {
            // Some servers hide their lists from apps they don't recognise; try once more without our app name.
            val second = load(null)
            if (second.second.isNotEmpty()) { categories = second.first; streams = second.second }
            else if (categories.isEmpty()) categories = second.first
        }

        if (streams.isEmpty()) {
            val secret = crypto.decrypt(p.passwordEnc)
            val shown = lastReply.replace(p.username, "•••").let { if (secret.isNotEmpty()) it.replace(secret, "•••") else it }
                .replace("\n", " ").trim()
            throw IllegalStateException(
                "Signed in (account status: $status), but the server sent no live channels. " +
                    "Live categories found: ${categories.size}. " +
                    "Server reply began: \"${shown.ifBlank { "(empty)" }}\""
            )
        }

        val seen = HashSet<String>()
        val channels = streams.mapIndexedNotNull { i, o ->
            val id = streamId(o)
            if (!seen.add(id)) return@mapIndexedNotNull null
            val name = o.optString("name").trim().ifBlank { "Channel ${i + 1}" }
            ChannelEntity(
                providerId = p.id,
                remoteId = id,
                name = name,
                sortKey = name.lowercase(),
                number = o.optInt("num", i + 1),
                logo = o.optString("stream_icon").ifBlank { null },
                categoryRemoteId = o.optString("category_id").ifBlank { null },
                streamUrl = "xtream:$id",
                epgChannelId = o.optString("epg_channel_id").ifBlank { null },
            )
        }
        setState(p.id, SyncState.Running("Saving ${channels.size} channels"))
        db.content().replaceLive(p.id, categories, channels)
        return SyncState.Done(channels.size, 0)
    }

    /** Turns a stored channel into a real address (and a second one to try) just before playback. */
    suspend fun resolveStream(channel: ChannelEntity): PlayTarget = withContext(Dispatchers.IO) {
        val headers = channel.headersJson?.let { json ->
            runCatching {
                val o = JSONObject(json)
                o.keys().asSequence().associateWith { o.optString(it) }
            }.getOrNull()
        } ?: emptyMap()
        if (channel.streamUrl.startsWith("xtream:")) {
            val p = db.providers().get(channel.providerId) ?: throw IllegalStateException("Playlist not found")
            val id = channel.streamUrl.removePrefix("xtream:")
            val base = "${p.baseUrl}/live/${p.username}/${crypto.decrypt(p.passwordEnc)}/$id"
            PlayTarget("$base.ts", "$base.m3u8", headers)
        } else {
            PlayTarget(channel.streamUrl, null, headers)
        }
    }

    private fun sha1(s: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
