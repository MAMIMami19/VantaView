// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.addon

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pulls (install link, name) pairs out of an add-on backup. Understands:
 * - VantaView backups: {"addons":[{"manifestUrl","name","enabled"}]}
 * - Stremio collections, as saved by Stremio Account Bootstrapper or the Stremio API:
 *   {"result":{"addons":[{"transportUrl","manifest":{"name"}}]}} or a bare array of those
 * - Nuvio add-on rows, as saved by Stremio Account Bootstrapper: [{"url","name","enabled","sort_order"}]
 * - NuvioSync backups: {"app":"NuvioSync","original":{"addons":[those rows]}}
 * - Plain text with one install link per line
 * Items are returned in their saved order (Nuvio's sort_order when present). Disabled Nuvio rows are kept.
 */
fun parseBackup(text: String): List<Pair<String, String>> {
    val t = text.trim().removePrefix("\uFEFF").trim()

    fun item(o: JSONObject): Triple<String, String, Int>? {
        val url = o.optString("manifestUrl").ifBlank { o.optString("transportUrl") }.ifBlank { o.optString("url") }
        if (!(url.startsWith("http") || url.startsWith("stremio://"))) return null
        val name = o.optString("name").ifBlank { o.optJSONObject("manifest")?.optString("name").orEmpty() }
        return Triple(url, name, o.optInt("sort_order", Int.MAX_VALUE))
    }

    fun fromArray(arr: JSONArray): List<Pair<String, String>> =
        (0 until arr.length()).mapNotNull { i ->
            when (val v = arr.get(i)) {
                is JSONObject -> item(v)
                else -> v.toString().takeIf { it.startsWith("http") || it.startsWith("stremio://") }?.let { Triple(it, "", Int.MAX_VALUE) }
            }
        }.withIndex().sortedWith(compareBy({ it.value.third }, { it.index })).map { it.value.first to it.value.second }

    return when {
        t.startsWith("{") -> {
            val o = runCatching { JSONObject(t) }.getOrNull() ?: throw IllegalArgumentException("That file couldn't be read")
            val arr = o.optJSONArray("addons") ?: o.optJSONObject("result")?.optJSONArray("addons")
                ?: o.optJSONObject("original")?.optJSONArray("addons") // NuvioSync backups
                ?: throw IllegalArgumentException("That file has no add-ons in it")
            fromArray(arr)
        }
        t.startsWith("[") -> fromArray(runCatching { JSONArray(t) }.getOrNull() ?: throw IllegalArgumentException("That file couldn't be read"))
        else -> t.lines().map { it.trim() }.filter { it.startsWith("http") || it.startsWith("stremio://") }.map { it to "" }
    }
}
