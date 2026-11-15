// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.trakt

import com.vantaview.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class TraktException(message: String, val code: Int = 0) : Exception(message)

/** The code to type at trakt.tv/activate, and how to poll for the result. */
data class TraktCode(val deviceCode: String, val userCode: String, val url: String, val intervalSec: Int, val expiresAt: Long)

/** Tokens for one linked account. */
data class TraktTokens(val access: String, val refresh: String, val expiresAt: Long)

/** A film or series as Trakt names it: the IMDb id is what add-ons use. */
data class TraktTitle(val type: String, val imdb: String, val tmdb: Int?, val name: String, val year: Int?)

/** An episode as Trakt names it. */
data class TraktEpisode(val showImdb: String, val season: Int, val number: Int) {
    /** The add-on video id for this episode ("tt123:1:2"). */
    val videoId get() = "$showImdb:$season:$number"
}

/** Somewhere a profile stopped part-way: a film, or an episode of a series. */
data class TraktPlayback(
    val title: TraktTitle,
    val episode: TraktEpisode?,
    val episodeTitle: String?,
    val progress: Float,
    val runtimeMin: Int,
    val pausedAt: Long,
)

/** One of the user's own lists. */
data class TraktList(val id: String, val name: String)

/**
 * The parts of the Trakt API VantaView uses. All calls are JSON over HTTPS with the app's client id;
 * account calls add the profile's access token.
 */
class TraktApi(private val http: OkHttpClient) {
    val clientId: String = BuildConfig.TRAKT_CLIENT_ID
    private val clientSecret: String = BuildConfig.TRAKT_CLIENT_SECRET
    val configured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    private val json = "application/json".toMediaType()

    data class Reply(val code: Int, val body: String)

    suspend fun call(path: String, method: String = "GET", token: String? = null, body: Any? = null): Reply = withContext(Dispatchers.IO) {
        val b = Request.Builder().url("https://api.trakt.tv$path")
            .header("Content-Type", "application/json")
            .header("trakt-api-version", "2")
            .header("trakt-api-key", clientId)
            .header("User-Agent", "VantaView/${BuildConfig.VERSION_NAME}")
        token?.let { b.header("Authorization", "Bearer $it") }
        b.method(method, body?.toString()?.toRequestBody(json) ?: if (method == "POST") "{}".toRequestBody(json) else null)
        try {
            http.newCall(b.build()).execute().use { r -> Reply(r.code, r.body?.string().orEmpty()) }
        } catch (e: Exception) {
            throw TraktException("Couldn't reach Trakt (${e.message ?: "network error"})")
        }
    }

    private fun Reply.ok(): Reply {
        if (code in 200..299) return this
        throw TraktException(
            when (code) {
                401, 403 -> "Trakt says this account isn't signed in any more. Link it again in Settings › Trakt."
                420 -> "Your Trakt account has reached its limit (lists or items). Trakt VIP raises it."
                429 -> "Trakt is busy. Try again in a minute."
                in 500..599 -> "Trakt isn't answering right now (error $code)."
                else -> "Trakt said no (error $code)."
            },
            code,
        )
    }

    // ---- Sign-in (device code) ----

    suspend fun startCode(): TraktCode {
        val o = JSONObject(call("/oauth/device/code", "POST", body = JSONObject().put("client_id", clientId)).ok().body)
        return TraktCode(
            o.getString("device_code"), o.getString("user_code"), o.optString("verification_url", "https://trakt.tv/activate"),
            o.optInt("interval", 5), System.currentTimeMillis() + o.optInt("expires_in", 600) * 1000L,
        )
    }

    /** The tokens once the code has been entered, null while still waiting. */
    suspend fun pollCode(c: TraktCode): TraktTokens? {
        val r = call(
            "/oauth/device/token", "POST",
            body = JSONObject().put("code", c.deviceCode).put("client_id", clientId).put("client_secret", clientSecret),
        )
        return when (r.code) {
            200 -> tokens(JSONObject(r.body))
            400, 429 -> null // not entered yet, or polling too fast
            404 -> throw TraktException("That code wasn't recognised. Start again.")
            409 -> throw TraktException("That code was already used. Start again.")
            410 -> throw TraktException("The code ran out of time. Start again.")
            418 -> throw TraktException("The request was turned down on trakt.tv.")
            else -> throw TraktException("Trakt sign-in failed (error ${r.code}).")
        }
    }

