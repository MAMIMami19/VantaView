// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.repo

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds the address of a past programme (catch-up) from a channel's live address and its rule:
 * - "xc|<server time zone>": Xtream Codes timeshift (/timeshift/user/pass/minutes/YYYY-MM-DD:HH-MM/id.ts)
 * - "default|<template>": the M3U catchup-source, a full address or something added to the live one
 * - "append|<template>": the template added to the live address
 * - "shift": ?utc=<start>&lutc=<now> added to the live address
 * - "flussonic": Flussonic archive addresses (index-<start>-<duration>.m3u8, timeshift_abs-<start>.ts)
 * Templates may use {utc} {start} {lutc} {now} {timestamp} {utcend} {end} {duration} {offset}
 * (also written ${start} etc.) and {Y} {m} {d} {H} {M} {S} for the start time in UTC.
 */
object CatchUp {
    fun build(rule: String, live: String, start: Long, stop: Long, now: Long): String? {
        val mode = rule.substringBefore('|').lowercase()
        val arg = rule.substringAfter('|', "")
        return when (mode) {
            "xc" -> xtream(live, start, stop, arg)
            "default" -> if (arg.isBlank()) shift(live, start, now) else fill(arg, start, stop, now).let { if (it.startsWith("http", true)) it else live + it }
            "append" -> live + fill(arg, start, stop, now)
            "shift" -> shift(live, start, now)
            "flussonic", "fs", "flussonic-hls", "flussonic-ts" -> flussonic(live, start, stop)
            else -> null
        }
    }

    private fun shift(live: String, start: Long, now: Long) =
        live + (if ('?' in live) "&" else "?") + "utc=${start / 1000}&lutc=${now / 1000}"

    fun fill(template: String, start: Long, stop: Long, now: Long): String {
        val s = start / 1000
        val e = stop / 1000
        val n = now / 1000
        val utc = TimeZone.getTimeZone("UTC")
        fun f(p: String) = SimpleDateFormat(p, Locale.US).apply { timeZone = utc }.format(Date(start))
        var out = template
        val values = mapOf(
            "utc" to s, "start" to s, "lutc" to n, "now" to n, "timestamp" to n,
            "utcend" to e, "end" to e, "duration" to (e - s), "offset" to (n - s),
        )
        values.forEach { (k, v) -> out = out.replace("\${$k}", v.toString()).replace("{$k}", v.toString()) }
        mapOf("Y" to "yyyy", "m" to "MM", "d" to "dd", "H" to "HH", "M" to "mm", "S" to "ss").forEach { (k, p) ->
            out = out.replace("{$k}", f(p))
        }
        return out
    }

    private fun flussonic(live: String, start: Long, stop: Long): String? {
        val s = start / 1000
        val d = (stop - start) / 1000
        val base = live.substringBefore('?')
        val query = live.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        Regex("/(index|video|mono)\\.m3u8$").find(base)?.let { m ->
            return base.substring(0, m.range.first) + "/${m.groupValues[1]}-$s-$d.m3u8" + query
        }
        if (base.endsWith("/mpegts")) return base.removeSuffix("/mpegts") + "/timeshift_abs-$s.ts" + query
        return null
    }

    private fun xtream(live: String, start: Long, stop: Long, tz: String): String? {
        // http://host/live/user/pass/123.ts or http://host/user/pass/123
        val m = Regex("^(https?://[^/]+)(?:/live)?/([^/]+)/([^/]+)/(\\d+)(?:\\.\\w+)?$").find(live.substringBefore('?')) ?: return null
        val (host, user, pass, id) = m.destructured
        val zone = TimeZone.getTimeZone(tz.ifBlank { "UTC" })
        val at = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply { timeZone = zone }.format(Date(start))
        val minutes = ((stop - start) / 60_000).coerceAtLeast(1)
        return "$host/timeshift/$user/$pass/$minutes/$at/$id.ts"
    }
}
