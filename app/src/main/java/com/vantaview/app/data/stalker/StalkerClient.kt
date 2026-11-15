// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.stalker

import android.util.JsonReader
import android.util.JsonToken
import com.vantaview.app.data.db.ProviderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A programme from a portal's TV guide. [id] is used to ask for its catch-up recording. */
data class StalkerProgramme(val channelId: String, val id: String, val start: Long, val stop: Long, val title: String, val description: String?, val category: String?)

/**
 * Talks to a Stalker (Ministra / MAG) middleware portal with the portal address and MAC address the
 * user entered for their own subscription. Answers are wrapped in {"js": …}.
 */
@Singleton
class StalkerClient @Inject constructor(http: OkHttpClient, private val crypto: com.vantaview.app.data.security.CredentialCrypto) {
    private val http = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

    private data class Session(val api: String, val token: String, val at: Long)
    private val sessions = ConcurrentHashMap<Long, Session>()
    // Portals don't like many requests at once (Movies and Series load a row per category).
    private val gates = ConcurrentHashMap<Long, Semaphore>()

    /** Headers the player sends with portal streams. */
    val playHeaders: Map<String, String> = mapOf("User-Agent" to MAG_UA)

    // ---- Address and MAC ----

    /** The API addresses to try for a portal address such as http://host:8080/c/ or http://host/stalker_portal/c/. */
    fun apiCandidates(portal: String): List<String> {
        val base = portal.trim().substringBefore('?').substringBefore('#').trimEnd('/')
            .let { if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "http://$it" }
        if (base.endsWith(".php", true)) return listOf(base)
        val root = base.removeSuffix("/c").trimEnd('/')
        return if (root.endsWith("/stalker_portal", true)) listOf("$root/server/load.php", "$root/portal.php")
        else listOf("$root/portal.php", "$root/server/load.php", "$root/stalker_portal/server/load.php")
    }

    /** The MAC address is the login, so it's stored encrypted like a password. */
    private fun mac(p: ProviderEntity): String = crypto.decrypt(p.passwordEnc)

    private fun origin(p: ProviderEntity) = p.baseUrl.substringBefore("://") + "://" + p.baseUrl.substringAfter("://").substringBefore('/')

    /** Makes a picture address from the portal absolute. */
    fun absolute(p: ProviderEntity, url: String?): String? = when {
        url.isNullOrBlank() -> null
        url.startsWith("http", true) -> url
        url.startsWith("/") -> origin(p) + url
        else -> null
    }

    // ---- Requests ----

    private fun request(p: ProviderEntity, url: String, token: String?): Request {
        val mac = URLEncoder.encode(mac(p), "UTF-8")
        val tz = URLEncoder.encode(TimeZone.getDefault().id, "UTF-8")
        val b = Request.Builder().url(url)
            .header("User-Agent", MAG_UA)
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
            .header("Referer", p.baseUrl.trimEnd('/') + "/")
            .header("Cookie", "mac=$mac; stb_lang=en; timezone=$tz")
            .header("Accept", "*/*")
        if (token != null) b.header("Authorization", "Bearer $token")
        return b.build()
    }

