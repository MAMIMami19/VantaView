// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.library

import android.content.Context
import com.vantaview.app.data.profile.ProfileSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** A film or series saved to the Watchlist or marked as watched. */
data class SavedTitle(val type: String, val id: String, val name: String, val poster: String?, val year: String?, val at: Long) {
    val key get() = "$type:$id"
}

/** A reminder for a programme on a channel. */
data class Reminder(
    val channelId: Long, val channelName: String, val title: String,
    val start: Long, val stop: Long, val leadMinutes: Int,
) {
    val key get() = "$channelId:$start"
    val alertAt get() = start - leadMinutes * 60_000L
}

/**
 * Library for the profile in use: Watchlist, Watched and programme reminders.
 * Stored per profile, newest first.
 */
@Singleton
class LibraryStore @Inject constructor(
    @ApplicationContext context: Context,
    private val session: ProfileSession,
) {
    private val sp = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val changed = MutableStateFlow(0)
    private fun bump() { changed.value++ }

    // ---- Watchlist and Watched ----

    @OptIn(ExperimentalCoroutinesApi::class)
    val watchlist: Flow<List<SavedTitle>> = combine(session.current, changed) { id, _ -> titles("watch_$id") }
    val watched: Flow<List<SavedTitle>> = combine(session.current, changed) { id, _ -> titles("seen_$id") }

    fun inWatchlist(type: String, id: String) = titles("watch_${session.id}").any { it.type == type && it.id == id }
    fun isWatched(type: String, id: String) = titles("seen_${session.id}").any { it.type == type && it.id == id }

    fun setWatchlist(t: SavedTitle, on: Boolean) = toggle("watch_${session.id}", t, on)
    fun setWatched(t: SavedTitle, on: Boolean) {
        toggle("seen_${session.id}", t, on)
        if (on) toggle("watch_${session.id}", t, false) // watched titles leave the Watchlist
    }

    private fun toggle(k: String, t: SavedTitle, on: Boolean) {
        val list = titles(k).filter { it.key != t.key }
        saveTitles(k, if (on) listOf(t.copy(at = System.currentTimeMillis())) + list else list)
        bump()
    }

    private fun titles(k: String): List<SavedTitle> = runCatching {
        val a = JSONArray(sp.getString(k, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            SavedTitle(o.getString("type"), o.getString("id"), o.optString("name"), o.optString("poster").ifBlank { null },
                o.optString("year").ifBlank { null }, o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    private fun saveTitles(k: String, list: List<SavedTitle>) {
        val a = JSONArray()
        list.take(500).forEach { t ->
            a.put(JSONObject().put("type", t.type).put("id", t.id).put("name", t.name)
                .put("poster", t.poster.orEmpty()).put("year", t.year.orEmpty()).put("at", t.at))
        }
        sp.edit().putString(k, a.toString()).apply()
    }

    // ---- Watched episodes (per series) ----

    private fun epsKey(seriesId: String) = "eps_${session.id}_$seriesId"

    /** Episode ids of this series marked as watched on the profile in use. */
    fun watchedEpisodes(seriesId: String): Set<String> = sp.getStringSet(epsKey(seriesId), emptySet()).orEmpty().toSet()

    fun setEpisodesWatched(seriesId: String, videoIds: Collection<String>, on: Boolean) {
        val now = watchedEpisodes(seriesId).let { if (on) it + videoIds else it - videoIds.toSet() }
        sp.edit().putStringSet(epsKey(seriesId), now).apply()
        bump()
    }

    // ---- Reminders ----

    val reminders: Flow<List<Reminder>> = combine(session.current, changed) { id, _ -> remindersFor(id) }
    fun remindersNow(): List<Reminder> = remindersFor(session.id)
    fun hasReminder(channelId: Long, start: Long) = remindersFor(session.id).any { it.channelId == channelId && it.start == start }

    /** Every profile's reminders (the alarm that fires when the app is closed doesn't know the profile). */
    fun allReminders(): List<Reminder> = sp.all.keys.filter { it.startsWith("remind_") }.flatMap { k -> readReminders(k) }

    fun addReminder(r: Reminder) {
        val k = "remind_${session.id}"
        writeReminders(k, (readReminders(k).filter { it.key != r.key } + r).sortedBy { it.start })
        bump()
    }

    fun removeReminder(r: Reminder) {
        sp.all.keys.filter { it.startsWith("remind_") }.forEach { k ->
            val list = readReminders(k)
            if (list.any { it.key == r.key }) writeReminders(k, list.filter { it.key != r.key })
        }
        bump()
    }

    private fun remindersFor(profile: Long): List<Reminder> {
        val k = "remind_$profile"
        val now = System.currentTimeMillis()
        val list = readReminders(k)
        val live = list.filter { it.stop > now } // finished programmes drop off
        if (live.size != list.size) writeReminders(k, live)
        return live
    }

    private fun readReminders(k: String): List<Reminder> = runCatching {
        val a = JSONArray(sp.getString(k, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Reminder(o.getLong("channelId"), o.optString("channel"), o.optString("title"), o.getLong("start"), o.getLong("stop"), o.optInt("lead", 5))
        }
    }.getOrDefault(emptyList())

    private fun writeReminders(k: String, list: List<Reminder>) {
        val a = JSONArray()
        list.forEach { r ->
            a.put(JSONObject().put("channelId", r.channelId).put("channel", r.channelName).put("title", r.title)
                .put("start", r.start).put("stop", r.stop).put("lead", r.leadMinutes))
        }
        sp.edit().putString(k, a.toString()).apply()
    }

    /** Re-reads after a restore. */
    fun reload() = bump()
}
