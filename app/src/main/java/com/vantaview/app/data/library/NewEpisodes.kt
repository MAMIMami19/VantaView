// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.library

import com.vantaview.app.data.addon.AddonRepository
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "New episode" badges for series on the Watchlist: a series gets one when an episode has come out since
 * the user added it or last opened its page. Episode dates come from the add-ons (Cinemeta usually), and
 * are looked up at most every six hours per series.
 */
@Singleton
class NewEpisodes @Inject constructor(
    private val addons: AddonRepository,
    private val library: LibraryStore,
) {
    private val newest = ConcurrentHashMap<String, Pair<Long, Long>>() // series id -> (looked up at, newest release)

    /** When the newest released episode of a series came out (0 when unknown). */
    private suspend fun newestRelease(seriesId: String): Long {
        newest[seriesId]?.takeIf { System.currentTimeMillis() - it.first < 6 * 3_600_000L }?.let { return it.second }
        val now = System.currentTimeMillis()
        val meta = runCatching { addons.meta("series", seriesId) }.getOrNull()
        val latest = meta?.videos.orEmpty().filter { it.season > 0 }.mapNotNull { v ->
            v.released?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        }.filter { it <= now }.maxOrNull() ?: 0L
        newest[seriesId] = now to latest
        return latest
    }

    /** Ids of the series in [titles] with a new episode. */
    suspend fun withNew(titles: List<SavedTitle>): Set<String> = titles.filter { it.type == "series" }.take(30).filter { t ->
        val since = maxOf(t.at, library.lastOpened(t.id))
        val latest = newestRelease(t.id)
        since > 0 && latest > since
    }.map { it.id }.toSet()
}
