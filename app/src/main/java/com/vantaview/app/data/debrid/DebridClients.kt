// SPDX-License-Identifier: GPL-3.0-or-later
// Written from each service's public API documentation:
// Real-Debrid https://api.real-debrid.com/  AllDebrid https://docs.alldebrid.com/
// TorBox https://api-docs.torbox.app/  Premiumize https://www.premiumize.me/api
package com.vantaview.app.data.debrid

import com.vantaview.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class DebridException(message: String) : Exception(message)

/** Small JSON-over-HTTP helper shared by the four services. */
class DebridHttp(private val http: OkHttpClient) {
    private val ua = "VantaView/${BuildConfig.VERSION_NAME}"

    data class Reply(val code: Int, val body: String) {
        fun json(): JSONObject = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
    }

    suspend fun call(
        url: String,
        method: String = "GET",
        bearer: String? = null,
        form: List<Pair<String, String>>? = null,
        multipart: List<Pair<String, String>>? = null,
    ): Reply = withContext(Dispatchers.IO) {
        val body: RequestBody? = when {
            form != null -> FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
            multipart != null -> MultipartBody.Builder().setType(MultipartBody.FORM)
                .apply { multipart.forEach { (k, v) -> addFormDataPart(k, v) } }.build()
            method == "POST" -> FormBody.Builder().build()
            else -> null
        }
        val b = Request.Builder().url(url).header("User-Agent", ua)
        bearer?.let { b.header("Authorization", "Bearer $it") }
        b.method(method, body)
        try {
            http.newCall(b.build()).execute().use { r -> Reply(r.code, r.body?.string().orEmpty()) }
        } catch (e: Exception) {
            throw DebridException("Couldn't reach the service (${e.message ?: "network error"})")
        }
    }
}

/** What a service says about the connected account. */
data class AccountInfo(val label: String, val premiumUntil: Long, val premium: Boolean)

/** Shown while waiting for the user to enter a code on the service's website. */
data class CodeSignIn(val userCode: String, val url: String, val intervalSec: Int, val expiresAt: Long, val state: Map<String, String>)

interface DebridClient {
    val service: DebridService
    suspend fun accountInfo(a: DebridAccount): AccountInfo
    /** Cached state per hash, or null when the service has no way to check without adding the torrent. */
    suspend fun cached(a: DebridAccount, hashes: List<String>): Map<String, Boolean>?
    /** Tries to get a link. With [start], an uncached torrent is sent to the service and reported as Pending. */
    suspend fun resolve(a: DebridAccount, req: TorrentRequest, start: Boolean): Resolve
}

private fun JSONArray?.objects(): List<JSONObject> = this?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

// ---------------------------------------------------------------- Real-Debrid

class RealDebridClient(private val h: DebridHttp, private val save: (DebridAccount) -> Unit) : DebridClient {
    override val service = DebridService.REAL_DEBRID
    private val api = "https://api.real-debrid.com/rest/1.0"
    private val oauth = "https://api.real-debrid.com/oauth/v2"
    /** Real-Debrid's published client id for open-source apps. */
    private val openSourceClientId = "X245A4XAIBGVM"
    private val grant = "http://oauth.net/grant_type/device/1.0"
    private val torrentIds = ConcurrentHashMap<String, String>()

    suspend fun startCode(): CodeSignIn {
        val o = h.call("$oauth/device/code?client_id=$openSourceClientId&new_credentials=yes").json()
        val device = o.optString("device_code").ifBlank { throw DebridException("Real-Debrid didn't give a sign-in code") }
        return CodeSignIn(
            userCode = o.optString("user_code"),
            url = o.optString("verification_url").ifBlank { "https://real-debrid.com/device" },
            intervalSec = o.optInt("interval", 5).coerceAtLeast(2),
            expiresAt = System.currentTimeMillis() + o.optLong("expires_in", 1800) * 1000,
            state = mapOf("device" to device),
        )
    }

