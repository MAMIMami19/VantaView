// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.trakt

import com.vantaview.app.data.addon.MetaPreview
import com.vantaview.app.data.history.HistoryEntry
import com.vantaview.app.data.history.WatchHistory
import com.vantaview.app.data.library.LibraryChange
import com.vantaview.app.data.library.LibraryStore
import com.vantaview.app.data.library.SavedTitle
import com.vantaview.app.data.profile.ProfileSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** The result of comparing this device, Trakt and what they agreed on at the last sync. */
data class Merge(val result: Set<String>, val pushAdd: Set<String>, val pushRemove: Set<String>)

object TraktMerge {
    /**
     * Two-way merge. With no earlier sync ([last] null) everything from both sides is kept. Afterwards an item
     * added on either side is added to the other, and one removed on either side is removed from the other.
     */
    fun merge(local: Set<String>, remote: Set<String>, last: Set<String>?): Merge {
        val result = if (last == null) local + remote else {
            val removed = (last - local) + (last - remote)
            (last - removed) + (local - last) + (remote - last)
        }
        return Merge(result, result - remote, remote - result)
    }
}

/** A row of Trakt titles for Movies or TV Series. */
data class TraktRow(val key: String, val title: String, val items: List<MetaPreview>)

sealed class TraktState {
    data object Idle : TraktState()
    data class Busy(val text: String) : TraktState()
    data class Done(val text: String) : TraktState()
    data class Failed(val text: String) : TraktState()
}

/**
 * Keeps each linked profile's Watchlist, watched films and episodes, and Continue watching in step with Trakt,
 * tells Trakt what's playing, and fetches recommendations and the user's lists.
 */
