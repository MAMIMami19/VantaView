// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.remote

/** What Smart Add found in a pasted provider message. */
sealed interface SmartAddResult {
    data class Xtream(val server: String, val username: String, val password: String) : SmartAddResult
    data class M3u(val url: String) : SmartAddResult
    /** A Stalker (MAG) portal: its address and the MAC address the provider registered. */
    data class Stalker(val portal: String, val mac: String) : SmartAddResult
    data object None : SmartAddResult
}

object SmartAdd {
    private val urlRegex = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
    private val getPhp = Regex("^(https?://[^/\\s]+)/(?:get|player_api|xmltv)\\.php\\?(.+)$", RegexOption.IGNORE_CASE)
    private val pathLogin = Regex("^(https?://[^/\\s]+)/(?:live|movie|series)/([^/\\s]+)/([^/\\s]+)/.+$", RegexOption.IGNORE_CASE)
    private val hostLabel = Regex("(?im)^\\W*(?:host|server|url|portal|dns|link|address)\\s*(?:url)?\\s*[:=]\\s*(\\S+)")
    private val userLabel = Regex("(?im)^\\W*(?:username|user\\s*name|user|login)\\s*[:=]\\s*(\\S+)")
    private val macRegex = Regex("(?<![0-9A-Fa-f:-])([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f:-])")
    private val portalLabel = Regex("(?im)^\\W*(?:portal|portal\\s*url|stalker|mag\\s*portal)\\s*(?:url)?\\s*[:=]\\s*(\\S+)")
    private val passLabel = Regex("(?im)^\\W*(?:password|pass|pwd)\\s*[:=]\\s*(\\S+)")

    fun parse(text: String): SmartAddResult {
        val urls = urlRegex.findAll(text).map { it.value.trimEnd('.', ',', ')', ';') }.toList()

        for (u in urls) {
            getPhp.find(u)?.let { m ->
                val q = query(m.groupValues[2])
                val user = q["username"]
                val pass = q["password"]
                if (!user.isNullOrBlank() && !pass.isNullOrBlank()) {
                    return SmartAddResult.Xtream(m.groupValues[1], user, pass)
                }
            }
            pathLogin.find(u)?.let { m ->
                return SmartAddResult.Xtream(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            }
        }

        // A MAC address with a portal address is a Stalker (MAG) login.
        macRegex.find(text)?.value?.let { mac ->
            val portal = portalLabel.find(text)?.groupValues?.get(1)
                ?: urls.firstOrNull { looksLikePortal(it) }
                ?: hostLabel.find(text)?.groupValues?.get(1)
                ?: urls.firstOrNull()
            if (!portal.isNullOrBlank()) return SmartAddResult.Stalker(portal.trimEnd('.', ',', ')', ';'), mac.uppercase().replace('-', ':'))
        }

        val user = userLabel.find(text)?.groupValues?.get(1)
        val pass = passLabel.find(text)?.groupValues?.get(1)
        if (!user.isNullOrBlank() && !pass.isNullOrBlank()) {
            val host = hostLabel.find(text)?.groupValues?.get(1) ?: urls.firstOrNull()
            if (!host.isNullOrBlank()) return SmartAddResult.Xtream(normalizeServer(host), user, pass)
        }

        urls.firstOrNull { it.contains("m3u", true) }?.let { return SmartAddResult.M3u(it) }
        urls.firstOrNull()?.let { return SmartAddResult.M3u(it) }
        return SmartAddResult.None
    }

    /** Portal addresses usually end in /c/ or contain stalker_portal or portal.php. */
    fun looksLikePortal(url: String): Boolean =
        Regex("(?i)(/c/?$|/c/index\\.html|stalker_portal|portal\\.php|/server/load\\.php)").containsMatchIn(url.substringBefore('?'))

    /**
     * "example.com:8080/player_api.php" becomes "http://example.com:8080".
     * A real sub-path ("example.com/panel") is kept, because some servers live under one.
     */
    fun normalizeServer(input: String): String {
        var s = input.trim().substringBefore('?').substringBefore('#')
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        val scheme = s.substringBefore("://").lowercase()
        val rest = s.substringAfter("://")
        val host = rest.substringBefore('/')
        var path = if ('/' in rest) rest.substringAfter('/') else ""
        val endpoints = Regex(
            "(?i)(^|/)(player_api\\.php|get\\.php|xmltv\\.php|panel_api\\.php|c|stalker_portal|live|movie|series)(/.*)?$"
        )
        path = path.replace(endpoints, "").trim('/')
        return "$scheme://$host" + if (path.isNotEmpty()) "/$path" else ""
    }

    private fun query(q: String): Map<String, String> =
        q.split('&').mapNotNull { kv ->
            val k = kv.substringBefore('=')
            if (k.isBlank() || '=' !in kv) null
            else k.lowercase() to runCatching { java.net.URLDecoder.decode(kv.substringAfter('='), "UTF-8") }
                .getOrDefault(kv.substringAfter('='))
        }.toMap()
}