    /** Returns the account once the user has approved the code, or null while still waiting. */
    suspend fun pollCode(s: CodeSignIn): DebridAccount? {
        val device = s.state.getValue("device")
        val cred = h.call("$oauth/device/credentials?client_id=$openSourceClientId&code=$device")
        if (cred.code != 200) return null
        val c = cred.json()
        val id = c.optString("client_id").ifBlank { return null }
        val secret = c.optString("client_secret")
        val tok = h.call(
            "$oauth/token", "POST",
            form = listOf("client_id" to id, "client_secret" to secret, "code" to device, "grant_type" to grant),
        ).json()
        val access = tok.optString("access_token").ifBlank { throw DebridException("Real-Debrid sign-in failed") }
        val acc = DebridAccount(
            service, "", access, tok.optString("refresh_token"), id, secret,
            System.currentTimeMillis() + tok.optLong("expires_in", 3600) * 1000,
        )
        val info = accountInfo(acc)
        return acc.copy(label = info.label, premiumUntil = info.premiumUntil)
    }

    /** A token from the sign-in code expires; it is refreshed when close to expiry or rejected. */
    private suspend fun fresh(a: DebridAccount, force: Boolean = false): DebridAccount {
        if (a.refreshToken == null || a.clientId == null || a.clientSecret == null) return a
        if (!force && a.expiresAt - System.currentTimeMillis() > 60_000) return a
        val tok = h.call(
            "$oauth/token", "POST",
            form = listOf("client_id" to a.clientId, "client_secret" to a.clientSecret, "code" to a.refreshToken, "grant_type" to grant),
        ).json()
        val access = tok.optString("access_token").ifBlank { throw DebridException("Real-Debrid sign-in has expired. Connect it again.") }
        return a.copy(
            token = access,
            refreshToken = tok.optString("refresh_token").ifBlank { a.refreshToken },
            expiresAt = System.currentTimeMillis() + tok.optLong("expires_in", 3600) * 1000,
        ).also(save)
    }

    private suspend fun rd(a: DebridAccount, path: String, method: String = "GET", form: List<Pair<String, String>>? = null): DebridHttp.Reply {
        var acc = fresh(a)
        var r = h.call("$api$path", method, acc.token, form)
        if (r.code == 401 && acc.refreshToken != null) {
            acc = fresh(acc, force = true)
            r = h.call("$api$path", method, acc.token, form)
        }
        if (r.code == 401) throw DebridException("Real-Debrid rejected the sign-in. Connect it again.")
        if (r.code == 403) throw DebridException("Real-Debrid says the account is locked or not premium.")
        return r
    }

    override suspend fun accountInfo(a: DebridAccount): AccountInfo {
        val o = rd(a, "/user").json()
        val until = runCatching { java.time.Instant.parse(o.optString("expiration")).toEpochMilli() }.getOrDefault(0L)
        return AccountInfo(o.optString("username").ifBlank { o.optString("email") }, until, o.optString("type") == "premium")
    }

    override suspend fun cached(a: DebridAccount, hashes: List<String>): Map<String, Boolean>? = null

    private suspend fun info(a: DebridAccount, id: String) = rd(a, "/torrents/info/$id").json()

    override suspend fun resolve(a: DebridAccount, req: TorrentRequest, start: Boolean): Resolve {
        val id = torrentIds[req.hash] ?: run {
            val r = rd(a, "/torrents/addMagnet", "POST", listOf("magnet" to req.magnet))
            r.json().optString("id").ifBlank { return Resolve.Failed("Real-Debrid couldn't add the torrent") }
        }.also { torrentIds[req.hash] = it }

        var t = info(a, id)
        if (t.optString("status") == "waiting_files_selection") {
            val files = t.optJSONArray("files").objects().map { DebridFile(it.optString("id"), it.optString("path"), it.optLong("bytes")) }
            val f = FilePicker.pick(files, req.filename, req.season, req.episode)
                ?: return Resolve.Failed("No video file in this torrent")
            rd(a, "/torrents/selectFiles/$id", "POST", listOf("files" to f.id))
            t = info(a, id)
            // A cached torrent turns "downloaded" almost at once.
            if (t.optString("status") != "downloaded") { delay(1500); t = info(a, id) }
        }
        when (t.optString("status")) {
            "downloaded" -> {
                val link = t.optJSONArray("links")?.optString(0).orEmpty().ifBlank { return Resolve.Failed("Real-Debrid gave no link") }
                val u = rd(a, "/unrestrict/link", "POST", listOf("link" to link)).json()
                val url = u.optString("download").ifBlank { return Resolve.Failed(u.optString("error").ifBlank { "Real-Debrid couldn't unlock the file" }) }
                return Resolve.Ready(url, u.optString("filename").ifBlank { null })
            }
            "magnet_error", "error", "virus", "dead" -> {
                torrentIds.remove(req.hash)
                return Resolve.Failed("Real-Debrid can't fetch this torrent (${t.optString("status")})")
            }
            else -> return if (start) Resolve.Pending(t.optInt("progress", 0)) else {
                // Not cached: remove it again so it doesn't fill up the account.
                runCatching { rd(a, "/torrents/delete/$id", "DELETE") }
                torrentIds.remove(req.hash)
                Resolve.NotCached
            }
        }
    }
}

