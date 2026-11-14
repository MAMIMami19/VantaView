// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** How often something refreshes by itself. 0 hours = never (only when you press refresh). */
enum class RefreshEvery(val label: String, val hours: Int) {
    NEVER("Never", 0), HALF_DAY("Every 12 hours", 12), DAILY("Daily", 24), WEEKLY("Weekly", 168);
    val ms: Long get() = hours * 3_600_000L
}

/** Settings › Sources and storage. Shared by every profile on this device. */
@Singleton
class SourceSettings @Inject constructor(@ApplicationContext context: Context) {
    private val sp = context.getSharedPreferences("sources", Context.MODE_PRIVATE)

    /** Playlists refresh when VantaView starts if their channels are older than this. */
    var playlists: RefreshEvery
        get() = runCatching { RefreshEvery.valueOf(sp.getString("playlists", null)!!) }.getOrDefault(RefreshEvery.DAILY)
        set(v) { sp.edit().putString("playlists", v.name).apply() }

    /** The TV guide reloads when it's older than this (Never = only when you refresh it). */
    var guide: RefreshEvery
        get() = runCatching { RefreshEvery.valueOf(sp.getString("guide", null)!!) }.getOrDefault(RefreshEvery.HALF_DAY)
        set(v) { sp.edit().putString("guide", v.name).apply() }
}
