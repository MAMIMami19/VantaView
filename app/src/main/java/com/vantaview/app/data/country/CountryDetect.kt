// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.country

import java.util.Locale

/**
 * Works out which country a live category or channel belongs to from the tags providers already use:
 * "UK | Sports", "|DE| News", "FR: Kids", "Germany - Movies", "Sports (UK)", "UK: BBC One", or a guide id like "bbcone.uk".
 */
object CountryDetect {
    /** Channels with no country tag. */
    const val OTHER = "OTHER"

    /** Country code → display name. Codes follow what IPTV providers use (AR is Arabic, EXYU is ex-Yugoslavia). */
    val names: Map<String, String> = linkedMapOf(
        "UK" to "United Kingdom", "IE" to "Ireland", "US" to "United States", "CA" to "Canada",
        "AU" to "Australia", "NZ" to "New Zealand", "FR" to "France", "DE" to "Germany", "ES" to "Spain",
        "IT" to "Italy", "PT" to "Portugal", "NL" to "Netherlands", "BE" to "Belgium", "CH" to "Switzerland",
        "AT" to "Austria", "PL" to "Poland", "SE" to "Sweden", "NO" to "Norway", "DK" to "Denmark",
        "FI" to "Finland", "GR" to "Greece", "TR" to "Turkey", "RO" to "Romania", "BG" to "Bulgaria",
        "HU" to "Hungary", "CZ" to "Czechia", "SK" to "Slovakia", "HR" to "Croatia", "RS" to "Serbia",
        "EXYU" to "Ex-Yugoslavia", "AL" to "Albania", "RU" to "Russia", "UA" to "Ukraine", "IN" to "India",
        "PK" to "Pakistan", "BD" to "Bangladesh", "AR" to "Arabic", "IR" to "Iran", "KU" to "Kurdish",
        "AF" to "Afghanistan", "AFR" to "Africa", "NG" to "Nigeria", "ZA" to "South Africa", "BR" to "Brazil",
        "MX" to "Mexico", "LATAM" to "Latin America", "PH" to "Philippines", "CN" to "China", "JP" to "Japan",
        "KR" to "Korea", "TH" to "Thailand", "VN" to "Vietnam", "MY" to "Malaysia", "ID" to "Indonesia",
        "IL" to "Israel", "CARIB" to "Caribbean",
    )

    private val aliases: Map<String, String> = buildMap {
        names.keys.forEach { put(it, it) }
        mapOf(
            "GB" to "UK", "GBR" to "UK", "USA" to "US", "CAN" to "CA", "AUS" to "AU", "FRA" to "FR",
            "GER" to "DE", "DEU" to "DE", "ESP" to "ES", "SPA" to "ES", "ITA" to "IT", "POR" to "PT", "PRT" to "PT",
            "NLD" to "NL", "HOL" to "NL", "BEL" to "BE", "SUI" to "CH", "AUT" to "AT", "POL" to "PL", "SWE" to "SE",
            "NOR" to "NO", "DEN" to "DK", "DNK" to "DK", "FIN" to "FI", "GRE" to "GR", "GRC" to "GR", "TUR" to "TR",
            "ROM" to "RO", "ROU" to "RO", "BUL" to "BG", "HUN" to "HU", "CZE" to "CZ", "SVK" to "SK", "HRV" to "HR",
            "CRO" to "HR", "SRB" to "RS", "EX-YU" to "EXYU", "YU" to "EXYU", "ALB" to "AL", "RUS" to "RU",
            "UKR" to "UA", "IND" to "IN", "PAK" to "PK", "ARAB" to "AR", "ARA" to "AR", "ARABIC" to "AR",
            "IRN" to "IR", "KUR" to "KU", "AFG" to "AF", "AFRICA" to "AFR", "NGA" to "NG", "BRA" to "BR",
            "MEX" to "MX", "LAT" to "LATAM", "LATINO" to "LATAM", "PHI" to "PH", "CHN" to "CN", "JPN" to "JP",
            "KOR" to "KR", "THA" to "TH", "VIE" to "VN", "MAL" to "MY", "INA" to "ID", "ISR" to "IL",
            "CARIBBEAN" to "CARIB",
        ).forEach { (k, v) -> put(k, v) }
        names.forEach { (code, name) -> put(name.uppercase(), code) }
        mapOf(
            "ENGLAND" to "UK", "BRITAIN" to "UK", "GREAT BRITAIN" to "UK", "SCOTLAND" to "UK", "WALES" to "UK",
            "AMERICA" to "US", "DEUTSCHLAND" to "DE", "ESPANA" to "ES", "ITALIA" to "IT", "NEDERLAND" to "NL",
            "POLSKA" to "PL", "TURKIYE" to "TR", "PORTUGUESE" to "PT", "FRENCH" to "FR", "GERMAN" to "DE",
            "SPANISH" to "ES", "ITALIAN" to "IT", "DUTCH" to "NL", "POLISH" to "PL", "TURKISH" to "TR",
            "INDIAN" to "IN", "HINDI" to "IN", "PUNJABI" to "IN", "URDU" to "PK", "PERSIAN" to "IR", "FARSI" to "IR",
            "GREEK" to "GR", "ROMANIA" to "RO", "ALBANIAN" to "AL", "RUSSIAN" to "RU", "CANADIAN" to "CA",
            "IRISH" to "IE", "AFRICAN" to "AFR", "LATIN" to "LATAM",
        ).forEach { (k, v) -> put(k, v) }
    }

