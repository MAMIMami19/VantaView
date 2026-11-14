// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.guide

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/** XMLTV times look like "20261114190000 +0000" (offset optional, seconds optional). */
object XmltvTime {
    private val utc = TimeZone.getTimeZone("UTC")

    /** Milliseconds since 1970 (UTC), or null if the text can't be read. */
    fun parse(text: String?): Long? {
        val t = text?.trim().orEmpty()
        val digits = t.takeWhile { it.isDigit() }
        if (digits.length < 12) return null
        val y = digits.substring(0, 4).toInt()
        val mo = digits.substring(4, 6).toInt()
        val d = digits.substring(6, 8).toInt()
        val h = digits.substring(8, 10).toInt()
        val mi = digits.substring(10, 12).toInt()
        val s = if (digits.length >= 14) digits.substring(12, 14).toInt() else 0
        val cal = GregorianCalendar(utc).apply {
            clear()
            set(y, mo - 1, d, h, mi, s)
        }
        var ms = cal.timeInMillis
        // Offset such as +0100 or -0500: local time minus offset gives UTC.
        val rest = t.substring(digits.length).trim()
        val m = Regex("""^([+-])(\d{2}):?(\d{2})""").find(rest)
        if (m != null) {
            val sign = if (m.groupValues[1] == "-") -1 else 1
            val off = (m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()) * 60_000L
            ms -= sign * off
        }
        return ms
    }

    /** Local midnight [daysFromToday] days from today, in milliseconds. */
    fun dayStart(daysFromToday: Int, now: Long = System.currentTimeMillis()): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, daysFromToday)
        }.timeInMillis
}
