// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.country

import android.content.Context
import com.vantaview.app.data.db.ProviderEntity
import com.vantaview.app.data.db.VantaDb
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The countries found in one playlist. */
data class CountryScan(
    /** Category remote id → country code (or [CountryDetect.OTHER]). */
    val categoryCountry: Map<String, String>,
    /** Country code → channel count, biggest first. Includes OTHER when some channels have no country. */
    val counts: Map<String, Int>,
) {
    /** Real countries, leaving out OTHER. */
    val countries: List<String> get() = counts.keys.filter { it != CountryDetect.OTHER }
    fun countryOf(categoryRemoteId: String?): String =
        categoryRemoteId?.let { categoryCountry[it] } ?: CountryDetect.OTHER
}

/**
 * Which countries each profile shows for each playlist. Nothing saved means every country shows.
 * Also remembers whether the profile has been asked yet, and carries requests to open the country chooser.
 */
@Singleton
class CountryStore @Inject constructor(
    @ApplicationContext context: Context,
    private val db: VantaDb,
) {
    private val sp = context.getSharedPreferences("countries", Context.MODE_PRIVATE)
    private val cache = HashMap<Long, Pair<Long, CountryScan>>()

    /** Bumps whenever a choice changes, so screens can refresh. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    /** A playlist whose country chooser should open (from Live TV or Sources). */
    val editRequest = MutableStateFlow<Long?>(null)

    private fun key(profileId: Long, providerId: Long) = "p${profileId}_v$providerId"

    /** Chosen countries, or null for "show every country". */
    fun chosen(profileId: Long, providerId: Long): Set<String>? =
        sp.getStringSet(key(profileId, providerId), null)?.toSet()

    fun setChosen(profileId: Long, providerId: Long, codes: Set<String>?) {
        val k = key(profileId, providerId)
        sp.edit().apply { if (codes == null) remove(k) else putStringSet(k, codes) }
            .putBoolean("asked_$k", true).apply()
        _changes.value++
    }

    fun asked(profileId: Long, providerId: Long): Boolean = sp.getBoolean("asked_${key(profileId, providerId)}", false)

    fun markAsked(profileId: Long, providerId: Long) {
        sp.edit().putBoolean("asked_${key(profileId, providerId)}", true).apply()
        _changes.value++
    }

    fun forget(providerId: Long) {
        val e = sp.edit()
        sp.all.keys.filter { it.endsWith("_v$providerId") }.forEach { e.remove(it) }
        e.apply(); cache.remove(providerId)
    }

    /** Groups a playlist's categories by country. Cached until the playlist syncs again. */
    suspend fun scan(provider: ProviderEntity): CountryScan = withContext(Dispatchers.Default) {
        cache[provider.id]?.takeIf { it.first == provider.lastSyncAt }?.let { return@withContext it.second }
        val cats = db.content().categories(provider.id)
        val channels = db.content().countryRows(provider.id)
        val byCat = channels.groupBy { it.categoryRemoteId }
        val catCountry = cats.associate { c ->
            c.remoteId to CountryDetect.forCategory(c.name, byCat[c.remoteId].orEmpty().map { it.name to it.epgChannelId })
        }
        val counts = HashMap<String, Int>()
        channels.forEach { ch ->
            val code = ch.categoryRemoteId?.let { catCountry[it] } ?: CountryDetect.OTHER
            counts[code] = (counts[code] ?: 0) + 1
        }
        val scan = CountryScan(catCountry, counts.entries.sortedByDescending { it.value }.associate { it.key to it.value })
        cache[provider.id] = provider.lastSyncAt to scan
        scan
    }
}
