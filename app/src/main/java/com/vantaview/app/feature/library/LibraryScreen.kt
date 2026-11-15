// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.library

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vantaview.app.data.addon.MetaPreview
import com.vantaview.app.data.history.HistoryEntry
import com.vantaview.app.data.history.WatchHistory
import com.vantaview.app.data.library.LibraryStore
import com.vantaview.app.data.library.ReminderScheduler
import com.vantaview.app.data.library.Reminder
import com.vantaview.app.data.library.SavedTitle
import com.vantaview.app.feature.vod.PosterCard
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryStore,
    private val alarms: ReminderScheduler,
    history: WatchHistory,
    newEpisodes: com.vantaview.app.data.library.NewEpisodes,
) : ViewModel() {
    private fun <T> Flow<T>.state(initial: T) = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)
    val watchlist: StateFlow<List<SavedTitle>> = library.watchlist.state(emptyList())
    val watched: StateFlow<List<SavedTitle>> = library.watched.state(emptyList())
    /** Watchlist series with an episode out since they were added or last opened. */
    val withNew: StateFlow<Set<String>> = library.watchlist.map { newEpisodes.withNew(it) }.state(emptySet())
    val reminders: StateFlow<List<Reminder>> = library.reminders.state(emptyList())
    val shared: StateFlow<List<LibraryStore.Shared>> = library.shared.state(emptyList())
    fun removeShared(s: LibraryStore.Shared) = library.removeShared(s)
    /** Unfinished films and episodes, most recent first. */
    val continuing: StateFlow<List<HistoryEntry>> = history.entries.map { l -> l.filter { !it.finished } }.state(emptyList())

    fun cancel(r: Reminder) { library.removeReminder(r); alarms.cancel(r) }
    fun unwatch(t: SavedTitle) = library.setWatched(t, false)
    fun unlist(type: String, id: String) {
        library.watchlistNow().firstOrNull { it.type == type && it.id == id }?.let { library.setWatchlist(it, false) }
    }
}

private enum class Tab(val label: String) { WATCHLIST("Watchlist"), CONTINUE("Continue watching"), WATCHED("Watched"), SHARED("Shared with you"), REMINDERS("Reminders") }

/** Library: Watchlist, Continue watching, Watched and programme reminders for this profile. */
@Composable
fun LibraryScreen(
    initialTab: String? = null,
    onOpenTitle: (type: String, id: String, name: String, poster: String?, play: String?) -> Unit,
    onPlayChannel: (Long) -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
) {
    var tab by rememberSaveable { mutableStateOf(Tab.entries.firstOrNull { it.name.equals(initialTab, true) } ?: Tab.WATCHLIST) }
    val watchlist by vm.watchlist.collectAsStateWithLifecycle()
    val watched by vm.watched.collectAsStateWithLifecycle()
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    val continuing by vm.continuing.collectAsStateWithLifecycle()
    val withNew by vm.withNew.collectAsStateWithLifecycle()
    val shared by vm.shared.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        com.vantaview.app.ui.PageTitle("Library")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Tab.entries) { t ->
                val n = when (t) {
                    Tab.WATCHLIST -> watchlist.size; Tab.CONTINUE -> continuing.size
                    Tab.WATCHED -> watched.size; Tab.REMINDERS -> reminders.size; Tab.SHARED -> shared.size
                }
                FocusCard(onClick = { tab = t }, selected = tab == t) {
                    Text("${t.label} · $n", color = if (tab == t) Vanta.Cyan else Vanta.Text, fontWeight = if (tab == t) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        when (tab) {
            Tab.WATCHLIST -> Posters(
                watchlist.map { MetaPreview(it.id, it.type, it.name, it.poster, it.year, null) },
                "Nothing here yet. Use + Watchlist on a film or series to save it for later.",
                badges = withNew.associateWith { "New episode" },
            ) { m -> onOpenTitle(m.type, m.id, m.name, m.poster, null) }
            Tab.WATCHED -> Posters(
                watched.map { MetaPreview(it.id, it.type, it.name, it.poster, it.year, null) },
                "Films you finish, and titles you mark as watched, show here.",
            ) { m -> onOpenTitle(m.type, m.id, m.name, m.poster, null) }
            Tab.CONTINUE -> Posters(
                continuing.map { e ->
                    MetaPreview(e.metaId, e.type, e.title + (if (e.season != null) " · S${e.season} E${e.episode}" else ""), e.poster, null, null)
                },
                "Films and episodes you've started show here.",
            ) { m ->
                val e = continuing.firstOrNull { it.metaId == m.id && it.type == m.type }
                onOpenTitle(m.type, m.id, e?.title ?: m.name, m.poster, if (m.type == "movie") "movie" else e?.videoId)
            }
            Tab.REMINDERS -> Reminders(reminders, onPlayChannel, vm::cancel)
            Tab.SHARED -> SharedList(shared, onOpen = { t -> onOpenTitle(t.type, t.id, t.name, t.poster, null) }, onRemove = vm::removeShared)
        }
    }
}

@Composable
private fun Posters(
    items: List<MetaPreview>, empty: String, badges: Map<String, String> = emptyMap(),
    onRemove: ((MetaPreview) -> Unit)? = null, onOpen: (MetaPreview) -> Unit,
) {
    if (items.isEmpty()) { Text(empty, color = Vanta.TextDim, fontSize = 15.sp); return }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(124.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.type + it.id }) { m ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PosterCard(m, onClick = { onOpen(m) }, modifier = Modifier.fillMaxWidth(), badge = badges[m.id])
            }
        }
    }
}