    /** Short words that are also English words: only trusted when followed by a separator, not a space. */
    private val wordLike = setOf("IT", "ID", "IN", "NO", "AT", "AL", "AR", "AF", "MY", "BE", "IS")

    private val leadingSep = Regex("""^[\s\[\(|#*★✦●•▶►]*([A-Za-z][A-Za-z\- ]{0,18}?)\s*[\]\)|:\-–—•»/]""")
    private val leadingSpace = Regex("""^[\s\[\(|#*]*([A-Z]{2,3})\s+\S""")
    private val trailing = Regex("""[\[\(|]\s*([A-Za-z\-]{2,9})\s*[\]\)|]\s*$""")

    private fun lookup(token: String?): String? {
        val t = token?.trim()?.uppercase()?.replace(Regex("\\s+"), " ") ?: return null
        return aliases[t]
    }

    /** The country in a category or channel name, or null if it has no country tag. */
    fun fromName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        if (Regex("^[\\s\\[(|#*]*EX-?YU", RegexOption.IGNORE_CASE).containsMatchIn(name)) return "EXYU"
        leadingSep.find(name)?.let { m -> lookup(m.groupValues[1])?.let { return it } }
        leadingSpace.find(name)?.let { m ->
            val code = m.groupValues[1]
            if (code !in wordLike) lookup(code)?.let { return it }
        }
        trailing.find(name)?.let { m -> lookup(m.groupValues[1])?.let { return it } }
        return null
    }

    /** The country at the end of a guide id such as "bbcone.uk" or "cnn.us". */
    fun fromGuideId(id: String?): String? {
        val tail = id?.substringBefore('@')?.substringAfterLast('.', "")?.trim()?.uppercase() ?: return null
        if (tail.length != 2) return null
        return aliases[tail]?.takeIf { it.length == 2 || it == "UK" }
    }

    /**
     * Each channel's country, in the same order as [channels] (name, guide id). A country in the category name
     * wins; otherwise the channel's own name or guide id; otherwise the country most of the category shares.
     */
    fun assign(categoryName: String?, channels: List<Pair<String, String?>>): List<String> {
        fromName(categoryName)?.let { c -> return List(channels.size) { c } }
        val own = channels.map { (n, guide) -> fromName(n) ?: fromGuideId(guide) }
        val tagged = own.filterNotNull()
        val common = if (tagged.isEmpty()) null else tagged.groupingBy { it }.eachCount().maxBy { it.value }
            .takeIf { it.value * 10 >= channels.size * 6 }?.key
        return own.map { it ?: common ?: OTHER }
    }

    /** The country this device is set to, as a provider-style code (GB becomes UK). */
    fun deviceCountry(locale: Locale = Locale.getDefault()): String? = lookup(locale.country)

    fun displayName(code: String): String = if (code == OTHER) "Other channels" else names[code] ?: code
}
