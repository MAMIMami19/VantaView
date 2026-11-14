// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.guide

/**
 * Chooses which guide files to download when a playlist lists many (Free TV links one file per
 * country). Downloading them all would take ages, so only the countries the channels in use belong to
 * are fetched, judged from the endings of their guide IDs ("BBCOne.uk" is the UK).
 */
object GuideSources {
    /** Country files such as epg_ripper_UK1.xml.gz or epg_ripper_IN4.xml.gz; not ALJAZEERA1 or US_LOCALS2. */
    private val countryFile = Regex("""epg_ripper_([A-Z]{2})\d+\.xml(?:\.gz)?$""", RegexOption.IGNORE_CASE)

    fun split(value: String?): List<String> =
        value.orEmpty().split(',').map { it.trim() }.filter { it.startsWith("http", true) }.distinct()

    private fun norm(code: String) = code.lowercase().let { if (it == "uk") "gb" else it }

    /**
     * The links to download for [keys] (guide IDs of the channels in use), most-used country first.
     * With a single link, that link. When nothing matches, the first link, so a guide is still tried.
     */
    fun pick(urls: List<String>, keys: Collection<String>, max: Int = 6): List<String> {
        if (urls.size <= 1) return urls
        val counts = keys.mapNotNull { k ->
            k.substringAfterLast('.', "").takeIf { it.length == 2 && it.all(Char::isLetter) }?.let(::norm)
        }.groupingBy { it }.eachCount()
        val files = urls.mapNotNull { u ->
            countryFile.find(u.substringAfterLast('/'))?.let { norm(it.groupValues[1]) to u }
        }
        val chosen = files.filter { (c, _) -> c in counts }
            .sortedByDescending { (c, _) -> counts[c] }
            .map { it.second }
            .take(max)
        return chosen.ifEmpty { urls.take(1) }
    }
}