    suspend fun refresh(t: TraktTokens): TraktTokens {
        val body = JSONObject().put("refresh_token", t.refresh).put("client_id", clientId).put("client_secret", clientSecret)
            .put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob").put("grant_type", "refresh_token")
        return tokens(JSONObject(call("/oauth/token", "POST", body = body).ok().body))
    }

    suspend fun revoke(t: TraktTokens) {
        runCatching { call("/oauth/revoke", "POST", body = JSONObject().put("token", t.access).put("client_id", clientId).put("client_secret", clientSecret)) }
    }

    private fun tokens(o: JSONObject) = TraktTokens(
        o.getString("access_token"), o.getString("refresh_token"),
        (o.optLong("created_at", System.currentTimeMillis() / 1000) + o.optLong("expires_in", 7_776_000)) * 1000L,
    )

    suspend fun username(token: String): String =
        JSONObject(call("/users/settings", token = token).ok().body).optJSONObject("user")?.optString("username").orEmpty()

    // ---- Watchlist ----

    suspend fun watchlist(token: String): List<TraktTitle> =
        listOf("movies", "shows").flatMap { kind -> array(call("/sync/watchlist/$kind", token = token).ok().body).mapNotNull(::titleOf) }

    suspend fun addToWatchlist(token: String, titles: List<TraktTitle>) = postTitles("/sync/watchlist", token, titles)
    suspend fun removeFromWatchlist(token: String, titles: List<TraktTitle>) = postTitles("/sync/watchlist/remove", token, titles)

    // ---- Watched ----

    suspend fun watchedMovies(token: String): List<TraktTitle> =
        array(call("/sync/watched/movies", token = token).ok().body).mapNotNull(::titleOf)

    suspend fun watchedEpisodes(token: String): List<TraktEpisode> =
        array(call("/sync/watched/shows", token = token).ok().body).flatMap { o ->
            val show = o.optJSONObject("show") ?: return@flatMap emptyList()
            val imdb = show.optJSONObject("ids")?.optString("imdb").orEmpty()
            if (!imdb.startsWith("tt")) return@flatMap emptyList()
            objects(o.optJSONArray("seasons")).flatMap { s ->
                val season = s.optInt("number")
                objects(s.optJSONArray("episodes")).map { e -> TraktEpisode(imdb, season, e.optInt("number")) }
            }
        }

    suspend fun addHistory(token: String, movies: List<TraktTitle>, episodes: List<TraktEpisode>) =
        postHistory("/sync/history", token, movies, episodes)

    suspend fun removeHistory(token: String, movies: List<TraktTitle>, episodes: List<TraktEpisode>) =
        postHistory("/sync/history/remove", token, movies, episodes)

    // ---- Continue watching ----

    suspend fun playback(token: String): List<TraktPlayback> =
        array(call("/sync/playback?extended=full", token = token).ok().body).mapNotNull { o ->
            val progress = o.optDouble("progress", 0.0).toFloat()
            val paused = runCatching { java.time.Instant.parse(o.optString("paused_at")).toEpochMilli() }.getOrDefault(0L)
            when (o.optString("type")) {
                "movie" -> {
                    val m = o.optJSONObject("movie") ?: return@mapNotNull null
                    val t = titleOf(JSONObject().put("movie", m)) ?: return@mapNotNull null
                    TraktPlayback(t, null, null, progress, m.optInt("runtime"), paused)
                }
                "episode" -> {
                    val s = o.optJSONObject("show") ?: return@mapNotNull null
                    val e = o.optJSONObject("episode") ?: return@mapNotNull null
                    val t = titleOf(JSONObject().put("show", s)) ?: return@mapNotNull null
                    TraktPlayback(
                        t, TraktEpisode(t.imdb, e.optInt("season"), e.optInt("number")), e.optString("title").ifBlank { null },
                        progress, e.optInt("runtime").takeIf { it > 0 } ?: s.optInt("runtime"), paused,
                    )
                }
                else -> null
            }
        }