    private fun fetch(p: ProviderEntity, url: String, token: String?): String =
        http.newCall(request(p, url, token)).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw AuthException()
            if (!r.isSuccessful) throw IllegalStateException("The portal answered with error ${r.code}")
            r.body?.string().orEmpty()
        }

    private class AuthException : Exception("The portal refused this MAC address")

    private fun js(body: String): Any? {
        val t = body.trim().removePrefix("﻿").trim()
        if (!t.startsWith("{")) {
            if (t.contains("Authorization failed", true)) throw AuthException()
            throw IllegalStateException("The portal sent an answer the app could not read")
        }
        return JSONObject(t).opt("js")
    }

    /** Signs in: handshake for a token, then get_profile so the portal knows this box. */
    private fun signIn(p: ProviderEntity): Session {
        var lastError: Exception? = null
        for (api in apiCandidates(p.baseUrl)) {
            try {
                val hs = js(fetch(p, "$api?type=stb&action=handshake&token=&JsHttpRequest=1-xml", null)) as? JSONObject
                    ?: throw IllegalStateException("No answer to the handshake")
                val token = hs.optString("token")
                if (token.isBlank()) throw IllegalStateException("The portal didn't give a session")
                val sn = md5(mac(p)).uppercase().take(13)
                val profileUrl = "$api?type=stb&action=get_profile&hd=1&ver=ImageDescription%3A%200.2.18-r23-250%3B&num_banks=2" +
                    "&sn=$sn&stb_type=MAG250&image_version=218&video_out=hdmi&auth_second_step=1&hw_version=1.7-BD-00" +
                    "&not_valid_token=0&JsHttpRequest=1-xml"
                val profile = js(fetch(p, profileUrl, token)) as? JSONObject
                if (profile != null && (profile.optString("blocked") == "1" || profile.optInt("status", 0) == 1 && profile.optString("id").isBlank())) {
                    throw IllegalStateException(
                        profile.optString("msg").ifBlank { "The portal doesn't recognise this MAC address. Check it matches the one your provider has." }
                    )
                }
                return Session(api, token, System.currentTimeMillis()).also { sessions[p.id] = it }
            } catch (e: AuthException) {
                throw IllegalStateException("The portal refused this MAC address. Check it matches the one your provider has.")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Couldn't reach the portal")
    }

    /** Runs a call with a session, signing in again once if the session has expired. */
    private suspend fun call(p: ProviderEntity, query: String): Any? = withContext(Dispatchers.IO) {
        gates.getOrPut(p.id) { Semaphore(3) }.withPermit {
            val s = sessions[p.id]?.takeIf { System.currentTimeMillis() - it.at < 3_600_000L } ?: signIn(p)
            try {
                js(fetch(p, "${s.api}?$query&JsHttpRequest=1-xml", s.token))
            } catch (e: AuthException) {
                val fresh = signIn(p)
                js(fetch(p, "${fresh.api}?$query&JsHttpRequest=1-xml", fresh.token))
            }
        }
    }

    /** Checks the portal address and MAC work. Throws with a readable message. */
    suspend fun check(p: ProviderEntity) = withContext(Dispatchers.IO) { sessions.remove(p.id); signIn(p); Unit }

    fun forget(id: Long) { sessions.remove(id) }

    // ---- Live TV ----

    data class Genre(val id: String, val title: String, val censored: Boolean)

    private fun genres(arr: JSONArray?): List<Genre> = items(arr).mapNotNull { o ->
        val id = o.optString("id")
        if (id.isBlank() || id == "*") null
        else Genre(id, o.optString("title").ifBlank { o.optString("name") }.ifBlank { "Category $id" }, o.optString("censored") == "1")
    }

    suspend fun liveGenres(p: ProviderEntity): List<Genre> = genres(call(p, "type=itv&action=get_genres") as? JSONArray)

    /** Every live channel. Uses the one-call list, or reads the list page by page if the portal won't. */
    suspend fun liveChannels(p: ProviderEntity, progress: (String) -> Unit): List<JSONObject> {
        val all = (call(p, "type=itv&action=get_all_channels") as? JSONObject)?.optJSONArray("data")
        items(all).takeIf { it.isNotEmpty() }?.let { return it }
        val out = ArrayList<JSONObject>()
        var page = 1
        var pages = 1
        do {
            progress("Loading channels, page $page of $pages")
            val o = call(p, "type=itv&action=get_ordered_list&genre=*&fav=0&sortby=number&p=$page") as? JSONObject ?: break
            val data = items(o.optJSONArray("data"))
            if (data.isEmpty()) break
            out += data
            val per = o.optInt("max_page_items", data.size).coerceAtLeast(1)
            pages = ((o.optInt("total_items", out.size) + per - 1) / per).coerceAtMost(500)
            page++
        } while (page <= pages)
        return out
    }

    /** A playable address for a channel, film or episode. [series] is the episode number. */
    suspend fun createLink(p: ProviderEntity, type: String, cmd: String, series: Int? = null): String {
        val q = "type=$type&action=create_link&cmd=${enc(cmd)}" + (series?.let { "&series=$it" } ?: "&series=") +
            "&forced_storage=undefined&disable_ad=0&download=0"
        val got = (call(p, q) as? JSONObject)?.optString("cmd").orEmpty()
        return urlIn(got) ?: urlIn(cmd) ?: throw IllegalStateException("The portal didn't give a link to play")
    }

    /** Catch-up: a recording of a past programme from the portal's TV archive. */
    suspend fun archiveLink(p: ProviderEntity, programmeId: String): String =
        createLink(p, "tv_archive", "auto /media/$programmeId.mpg")

    /** "ffmpeg http://…" becomes "http://…". Addresses on localhost are placeholders that need create_link. */
    fun urlIn(cmd: String): String? = cmd.split(' ').firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
        ?.takeIf { !it.contains("://localhost", true) && !it.contains("://127.0.0.1") }

    /**
     * The portal's TV guide for the next [hours], read as it downloads (it can be large). [wanted]
     * picks the channels to keep; [sink] receives programmes in batches.
     */
    suspend fun guide(p: ProviderEntity, hours: Int, wanted: Set<String>, from: Long, to: Long, sink: suspend (List<StalkerProgramme>) -> Unit) {
        val s = withContext(Dispatchers.IO) { sessions[p.id]?.takeIf { System.currentTimeMillis() - it.at < 3_600_000L } ?: signIn(p) }
        val url = "${s.api}?type=itv&action=get_epg_info&period=$hours&JsHttpRequest=1-xml"
        val batch = ArrayList<StalkerProgramme>(2000)
        withContext(Dispatchers.IO) {
            http.newCall(request(p, url, s.token)).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("The portal's TV guide answered with error ${r.code}")
                val reader = JsonReader(InputStreamReader(r.body!!.byteStream(), Charsets.UTF_8))
                reader.isLenient = true
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() != "js" || reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue }
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() != "data" || reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue }
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val ch = reader.nextName()
                            if (ch !in wanted || reader.peek() != JsonToken.BEGIN_ARRAY) { reader.skipValue(); continue }
                            reader.beginArray()
                            while (reader.hasNext()) {
                                readProgramme(reader, ch)?.takeIf { it.stop > from && it.start < to }?.let { batch += it }
                                if (batch.size >= 2000) { sink(batch.toList()); batch.clear() }
                            }
                            reader.endArray()
                        }
                        reader.endObject()
                    }
                    reader.endObject()
                }
            }
        }
        if (batch.isNotEmpty()) sink(batch)
    }

    private fun readProgramme(reader: JsonReader, channel: String): StalkerProgramme? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return null }
        var id: String? = null; var name: String? = null; var descr: String? = null; var cat: String? = null
        var start = 0L; var stop = 0L
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (reader.peek() == JsonToken.NULL) { reader.skipValue(); continue }
            when (key) {
                "id" -> id = reader.nextString()
                "name" -> name = reader.nextString()
                "descr" -> descr = reader.nextString()
                "category" -> cat = reader.nextString()
                "start_timestamp" -> start = reader.nextString().toLongOrNull()?.times(1000) ?: 0
                "stop_timestamp" -> stop = reader.nextString().toLongOrNull()?.times(1000) ?: 0
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (id.isNullOrBlank() || name.isNullOrBlank() || start == 0L || stop <= start) return null
        return StalkerProgramme(channel, id, start, stop, name.trim(), descr?.trim()?.take(400)?.ifBlank { null }, cat?.trim()?.ifBlank { null })
    }

    // ---- Films and series ----

    /** [kind] is "vod" (films) or "series". */
    suspend fun vodGenres(p: ProviderEntity, kind: String): List<Genre> =
        genres(call(p, "type=$kind&action=get_categories") as? JSONArray)

    /** One page of a category (or a search when [search] is set). */
    suspend fun vodPage(p: ProviderEntity, kind: String, category: String, page: Int, search: String? = null): List<JSONObject> {
        val q = "type=$kind&action=get_ordered_list&category=${enc(category)}&genre=${enc(category)}&sortby=added&fav=0&p=$page" +
            (search?.let { "&search=${enc(it)}" } ?: "")
        return items((call(p, q) as? JSONObject)?.optJSONArray("data"))
    }

    /** Seasons of a series (newer portals): each has a cmd and a list of episode numbers, or its own episode list. */
    suspend fun seasons(p: ProviderEntity, kind: String, movieId: String): List<JSONObject> =
        items((call(p, "type=$kind&action=get_ordered_list&movie_id=${enc(movieId)}&season_id=0&episode_id=0&p=1") as? JSONObject)?.optJSONArray("data"))

    suspend fun episodes(p: ProviderEntity, kind: String, movieId: String, seasonId: String): List<JSONObject> =
        items((call(p, "type=$kind&action=get_ordered_list&movie_id=${enc(movieId)}&season_id=${enc(seasonId)}&episode_id=0&p=1") as? JSONObject)?.optJSONArray("data"))

    // ---- Helpers ----

    private fun items(arr: JSONArray?): List<JSONObject> = arr?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAG_UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

        private val macRegex = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

        /** "00-1a-79-…" or "001A79…" becomes "00:1A:79:…"; null if it isn't a MAC address. */
        fun normalizeMac(input: String): String? {
            val hex = input.trim().uppercase().filter { it.isLetterOrDigit() }
            if (hex.length != 12 || hex.any { it !in "0123456789ABCDEF" }) return null
            return hex.chunked(2).joinToString(":").takeIf { macRegex.matches(it) }
        }
    }
}
