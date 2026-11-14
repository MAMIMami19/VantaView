// SPDX-License-Identifier: GPL-3.0-or-later
// Portions adapted from Stream Goat (c) Syed Ali Hamza, GPL-3.0
// https://github.com/sherazi5110/stream-goat  (LiveTv.kt: parseM3u, rawGithub)
package com.vantaview.app.data.remote

data class M3uEntry(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String?,
    val tvgId: String?,
    val number: Int?,
    val headers: Map<String, String>,
)

data class M3uResult(
    val entries: List<M3uEntry>,
    val epgUrl: String?,
    val skippedVod: Int,
)

object M3uParser {
    private val attr = Regex("([\\w-]+)=\"([^\"]*)\"")
    private val circledLetters = Regex("[\\u24B6-\\u24E9]")
    private val spaces = Regex("\\s+")
    private val vodExt = Regex("\\.(mp4|mkv|avi|mov|wmv|flv)(\\?.*)?$", RegexOption.IGNORE_CASE)

    /** GitHub "blob" page links are turned into the raw file link. */
    /** Video-site page links (not streams). The Free TV list includes some; they can't play here. */
    private val webPageHosts = Regex("""^https?://([a-z0-9-]+\.)*(youtube\.com|youtu\.be|twitch\.tv|dailymotion\.com|dai\.ly)(/|$)""", RegexOption.IGNORE_CASE)

    fun rawGithub(url: String): String {
        val m = Regex("^https?://github\\.com/([^/]+)/([^/]+)/blob/(.+)$").find(url.trim()) ?: return url.trim()
        return "https://raw.githubusercontent.com/${m.groupValues[1]}/${m.groupValues[2]}/${m.groupValues[3]}"
    }

    fun looksLikeVod(url: String): Boolean {
        val u = url.substringBefore('|')
        return vodExt.containsMatchIn(u) || u.contains("/movie/") || u.contains("/series/")
    }

    /**
     * Reads a playlist line by line: #EXTINF attributes, #EXTGRP, #EXTVLCOPT headers and the
     * "url|Header=value&Header2=value" form. Movie and series entries are counted and left out for now.
     */
    fun parse(lines: Sequence<String>): M3uResult {
        val out = ArrayList<M3uEntry>()
        var info: String? = null
        var extGroup: String? = null
        var epgUrl: String? = null
        var skipped = 0
        var first = true
        val options = mutableMapOf<String, String>()

        for (raw in lines) {
            val line = raw.trim().removePrefix("﻿")
            if (first && line.isNotEmpty()) {
                first = false
                if (line.startsWith("<")) throw IllegalArgumentException("This link is a web page, not a playlist")
                if (line.startsWith("#EXTM3U", true)) {
                    val attrs = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    epgUrl = (attrs["url-tvg"] ?: attrs["x-tvg-url"])?.substringBefore(',')?.trim()?.ifBlank { null }
                    continue
                }
            }
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", true) -> { info = line; options.clear(); extGroup = null }
                line.startsWith("#EXTGRP:", true) -> extGroup = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", true) -> {
                    val opt = line.substringAfter(':')
                    when {
                        opt.startsWith("http-referrer=", true) || opt.startsWith("http-referer=", true) ->
                            options["Referer"] = opt.substringAfter('=')
                        opt.startsWith("http-user-agent=", true) -> options["User-Agent"] = opt.substringAfter('=')
                        opt.startsWith("http-origin=", true) -> options["Origin"] = opt.substringAfter('=')
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val inf = info ?: continue
                    info = null
                    val attrs = attr.findAll(inf).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val rawName = inf.substringAfterLast(",", "").trim()
                        .ifBlank { attrs["tvg-name"] ?: "Channel ${out.size + 1}" }
                    // Free-TV marks channels with circled letters; they are not part of the name.
                    val name = rawName.replace(circledLetters, "").replace(spaces, " ").trim().ifBlank { rawName }
                    val headers = options.toMutableMap()
                    var url = line
                    if ('|' in line) {
                        url = line.substringBefore('|')
                        line.substringAfter('|').split('&').forEach { kv ->
                            val k = kv.substringBefore('=').trim()
                            val v = runCatching { java.net.URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8") }
                                .getOrDefault("")
                            if (k.isNotEmpty() && v.isNotEmpty()) {
                                headers[if (k.equals("referrer", true) || k.equals("referer", true)) "Referer" else k] = v
                            }
                        }
                    }
                    val ok = url.startsWith("http", true) || url.startsWith("rtmp", true) ||
                        url.startsWith("rtsp", true) || url.startsWith("udp", true)
                    if (!ok) continue
                    if (webPageHosts.containsMatchIn(url)) continue
                    if (looksLikeVod(url)) { skipped++; continue }
                    out += M3uEntry(
                        name = name,
                        url = url,
                        logo = attrs["tvg-logo"]?.ifBlank { null },
                        group = (attrs["group-title"] ?: extGroup)?.substringBefore(';')?.trim()?.ifBlank { null },
                        tvgId = attrs["tvg-id"]?.ifBlank { null },
                        number = attrs["tvg-chno"]?.toIntOrNull(),
                        headers = headers,
                    )
                }
            }
        }
        if (first) throw IllegalArgumentException("The playlist is empty")
        return M3uResult(out, epgUrl, skipped)
    }
}
