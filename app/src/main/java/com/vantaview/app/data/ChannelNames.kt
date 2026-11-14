// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data

/**
 * Tidies channel names from providers for display. The stored name is never changed, so search,
 * country detection and favourites keep working on the original.
 */
object ChannelNames {

    /** Symbols providers use to draw divider rows, e.g. "✦●✦ SUPERSPORTS ✦●✦" or "◆◆◆ CINEMA HD ◆◆◆". */
    private fun isDeco(c: Char): Boolean =
        !c.isLetterOrDigit() && !c.isWhitespace() && c !in "[]()|.&+'!?:-/_,"

    private val eventOnly = Regex("""[\[(]?\s*event\s*only\s*[\])]?""", RegexOption.IGNORE_CASE)

    // "UK| Name", "UK | Name", "|UK| Name", "[UK] Name"
    private val countryPrefix = Regex("""^\s*(?:\|\s*)?(?:\[[A-Z]{2,3}]\s*\|?|[A-Z]{2,3}\s*\|)\s*""")

    /** A row that is only a heading the provider put between channels, not something to play. */
    fun isDivider(name: String): Boolean {
        val t = name.trim()
        if (t.isEmpty()) return true
        val lead = t.takeWhile { isDeco(it) || it.isWhitespace() }.count { isDeco(it) }
        val trail = t.takeLastWhile { isDeco(it) || it.isWhitespace() }.count { isDeco(it) }
        if (lead >= 2 || trail >= 2) return true
        // Nothing but symbols, e.g. "##########" or "-----".
        return t.none { it.isLetterOrDigit() }
    }

    /** The words inside a divider row, e.g. "SUPERSPORTS". */
    fun dividerTitle(name: String): String =
        name.trim().trim { isDeco(it) || it.isWhitespace() || it == '-' || it == '_' || it == '=' }.ifBlank { name.trim() }

    /** Channels that only carry something while an event is on. */
    fun isEventOnly(name: String): Boolean = eventOnly.containsMatchIn(name)

    /** The name to show: country prefix and "[EVENT ONLY]" removed. Falls back to the original. */
    fun display(name: String): String {
        var s = eventOnly.replace(name, " ")
        s = countryPrefix.replace(s, "")
        s = s.replace(Regex("""\s{2,}"""), " ").trim()
        return s.ifBlank { name.trim() }
    }
}
