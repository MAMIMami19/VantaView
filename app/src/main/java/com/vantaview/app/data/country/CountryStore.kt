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
    /** Category remote id → the countries its channels come from. */
    val categoryCountries: Map<String, Set<String>>,
    /** Country code → channel count, biggest first. Includes OTHER when some channels have no country. */
    val counts: Map<String, Int>,
) {
    /** Real countries, leaving out OTHER. */
    val countries: List<String> get() = counts.keys.filter { it != CountryDetect.OTHER }
    fun inCategory(categoryRemoteId: String): Set<String> = categoryCountries[categoryRemoteId].orEmpty()
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

    /** Works out every channel's country (only where missing, so after a sync) and counts them. Cached per sync. */
    suspend fun scan(provider: ProviderEntity): CountryScan = withContext(Dispatchers.Default) {
        cache[provider.id]?.takeIf { it.first == provider.lastSyncAt }?.let { return@withContext it.second }
        val dao = db.content()
        if (dao.countryMissing(provider.id) > 0) {
            val names = dao.categories(provider.id).associate { it.remoteId to it.name }
            val byCode = HashMap<String, MutableList<Long>>()
            dao.countryRows(provider.id).groupBy { it.categoryRemoteId }.forEach { (cat, rows) ->
                val codes = CountryDetect.assign(cat?.let { names[it] }, rows.map { it.name to it.epgChannelId })
                rows.forEachIndexed { k, r -> byCode.getOrPut(codes[k]) { mutableListOf() }.add(r.id) }
            }
            byCode.forEach { (code, ids) -> ids.chunked(900).forEach { dao.setCountry(code, it) } }
        }
        val tally = dao.countryTally(provider.id)
        val counts = HashMap<String, Int>()
        val perCat = HashMap<String, MutableSet<String>>()
        tally.forEach { t ->
            val code = t.code ?: CountryDetect.OTHER
            counts[code] = (counts[code] ?: 0) + t.n
            t.category?.let { perCat.getOrPut(it) { mutableSetOf() }.add(code) }
        }
        val scan = CountryScan(perCat, counts.entries.sortedByDescending { it.value }.associate { it.key to it.value })
        cache[provider.id] = provider.lastSyncAt to scan
        scan
    }
}