@Singleton
class TraktSync @Inject constructor(
    http: OkHttpClient,
    private val store: TraktStore,
    private val library: LibraryStore,
    private val history: WatchHistory,
    private val session: ProfileSession,
    @com.vantaview.app.di.AppScope private val scope: CoroutineScope,
) {
    val api = TraktApi(http)
    private val lock = Mutex()
    private val _state = MutableStateFlow<TraktState>(TraktState.Idle)
    val state: StateFlow<TraktState> = _state
    val storeVersion get() = store.version

    fun link(profile: Long = session.id): TraktLink? = store.link(profile)

    private var started = false

    /** Starts listening for Library changes, profile switches and the half-hourly sync. Called once at start-up. */
    fun start() {
        if (started || !api.configured) return
        started = true
        scope.launch { library.changes.collect { (profile, c) -> runCatching { push(profile, c) } } }
        scope.launch { session.current.drop(1).collect { runCatching { sync(it, force = false) } } }
        scope.launch {
            delay(8_000) // let start-up finish first
            while (true) {
                runCatching { sync(session.id, force = false) }
                delay(30 * 60_000L)
            }
        }
    }

    // ---- Sign-in ----

    suspend fun startSignIn(): TraktCode = api.startCode()

    /** Waits for the code to be entered (or to run out), then links the profile and syncs. */
    suspend fun finishSignIn(code: TraktCode, profile: Long = session.id): String {
        while (System.currentTimeMillis() < code.expiresAt) {
            delay(code.intervalSec * 1000L)
            val t = api.pollCode(code) ?: continue
            val name = runCatching { api.username(t.access) }.getOrDefault("")
            store.saveTokens(profile, t, name)
            scope.launch { runCatching { sync(profile, force = true) } }
            return name
        }
        throw TraktException("The code ran out of time. Start again.")
    }

    fun setOptions(o: TraktOptions, profile: Long = session.id) = store.setOptions(profile, o)

    fun unlink(profile: Long = session.id) {
        store.link(profile)?.let { l -> scope.launch { api.revoke(l.tokens) } }
        store.unlink(profile)
        rowCache.keys.removeAll { it.startsWith("$profile:") }
        _state.value = TraktState.Idle
    }

    /** A working access token for the profile, refreshed when it's within a day of running out. */
    private suspend fun token(profile: Long): String? {
        val l = store.link(profile) ?: return null
        if (l.tokens.expiresAt - System.currentTimeMillis() > 86_400_000L || l.tokens.refresh.isEmpty()) return l.tokens.access
        return runCatching { api.refresh(l.tokens).also { store.saveTokens(profile, it) }.access }.getOrDefault(l.tokens.access)
    }

    // ---- Sync ----

    fun syncNow() { scope.launch { runCatching { sync(session.id, force = true) } } }

    suspend fun sync(profile: Long, force: Boolean) = lock.withLock {
        val link = store.link(profile) ?: return@withLock
        if (!api.configured) return@withLock
        if (!force && System.currentTimeMillis() - link.lastSync < 25 * 60_000L) return@withLock
        val tok = token(profile) ?: return@withLock
        val o = link.options
        val done = ArrayList<String>()
        try {
            if (o.watchlist) { _state.value = TraktState.Busy("Syncing the Watchlist…"); done += syncWatchlist(profile, tok) }
            if (o.history) { _state.value = TraktState.Busy("Syncing what you've watched…"); done += syncHistory(profile, tok) }
            if (o.progress) { _state.value = TraktState.Busy("Syncing Continue watching…"); done += syncPlayback(profile, tok) }
            rowCache.keys.removeAll { it.startsWith("$profile:") }
            store.markSynced(profile)
            _state.value = TraktState.Done(done.filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Everything already matched." })
        } catch (e: Exception) {
            _state.value = TraktState.Failed(e.message ?: "Trakt sync failed.")
        }
    }

    private fun movieKey(id: String) = "movie:$id"
    private fun keyOf(t: SavedTitle) = "${t.type}:${t.id}"
    private fun keyOf(t: TraktTitle) = "${t.type}:${t.imdb}"
    private fun titleOfKey(k: String, name: String = "") = TraktTitle(k.substringBefore(':'), k.substringAfter(':'), null, name, null)

    private suspend fun syncWatchlist(profile: Long, tok: String): String {
        val remote = api.watchlist(tok)
        val remoteByKey = remote.associateBy(::keyOf)
        val local = library.watchlistNow(profile)
        val localByKey = local.filter { it.id.startsWith("tt") }.associateBy(::keyOf)
        val m = TraktMerge.merge(localByKey.keys, remoteByKey.keys, store.snapshot(profile, "watch"))
        api.addToWatchlist(tok, m.pushAdd.map { k -> localByKey[k]?.let { TraktTitle(it.type, it.id, null, it.name, null) } ?: titleOfKey(k) })
        api.removeFromWatchlist(tok, m.pushRemove.map { remoteByKey[it] ?: titleOfKey(it) })
        // Keep this device's order and anything Trakt can't hold (titles without an IMDb id); add Trakt's new ones first.
        val newFromTrakt = (m.result - localByKey.keys).mapNotNull { remoteByKey[it] }.map(::saved)
        val kept = local.filter { !it.id.startsWith("tt") || keyOf(it) in m.result }
        library.replaceFromSync(profile, newFromTrakt + kept, null)
        store.saveSnapshot(profile, "watch", m.result)
        return counts("Watchlist", newFromTrakt.size, m.pushAdd.size, (localByKey.keys - m.result).size + m.pushRemove.size)
    }

    private suspend fun syncHistory(profile: Long, tok: String): String {
        // Films.
        val remoteMovies = api.watchedMovies(tok).associateBy(::keyOf)
        val seen = library.watchedNow(profile)
        val localMovies = seen.filter { it.type == "movie" && it.id.startsWith("tt") }.associateBy(::keyOf)
        val mm = TraktMerge.merge(localMovies.keys, remoteMovies.keys, store.snapshot(profile, "seen"))
        // Episodes.
        val localEps = library.allWatchedEpisodes(profile)
        val localEpIds = localEps.values.flatten().filter { it.startsWith("tt") && it.count { c -> c == ':' } == 2 }.toSet()
        val remoteEps = api.watchedEpisodes(tok).associateBy { it.videoId }
        val me = TraktMerge.merge(localEpIds, remoteEps.keys, store.snapshot(profile, "eps"))

        api.addHistory(tok, mm.pushAdd.map { titleOfKey(it) }, me.pushAdd.mapNotNull(::episodeOf))
        api.removeHistory(tok, mm.pushRemove.map { titleOfKey(it) }, me.pushRemove.mapNotNull(::episodeOf))

        val newMovies = (mm.result - localMovies.keys).mapNotNull { remoteMovies[it] }.map(::saved)
        val keptSeen = seen.filter { !(it.type == "movie" && it.id.startsWith("tt")) || keyOf(it) in mm.result }
        library.replaceFromSync(profile, null, newMovies + keptSeen)
        // Episodes by series; series without IMDb ids keep theirs.
        val bySeries = HashMap<String, MutableSet<String>>()
        localEps.forEach { (series, ids) -> ids.filter { !it.startsWith("tt") }.forEach { bySeries.getOrPut(series) { HashSet() } += it } }
        me.result.forEach { id -> bySeries.getOrPut(id.substringBefore(':')) { HashSet() } += id }
        library.replaceEpisodesFromSync(profile, bySeries)
        store.saveSnapshot(profile, "seen", mm.result)
        store.saveSnapshot(profile, "eps", me.result)
        val films = counts("watched films", newMovies.size, mm.pushAdd.size, 0)
        val eps = counts("watched episodes", (me.result - localEpIds).size, me.pushAdd.size, 0)
        return listOf(films, eps).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun episodeOf(videoId: String): TraktEpisode? {
        val p = videoId.split(':')
        if (p.size != 3 || !p[0].startsWith("tt")) return null
        return TraktEpisode(p[0], p[1].toIntOrNull() ?: return null, p[2].toIntOrNull() ?: return null)
    }

    private suspend fun syncPlayback(profile: Long, tok: String): String {
        var added = 0
        for (p in api.playback(tok).sortedBy { it.pausedAt }) {
            val type = p.title.type
            val have = history.getFor(profile, type, p.title.imdb)
            if (have != null && have.updatedAt >= p.pausedAt) continue
            val runtime = (p.runtimeMin.takeIf { it > 0 } ?: if (type == "movie") 100 else 45) * 60_000L
            history.recordFor(
                profile,
                HistoryEntry(
                    type = type, metaId = p.title.imdb, videoId = p.episode?.videoId ?: p.title.imdb, title = p.title.name,
                    poster = have?.poster ?: poster(p.title.imdb), background = have?.background,
                    season = p.episode?.season, episode = p.episode?.number, episodeTitle = p.episodeTitle,
                    positionMs = (runtime * p.progress / 100f).toLong(), durationMs = runtime, updatedAt = p.pausedAt,
                    knownSeason = maxOf(have?.knownSeason ?: 0, p.episode?.season ?: 0),
                ),
            )
            added++
        }
        return if (added > 0) "$added in Continue watching from Trakt" else ""
    }

    private fun counts(what: String, fromTrakt: Int, toTrakt: Int, removed: Int): String = listOfNotNull(
        fromTrakt.takeIf { it > 0 }?.let { "$it $what from Trakt" },
        toTrakt.takeIf { it > 0 }?.let { "$it sent to Trakt" },
        removed.takeIf { it > 0 }?.let { "$it removed" },
    ).joinToString(", ")

    private fun saved(t: TraktTitle) = SavedTitle(t.type, t.imdb, t.name, poster(t.imdb), t.year?.toString(), System.currentTimeMillis())

    // ---- Changes made on this device go to Trakt straight away ----

    /** Films and episodes Trakt already counted from the player, so marking them watched doesn't add a second play. */
    private val scrobbled = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private suspend fun push(profile: Long, c: LibraryChange) {
        val link = store.link(profile) ?: return
        val tok = token(profile) ?: return
        when (c) {
            is LibraryChange.Watchlist -> if (link.options.watchlist && c.title.id.startsWith("tt")) {
                val t = TraktTitle(c.title.type, c.title.id, null, c.title.name, null)
                if (c.on) api.addToWatchlist(tok, listOf(t)) else api.removeFromWatchlist(tok, listOf(t))
                editSnapshot(profile, "watch", keyOf(t), c.on)
            }
            is LibraryChange.Watched -> if (link.options.history && c.title.type == "movie" && c.title.id.startsWith("tt")) {
                val key = movieKey(c.title.id)
                if (!(c.on && recentlyScrobbled(c.title.id))) {
                    val t = TraktTitle("movie", c.title.id, null, c.title.name, null)
                    if (c.on) api.addHistory(tok, listOf(t), emptyList()) else api.removeHistory(tok, listOf(t), emptyList())
                }
                editSnapshot(profile, "seen", key, c.on)
            }
            is LibraryChange.Episodes -> if (link.options.history) {
                val eps = c.videoIds.filter { !(c.on && recentlyScrobbled(it)) }.mapNotNull(::episodeOf)
                if (c.on) api.addHistory(tok, emptyList(), eps) else api.removeHistory(tok, emptyList(), eps)
                c.videoIds.filter { it.startsWith("tt") }.forEach { editSnapshot(profile, "eps", it, c.on) }
            }
        }
    }

    private fun recentlyScrobbled(id: String) = (scrobbled[id] ?: 0L) > System.currentTimeMillis() - 30 * 60_000L

    private fun editSnapshot(profile: Long, name: String, key: String, on: Boolean) {
        val s = store.snapshot(profile, name) ?: return // no sync yet: the first sync merges everything anyway
        store.saveSnapshot(profile, name, if (on) s + key else s - key)
    }

    // ---- What's playing ----

    /**
     * Tells Trakt a film or episode started, paused or stopped ([action] "start", "pause" or "stop"), so it
     * appears as watching now, keeps its place, and counts as watched when stopped near the end.
     */
    fun scrobble(action: String, type: String, metaId: String, videoId: String, title: String, progressPercent: Float) {
        if (!metaId.startsWith("tt")) return
        val profile = session.id
        val link = store.link(profile) ?: return
        if (!link.options.progress) return
        scope.launch {
            runCatching {
                val tok = token(profile) ?: return@runCatching
                val ep = if (type == "series") episodeOf(videoId) else null
                if (type == "series" && ep == null) return@runCatching
                api.scrobble(tok, action, TraktTitle(type, metaId, null, title, null), ep, progressPercent)
                if (action == "stop" && progressPercent >= 80f) scrobbled[if (ep != null) videoId else metaId] = System.currentTimeMillis()
            }
        }
    }

    // ---- Recommendations and lists for Movies and TV Series ----

    private val rowCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<TraktRow>>>()

    /** "Recommended for you" and each of the user's own lists, for films or series. Empty when not linked. */
    suspend fun rows(type: String, profile: Long = session.id): List<TraktRow> {
        val link = store.link(profile) ?: return emptyList()
        if (!link.options.lists) return emptyList()
        val key = "$profile:$type"
        rowCache[key]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000L }?.let { return it.second }
        val tok = token(profile) ?: return emptyList()
        val out = ArrayList<TraktRow>()
        runCatching { api.recommendations(tok, type) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
            out += TraktRow("trakt-recs-$type", "Recommended for you · Trakt", it.map(::preview))
        }
        runCatching { api.lists(tok) }.getOrDefault(emptyList()).take(10).forEach { l ->
            runCatching { api.listItems(tok, l.id, type) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                out += TraktRow("trakt-list-${l.id}-$type", "${l.name} · Trakt", it.map(::preview))
            }
        }
        rowCache[key] = System.currentTimeMillis() to out
        return out
    }

    private fun preview(t: TraktTitle) = MetaPreview(t.imdb, t.type, t.name, poster(t.imdb), t.year?.toString(), null)

    companion object {
        /** Posters by IMDb id from the image service Stremio's Cinemeta uses. */
        fun poster(imdb: String) = "https://images.metahub.space/poster/medium/$imdb/img"
    }
}