@Composable
private fun Reminders(list: List<Reminder>, onPlay: (Long) -> Unit, onCancel: (Reminder) -> Unit) {
    if (list.isEmpty()) {
        Text("No reminders. In the TV Guide, pick a programme that hasn't started and choose Remind me.", color = Vanta.TextDim, fontSize = 15.sp)
        return
    }
    val day = SimpleDateFormat("EEE d MMM", Locale.UK)
    val time = SimpleDateFormat("HH:mm", Locale.UK)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list, key = { it.key }) { r ->
            val now = System.currentTimeMillis()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(r.title, color = Vanta.Text, fontWeight = FontWeight.Bold)
                    Text(
                        "${day.format(Date(r.start))} · ${time.format(Date(r.start))} – ${time.format(Date(r.stop))} · ${r.channelName}" +
                            " · reminds ${r.leadMinutes} min before",
                        color = Vanta.TextDim, fontSize = 13.sp,
                    )
                }
                if (r.start <= now) FocusCard(onClick = { onPlay(r.channelId) }) { Text("▶ Watch", color = Vanta.Text) }
                FocusCard(onClick = { onCancel(r) }) { Text("Cancel", color = Vanta.Text) }
            }
        }
    }
}

/** Titles other profiles shared with this one, with who shared them and their message. */
@Composable
private fun SharedList(items: List<LibraryStore.Shared>, onOpen: (SavedTitle) -> Unit, onRemove: (LibraryStore.Shared) -> Unit) {
    if (items.isEmpty()) {
        Text("Nothing shared yet. Other profiles can share a film or series with you: press and hold its poster.", color = Vanta.TextDim, fontSize = 15.sp)
        return
    }
    androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items.size, key = { items[it].key }) { i ->
            val s = items[i]
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusCard(onClick = { onOpen(s.title) }, modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        androidx.compose.foundation.layout.Box(Modifier.size(width = 54.dp, height = 80.dp).clip(Vanta.Shape).background(Vanta.SurfaceHigh)) {
                            s.title.poster?.let { coil.compose.AsyncImage(model = it, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(s.title.name, color = Vanta.Text, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("From ${s.from}", color = Vanta.Cyan, fontSize = 13.sp)
                            s.message?.let { Text("\u201C$it\u201D", color = Vanta.TextDim, fontSize = 14.sp, maxLines = 3) }
                        }
                    }
                }
                FocusCard(onClick = { onRemove(s) }) { Text("Remove", color = Vanta.Error, maxLines = 1, softWrap = false) }
            }
        }
    }
}