// ---------------------------------------------------------------- AllDebrid

class AllDebridClient(private val h: DebridHttp) : DebridClient {
    override val service = DebridService.ALL_DEBRID
    private val api = "https://api.alldebrid.com"
    private val magnetIds = ConcurrentHashMap<String, String>()

    /** AllDebrid wraps replies as {status, data} or {status:"error", error:{message}}. */
    private fun data(r: DebridHttp.Reply): JSONObject {
        val o = r.json()
        if (o.optString("status") == "error") {
            throw DebridException(o.optJSONObject("error")?.optString("message")?.ifBlank { null } ?: "AllDebrid error")
        }
        return o.optJSONObject("data") ?: JSONObject()
    }

    suspend fun startCode(): CodeSignIn {
        val d = data(h.call("$api/v4.1/pin/get"))
        return CodeSignIn(
            userCode = d.optString("pin"),
            url = d.optString("user_url").ifBlank { d.optString("base_url") },
            intervalSec = 5,
            expiresAt = System.currentTimeMillis() + d.optLong("expires_in", 600) * 1000,
            state = mapOf("pin" to d.optString("pin"), "check" to d.optString("check")),
        )
    }

    suspend fun pollCode(s: CodeSignIn): DebridAccount? {
        val d = data(h.call("$api/v4/pin/check", "POST", form = listOf("pin" to s.state.getValue("pin"), "check" to s.state.getValue("check"))))
        if (!d.optBoolean("activated")) return null
        val key = d.optString("apikey").ifBlank { return null }
        val acc = DebridAccount(service, "", key)
        val info = accountInfo(acc)
        return acc.copy(label = info.label, premiumUntil = info.premiumUntil)
    }

    override suspend fun accountInfo(a: DebridAccount): AccountInfo {
        val r = h.call("$api/v4/user", bearer = a.token)
        if (r.code == 401) throw DebridException("AllDebrid rejected that key")
        val u = data(r).optJSONObject("user") ?: throw DebridException("AllDebrid didn't return the account")
        return AccountInfo(u.optString("username"), u.optLong("premiumUntil") * 1000, u.optBoolean("isPremium"))
    }

    /**
     * AllDebrid has no separate cache check: adding magnets reports "ready" for cached ones.
     * They are removed again straight away so the account isn't filled up.
     */
    override suspend fun cached(a: DebridAccount, hashes: List<String>): Map<String, Boolean>? {
        if (hashes.isEmpty()) return emptyMap()
        val d = data(h.call("$api/v4/magnet/upload", "POST", a.token, form = hashes.map { "magnets[]" to it }))
        val out = HashMap<String, Boolean>()
        d.optJSONArray("magnets").objects().forEach { m ->
            val hash = m.optString("hash").lowercase()
            if (hash.isNotBlank() && m.optJSONObject("error") == null) out[hash] = m.optBoolean("ready")
            m.optString("id").takeIf { it.isNotBlank() }?.let { id ->
                runCatching { h.call("$api/v4/magnet/delete", "POST", a.token, form = listOf("id" to id)) }
            }
        }
        return out
    }

    private fun flatten(nodes: JSONArray?, prefix: String = ""): List<DebridFile> = nodes.objects().flatMap { n ->
        val name = n.optString("n")
        when {
            n.has("e") -> flatten(n.optJSONArray("e"), "$prefix$name/")
            else -> listOf(DebridFile(n.optString("l"), "$prefix$name", n.optLong("s"), n.optString("l")))
        }
    }