    /** Tells Trakt what's playing: action is "start", "pause" or "stop"; progress in percent. */
    suspend fun scrobble(token: String, action: String, title: TraktTitle, episode: TraktEpisode?, progress: Float) {
        val body = JSONObject().put("progress", progress.coerceIn(0f, 100f).toDouble())
        if (episode == null) body.put("movie", JSONObject().put("ids", JSONObject().put("imdb", title.imdb)))
        else {
            body.put("show", JSONObject().put("ids", JSONObject().put("imdb", episode.showImdb)))
            body.put("episode", JSONObject().put("season", episode.season).put("number", episode.number))
        }
        val r = call("/scrobble/$action", "POST", token, body)
        if (r.code != 409) r.ok() // 409: already scrobbled a moment ago
    }

    // ---- Recommendations and lists ----

    suspend fun recommendations(token: String, type: String): List<TraktTitle> =
        array(call("/recommendations/${if (type == "series") "shows" else "movies"}?limit=40&ignore_collected=false", token = token).ok().body)
            .mapNotNull { o -> titleOf(JSONObject().put(if (type == "series") "show" else "movie", o)) }

    suspend fun lists(token: String): List<TraktList> =
        array(call("/users/me/lists", token = token).ok().body).mapNotNull { o ->
            val id = o.optJSONObject("ids")?.optString("trakt").orEmpty().ifBlank { return@mapNotNull null }
            TraktList(id, o.optString("name"))
        }

    suspend fun listItems(token: String, listId: String, type: String): List<TraktTitle> =
        array(call("/users/me/lists/$listId/items/${if (type == "series") "show" else "movie"}", token = token).ok().body).mapNotNull(::titleOf)

    // ---- Helpers ----

    private suspend fun postTitles(path: String, token: String, titles: List<TraktTitle>) {
        if (titles.isEmpty()) return
        val body = JSONObject()
        titles.filter { it.type == "movie" }.takeIf { it.isNotEmpty() }?.let { body.put("movies", JSONArray(it.map(::ref))) }
        titles.filter { it.type == "series" }.takeIf { it.isNotEmpty() }?.let { body.put("shows", JSONArray(it.map(::ref))) }
        call(path, "POST", token, body).ok()
    }

    private suspend fun postHistory(path: String, token: String, movies: List<TraktTitle>, episodes: List<TraktEpisode>) {
        if (movies.isEmpty() && episodes.isEmpty()) return
        val body = JSONObject()
        if (movies.isNotEmpty()) body.put("movies", JSONArray(movies.map(::ref)))
        if (episodes.isNotEmpty()) body.put("shows", JSONArray(episodes.groupBy { it.showImdb }.map { (imdb, eps) ->
            JSONObject().put("ids", JSONObject().put("imdb", imdb)).put("seasons", JSONArray(eps.groupBy { it.season }.map { (s, list) ->
                JSONObject().put("number", s).put("episodes", JSONArray(list.map { JSONObject().put("number", it.number) }))
            }))
        }))
        call(path, "POST", token, body).ok()
    }

    private fun ref(t: TraktTitle) = JSONObject().put("ids", JSONObject().put("imdb", t.imdb))

    companion object {
        fun array(body: String): List<JSONObject> = runCatching { objects(JSONArray(body)) }.getOrDefault(emptyList())
        fun objects(a: JSONArray?): List<JSONObject> = a?.let { (0 until it.length()).mapNotNull(it::optJSONObject) }.orEmpty()

        /** A watchlist, history or list item ({"movie": …} or {"show": …}) as a title, if it has an IMDb id. */
        fun titleOf(o: JSONObject): TraktTitle? {
            val (type, m) = o.optJSONObject("movie")?.let { "movie" to it } ?: o.optJSONObject("show")?.let { "series" to it } ?: return null
            val ids = m.optJSONObject("ids") ?: return null
            val imdb = ids.optString("imdb").takeIf { it.startsWith("tt") } ?: return null
            return TraktTitle(type, imdb, ids.optInt("tmdb").takeIf { it > 0 }, m.optString("title"), m.optInt("year").takeIf { it > 0 })
        }
    }
}
