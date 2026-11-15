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

/** A change the user made to the Library, for Trakt to copy. */
sealed class LibraryChange {
    data class Watchlist(val title: SavedTitle, val on: Boolean) : LibraryChange()
    data class Watched(val title: SavedTitle, val on: Boolean) : LibraryChange()
    data class Episodes(val seriesId: String, val videoIds: List<String>, val on: Boolean) : LibraryChange()
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

    private val _changes = kotlinx.coroutines.flow.MutableSharedFlow<Pair<Long, LibraryChange>>(extraBufferCapacity = 64)
    /** Changes the user makes (with the profile they were made on); not ones copied in from Trakt. */
    val changes: kotlinx.coroutines.flow.SharedFlow<Pair<Long, LibraryChange>> = _changes
    private fun tell(c: LibraryChange) { _changes.tryEmit(session.id to c) }

    // ---- Watchlist and Watched ----

    @OptIn(ExperimentalCoroutinesApi::class)
    val watchlist: Flow<List<SavedTitle>> = combine(session.current, changed) { id, _ -> titles("watch_$id") }
    val watched: Flow<List<SavedTitle>> = combine(session.current, changed) { id, _ -> titles("seen_$id") }

    fun inWatchlist(type: String, id: String) = titles("watch_${session.id}").any { it.type == type && it.id == id }
    fun isWatched(type: String, id: String) = titles("seen_${session.id}").any { it.type == type && it.id == id }

    fun setWatchlist(t: SavedTitle, on: Boolean) {
        toggle("watch_${session.id}", t, on)
        tell(LibraryChange.Watchlist(t, on))
    }
    fun setWatched(t: SavedTitle, on: Boolean) {
        val wasListed = on && inWatchlist(t.type, t.id)
        toggle("seen_${session.id}", t, on)
        tell(LibraryChange.Watched(t, on))
        if (wasListed) setWatchlist(t, false) // watched films leave the Watchlist
    }

    fun watchlistNow(profile: Long = session.id): List<SavedTitle> = titles("watch_$profile")
    fun watchedNow(profile: Long = session.id): List<SavedTitle> = titles("seen_$profile")

    /** Replaces a profile's Watchlist or Watched list with what a Trakt sync worked out (no change is reported back). */
    fun replaceFromSync(profile: Long, watchlist: List<SavedTitle>?, watched: List<SavedTitle>?) {
        watchlist?.let { saveTitles("watch_$profile", it) }
        watched?.let { saveTitles("seen_$profile", it) }
        bump()
    }

    /** When the user last opened a series' page, for "New episode" badges. */
    fun lastOpened(seriesId: String): Long = sp.getLong("opened_${session.id}_$seriesId", 0L)
    fun markOpened(seriesId: String) { sp.edit().putLong("opened_${session.id}_$seriesId", System.currentTimeMillis()).apply(); bump() }

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
        tell(LibraryChange.Episodes(seriesId, videoIds.toList(), on))
    }

    /** Every watched episode id of a profile, by series. */
    fun allWatchedEpisodes(profile: Long = session.id): Map<String, Set<String>> {
        val prefix = "eps_${profile}_"
        return sp.all.filterKeys { it.startsWith(prefix) }.mapNotNull { (k, v) ->
            @Suppress("UNCHECKED_CAST") (v as? Set<String>)?.let { k.removePrefix(prefix) to it.toSet() }
        }.toMap()
    }

    /** Sets a profile's watched episodes to exactly these (from a Trakt sync; no change is reported back). */
    fun replaceEpisodesFromSync(profile: Long, bySeries: Map<String, Set<String>>) {
        val prefix = "eps_${profile}_"
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith(prefix) }.forEach { e.remove(it) }
        bySeries.forEach { (series, ids) -> if (ids.isNotEmpty()) e.putStringSet(prefix + series, ids) }
        e.apply()
        bump()
    }

    // ---- Shared with you ----

    /** A title another profile shared with this one, with an optional message. */
    data class Shared(val title: SavedTitle, val from: String, val message: String?, val at: Long) {
        val key get() = "${title.key}:$at"
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val shared: Flow<List<Shared>> = combine(session.current, changed) { id, _ -> sharedFor(id) }

    fun sharedNow(): List<Shared> = sharedFor(session.id)

    /** Puts [t] in [toProfile]'s Library under "Shared with you". */
    fun share(toProfile: Long, t: SavedTitle, from: String, message: String?) {
        val list = listOf(Shared(t, from, message?.trim()?.ifBlank { null }, System.currentTimeMillis())) +
            sharedFor(toProfile).filter { it.title.key != t.key }
        writeShared(toProfile, list.take(100))
    }

    fun removeShared(s: Shared) = writeShared(session.id, sharedFor(session.id).filter { it.key != s.key })

    /** Shares not yet seen on Home ("Mum shared … with you"). */
    fun unseenShares(): List<Shared> = sharedFor(session.id).filter { it.at > sp.getLong("shared_seen_${session.id}", 0L) }
    fun markSharesSeen() { sp.edit().putLong("shared_seen_${session.id}", System.currentTimeMillis()).apply(); bump() }

    private fun sharedFor(profile: Long): List<Shared> = runCatching {
        val a = JSONArray(sp.getString("shared_$profile", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Shared(
                SavedTitle(o.getString("type"), o.getString("id"), o.optString("name"), o.optString("poster").ifBlank { null }, o.optString("year").ifBlank { null }, o.optLong("at")),
                o.optString("from"), o.optString("message").ifBlank { null }, o.optLong("at"),
            )
        }
    }.getOrDefault(emptyList())

    private fun writeShared(profile: Long, list: List<Shared>) {
        val a = JSONArray()
        list.forEach { s ->
            a.put(JSONObject().put("type", s.title.type).put("id", s.title.id).put("name", s.title.name).put("poster", s.title.poster.orEmpty())
                .put("year", s.title.year.orEmpty()).put("from", s.from).put("message", s.message.orEmpty()).put("at", s.at))
        }
        sp.edit().putString("shared_$profile", a.toString()).apply()
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