    override suspend fun resolve(a: DebridAccount, req: TorrentRequest, start: Boolean): Resolve {
        var ready = false
        var id = magnetIds[req.hash]
        if (id == null) {
            val m = data(h.call("$api/v4/magnet/upload", "POST", a.token, form = listOf("magnets[]" to req.magnet)))
                .optJSONArray("magnets").objects().firstOrNull() ?: return Resolve.Failed("AllDebrid couldn't add the torrent")
            m.optJSONObject("error")?.let { return Resolve.Failed(it.optString("message").ifBlank { "AllDebrid couldn't add the torrent" }) }
            ready = m.optBoolean("ready")
            id = m.optString("id")
            magnetIds[req.hash] = id
        }

        if (!ready) {
            val st = data(h.call("$api/v4.1/magnet/status", "POST", a.token, form = listOf("id" to id)))
            val m = st.optJSONArray("magnets").objects().firstOrNull() ?: st.optJSONObject("magnets")
            val code = m?.optInt("statusCode", -1) ?: -1
            when {
                code == 4 -> ready = true
                code in 5..15 -> { magnetIds.remove(req.hash); return Resolve.Failed("AllDebrid can't fetch this torrent (${m?.optString("status")})") }
                !start -> {
                    runCatching { h.call("$api/v4/magnet/delete", "POST", a.token, form = listOf("id" to id)) }
                    magnetIds.remove(req.hash)
                    return Resolve.NotCached
                }
                else -> {
                    val size = m?.optLong("size") ?: 0
                    val done = m?.optLong("downloaded") ?: 0
                    return Resolve.Pending(if (size > 0) (done * 100 / size).toInt() else null)
                }
            }
        }
        val files = data(h.call("$api/v4/magnet/files", "POST", a.token, form = listOf("id[]" to id)))
            .optJSONArray("magnets").objects().firstOrNull()?.optJSONArray("files")
        val f = FilePicker.pick(flatten(files), req.filename, req.season, req.episode) ?: return Resolve.Failed("No video file in this torrent")
        val u = data(h.call("$api/v4/link/unlock", "POST", a.token, form = listOf("link" to (f.link ?: f.id))))
        val url = u.optString("link").ifBlank { return Resolve.Failed("AllDebrid couldn't unlock the file") }
        return Resolve.Ready(url, u.optString("filename").ifBlank { null })
    }
}

// ---------------------------------------------------------------- TorBox

class TorBoxClient(private val h: DebridHttp) : DebridClient {
    override val service = DebridService.TORBOX
    private val api = "https://api.torbox.app/v1/api"
    private val torrentIds = ConcurrentHashMap<String, String>()

    private fun ok(r: DebridHttp.Reply): JSONObject {
        val o = r.json()
        if (r.code == 403 || r.code == 401) throw DebridException("TorBox rejected that key")
        if (o.has("success") && !o.optBoolean("success")) throw DebridException(o.optString("detail").ifBlank { "TorBox error" })
        return o
    }

    override suspend fun accountInfo(a: DebridAccount): AccountInfo {
        val d = ok(h.call("$api/user/me?settings=false", bearer = a.token)).optJSONObject("data") ?: throw DebridException("TorBox didn't return the account")
        val until = runCatching { java.time.OffsetDateTime.parse(d.optString("premium_expires_at")).toInstant().toEpochMilli() }.getOrDefault(0L)
        return AccountInfo(d.optString("email"), until, d.optInt("plan", 0) > 0)
    }

    override suspend fun cached(a: DebridAccount, hashes: List<String>): Map<String, Boolean>? {
        if (hashes.isEmpty()) return emptyMap()
        val o = ok(h.call("$api/torrents/checkcached?hash=${hashes.joinToString(",")}&format=list&list_files=false", bearer = a.token))
        val found = o.optJSONArray("data").objects().map { it.optString("hash").lowercase() }.toSet() +
            (o.optJSONObject("data")?.keys()?.asSequence()?.map { it.lowercase() }?.toSet().orEmpty())
        return hashes.associateWith { it in found }
    }

