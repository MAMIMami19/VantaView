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

    private fun text(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("The server answered with error ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    private suspend fun syncXtream(p: ProviderEntity): SyncState.Done {
        setState(p.id, SyncState.Running("Signing in"))
        val login = JSONObject(text(xtreamApi(p)))
        val auth = login.optJSONObject("user_info")?.optString("auth")
        if (auth == null || auth == "0") throw IllegalStateException("The provider refused the username or password")

        setState(p.id, SyncState.Running("Loading categories"))
        val cats = runCatching { JSONArray(text(xtreamApi(p, "get_live_categories"))) }.getOrDefault(JSONArray())
        val categories = (0 until cats.length()).mapNotNull { cats.optJSONObject(it) }.mapIndexed { i, o ->
            CategoryEntity(
                providerId = p.id,
                remoteId = o.optString("category_id"),
                name = o.optString("category_name").ifBlank { "Category ${i + 1}" },
                position = i,
            )
        }

        setState(p.id, SyncState.Running("Loading channels"))
        val streams = JSONArray(text(xtreamApi(p, "get_live_streams")))
        val channels = (0 until streams.length()).mapNotNull { streams.optJSONObject(it) }.mapIndexedNotNull { i, o ->
            val streamId = o.optString("stream_id")
            if (streamId.isBlank()) return@mapIndexedNotNull null
            val name = o.optString("name").trim().ifBlank { "Channel ${i + 1}" }
            ChannelEntity(
                providerId = p.id,
                remoteId = streamId,
                name = name,
                sortKey = name.lowercase(),
                number = o.optInt("num", i + 1),
                logo = o.optString("stream_icon").ifBlank { null },
                categoryRemoteId = o.optString("category_id").ifBlank { null },
                streamUrl = "xtream:$streamId",
                epgChannelId = o.optString("epg_channel_id").ifBlank { null },
            )
        }
        if (channels.isEmpty()) throw IllegalStateException("This account has no live channels")
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