    override suspend fun resolve(a: DebridAccount, req: TorrentRequest, start: Boolean): Resolve {
        if (!torrentIds.containsKey(req.hash) && !start) {
            if (cached(a, listOf(req.hash))?.get(req.hash) != true) return Resolve.NotCached
        }
        val id = torrentIds[req.hash] ?: run {
            val d = ok(h.call("$api/torrents/createtorrent", "POST", a.token, multipart = listOf("magnet" to req.magnet, "seed" to "3", "allow_zip" to "false")))
                .optJSONObject("data") ?: return Resolve.Failed("TorBox couldn't add the torrent")
            d.optString("torrent_id").ifBlank { d.optString("id") }.ifBlank { return Resolve.Failed("TorBox couldn't add the torrent") }
        }.also { torrentIds[req.hash] = it }

        val t = ok(h.call("$api/torrents/mylist?id=$id&bypass_cache=true", bearer = a.token)).optJSONObject("data")
            ?: return Resolve.Pending(null)
        val finished = t.optBoolean("download_finished") || t.optBoolean("download_present")
        if (!finished) return Resolve.Pending((t.optDouble("progress", 0.0) * 100).toInt())
        val files = t.optJSONArray("files").objects().map { DebridFile(it.optString("id"), it.optString("name").ifBlank { it.optString("short_name") }, it.optLong("size")) }
        val f = FilePicker.pick(files, req.filename, req.season, req.episode) ?: return Resolve.Failed("No video file in this torrent")
        val d = ok(h.call("$api/torrents/requestdl?token=${a.token}&torrent_id=$id&file_id=${f.id}&redirect=false", bearer = a.token))
        val url = d.optString("data").takeIf { it.startsWith("http") } ?: return Resolve.Failed("TorBox couldn't make a link")
        return Resolve.Ready(url, f.name.substringAfterLast('/'))
    }
}

// ---------------------------------------------------------------- Premiumize

class PremiumizeClient(private val h: DebridHttp) : DebridClient {
    override val service = DebridService.PREMIUMIZE
    private val api = "https://www.premiumize.me/api"
    private val transferIds = ConcurrentHashMap<String, String>()

    private fun ok(r: DebridHttp.Reply): JSONObject {
        if (r.code == 401) throw DebridException("Premiumize rejected that key")
        val o = r.json()
        if (o.optString("status") == "error") throw DebridException(o.optString("message").ifBlank { "Premiumize error" })
        return o
    }

    override suspend fun accountInfo(a: DebridAccount): AccountInfo {
        val o = ok(h.call("$api/account/info", bearer = a.token))
        val until = o.optLong("premium_until", 0) * 1000
        return AccountInfo("Customer ${o.optString("customer_id")}", until, until > System.currentTimeMillis())
    }

    override suspend fun cached(a: DebridAccount, hashes: List<String>): Map<String, Boolean>? {
        if (hashes.isEmpty()) return emptyMap()
        val o = ok(h.call("$api/cache/check", "POST", a.token, form = hashes.map { "items[]" to it }))
        val arr = o.optJSONArray("response") ?: return null
        return hashes.withIndex().associate { (i, hash) -> hash to arr.optBoolean(i) }
    }

    override suspend fun resolve(a: DebridAccount, req: TorrentRequest, start: Boolean): Resolve {
        val r = h.call("$api/transfer/directdl", "POST", a.token, form = listOf("src" to req.magnet))
        if (r.code == 401) throw DebridException("Premiumize rejected that key")
        val o = r.json()
        val content = o.optJSONArray("content").objects()
        if (o.optString("status") == "success" && content.isNotEmpty()) {
            val files = content.map { DebridFile(it.optString("link"), it.optString("path"), it.optLong("size"), it.optString("link")) }
            val f = FilePicker.pick(files, req.filename, req.season, req.episode) ?: return Resolve.Failed("No video file in this torrent")
            return Resolve.Ready(f.link ?: f.id, f.name.substringAfterLast('/'))
        }
        if (!start) return Resolve.NotCached
        val id = transferIds[req.hash] ?: run {
            ok(h.call("$api/transfer/create", "POST", a.token, form = listOf("src" to req.magnet))).optString("id")
                .ifBlank { return Resolve.Failed("Premiumize couldn't start the download") }
        }.also { transferIds[req.hash] = it }
        val t = ok(h.call("$api/transfer/list", bearer = a.token)).optJSONArray("transfers").objects().firstOrNull { it.optString("id") == id }
        return when (t?.optString("status")) {
            "error", "deleted", "banned", "timeout" -> { transferIds.remove(req.hash); Resolve.Failed(t.optString("message").ifBlank { "Premiumize couldn't fetch this torrent" }) }
            else -> Resolve.Pending(t?.optDouble("progress", 0.0)?.let { (it * 100).toInt() })
        }
    }
}
