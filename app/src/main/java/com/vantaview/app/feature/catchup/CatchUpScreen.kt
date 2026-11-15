// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.catchup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.vantaview.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.vantaview.app.data.ChannelNames
import com.vantaview.app.data.db.ChannelEntity
import com.vantaview.app.data.db.ProgrammeEntity
import com.vantaview.app.data.db.VantaDb
import com.vantaview.app.data.repo.ProviderRepository
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** A group of catch-up channels ("All", or one of the playlist's categories). */
data class CatchUpGroup(val key: String, val name: String, val channels: List<ChannelEntity>)

/** A finished programme from a search, with its channel. */
data class PastHit(val programme: ProgrammeEntity, val channel: ChannelEntity)

data class CatchUpUi(
    val loading: Boolean = true,
    val groups: List<CatchUpGroup> = emptyList(),
    val group: String? = null,
    val channel: ChannelEntity? = null,
    /** Days back: 0 = today. */
    val day: Int = 0,
    val programmes: List<ProgrammeEntity> = emptyList(),
    val hits: List<PastHit>? = null,
    val message: String? = null,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class CatchUpViewModel @Inject constructor(
    private val db: VantaDb,
    private val repo: ProviderRepository,
    private val parental: com.vantaview.app.data.profile.ParentalControl,
    private val vodSession: com.vantaview.app.data.addon.VodSession,
    private val guide: com.vantaview.app.data.guide.GuideRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(CatchUpUi())
    val ui: StateFlow<CatchUpUi> = _ui
    val query = MutableStateFlow("")

    init {
        viewModelScope.launch {
            repo.observe().collect { providers ->
                val ids = providers.map { it.id }
                val all = if (ids.isEmpty()) emptyList() else db.content().catchUpChannels(ids)
                // Parental control: hidden adult channels stay hidden here too.
                val visible = if (!parental.hiding) all else all.groupBy { it.providerId }.flatMap { (pid, list) ->
                    val adult = parental.adultIds(db.content().categories(pid))
                    list.filter { !parental.hidden(it, adult) }
                }
                val groups = ArrayList<CatchUpGroup>()
                if (visible.isNotEmpty()) groups += CatchUpGroup("all", "All channels", visible)
                for (pid in ids) {
                    val cats = db.content().categories(pid).associateBy { it.remoteId }
                    visible.filter { it.providerId == pid }.groupBy { it.categoryRemoteId }.forEach { (cat, chans) ->
                        val name = cat?.let { cats[it]?.name } ?: return@forEach
                        groups += CatchUpGroup("$pid/$cat", name, chans)
                    }
                }
                _ui.update { it.copy(loading = false, groups = groups, group = it.group ?: groups.firstOrNull()?.key) }
            }
        }
        viewModelScope.launch { query.debounce(400).collect { search(it) } }
    }

    fun pickGroup(key: String) = _ui.update { it.copy(group = key, channel = null, programmes = emptyList()) }

    fun pickChannel(c: ChannelEntity) { _ui.update { it.copy(channel = c, day = 0) }; loadDay() }

    fun pickDay(d: Int) { _ui.update { it.copy(day = d) }; loadDay() }

    fun back() = _ui.update { if (it.channel != null) it.copy(channel = null, programmes = emptyList()) else it.copy(group = null) }

    private fun loadDay() {
        val c = _ui.value.channel ?: return
        val key = c.epgChannelId
        val (from, to) = dayRange(_ui.value.day)
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val list = if (key == null) emptyList() else
                guide.range(c.providerId, key, from, minOf(to, now)).filter { repo.hasCatchUp(c, it, now) }
            _ui.update { it.copy(programmes = list.sortedByDescending { p -> p.start }) }
        }
    }

    private suspend fun search(q: String) {
        if (q.trim().length < 2) { _ui.update { it.copy(hits = null) }; return }
        val chans = _ui.value.groups.firstOrNull()?.channels.orEmpty()
        if (chans.isEmpty()) { _ui.update { it.copy(hits = emptyList()) }; return }
        val now = System.currentTimeMillis()
        val maxDays = chans.maxOf { it.archiveDays }
        val byKey = chans.filter { it.epgChannelId != null }.associateBy { it.providerId to it.epgChannelId!! }
        val found = db.guide().searchPast(chans.map { it.providerId }.distinct(), q.trim(), now, now - maxDays * 86_400_000L)
            .mapNotNull { p -> byKey[p.providerId to p.channelKey]?.let { c -> PastHit(guide.shifted(p), c) } }
            .filter { repo.hasCatchUp(it.channel, it.programme, now) }
        _ui.update { it.copy(hits = found) }
    }

    /** Starts the recording of a programme in the player. */
    fun play(c: ChannelEntity, p: ProgrammeEntity, onReady: () -> Unit) {
        _ui.update { it.copy(message = "Getting the recording…") }
        viewModelScope.launch {
            val r = runCatching { repo.catchUp(c, p) }
            val t = r.getOrNull()
            if (t == null) {
                _ui.update { it.copy(message = r.exceptionOrNull()?.message ?: "This programme isn't available to catch up on.") }
                return@launch
            }
            val name = ChannelNames.display(c.name)
            vodSession.current = com.vantaview.app.data.addon.VodRequest(
                title = p.title,
                subtitle = name + " · " + SimpleDateFormat("EEE d MMM, HH:mm", com.vantaview.app.ui.I18n.locale).format(Date(p.start)),
                streams = listOf(com.vantaview.app.data.addon.AddonStream(t.url, name, "Catch-up", t.headers, 0, null, 0)),
                type = "catchup",
            )
            _ui.update { it.copy(message = null) }
            onReady()
        }
    }

    companion object {
        /** Start and end of the day [daysBack] days ago, local time. */
        fun dayRange(daysBack: Int): Pair<Long, Long> {
            val c = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, -daysBack)
            }
            return c.timeInMillis to c.timeInMillis + 86_400_000L
        }

        fun dayLabel(daysBack: Int): String = when (daysBack) {
            0 -> "Today"
            1 -> "Yesterday"
            else -> SimpleDateFormat("EEE d", com.vantaview.app.ui.I18n.locale).format(Date(dayRange(daysBack).first))
        }
    }
}

/** Catch-up: pick a group, a channel and a day, then a finished programme; or search past programmes. */
@Composable
fun CatchUpScreen(onPlayRecording: () -> Unit, vm: CatchUpViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val time = SimpleDateFormat("HH:mm", Locale.UK)

    BoxWithConstraints(Modifier.fillMaxSize().background(Vanta.Page).padding(horizontal = 16.dp, vertical = 12.dp)) {
        val wide = maxWidth >= 720.dp
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            com.vantaview.app.ui.PageTitle("Catch-up")
            com.vantaview.app.ui.SearchToggle(query, { vm.query.value = it }, "Search past programmes")
            ui.message?.let { Text(it, color = Vanta.Cyan, fontSize = 14.sp) }

            when {
                ui.loading -> Text("Loading…", color = Vanta.TextDim)
                ui.groups.isEmpty() -> Text(
                    "None of your playlists has channels with catch-up. When a provider offers it, those channels show here " +
                        "and you can watch programmes from the last few days.",
                    color = Vanta.TextDim,
                )
                ui.hits != null -> {
                    val hits = ui.hits!!
                    if (hits.isEmpty()) Text("No finished programmes match \"$query\".", color = Vanta.TextDim)
                    else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(hits, key = { "${it.channel.id}-${it.programme.start}" }) { h ->
                            ProgrammeRow(h.programme, ChannelNames.display(h.channel.name) + " · " +
                                SimpleDateFormat("EEE d MMM, HH:mm", com.vantaview.app.ui.I18n.locale).format(Date(h.programme.start))) {
                                vm.play(h.channel, h.programme, onPlayRecording)
                            }
                        }
                    }
                }
                wide -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Groups(ui, vm, Modifier.width(220.dp).fillMaxHeight())
                    Channels(ui, vm, Modifier.width(280.dp).fillMaxHeight())
                    Programmes(ui, vm, time, onPlayRecording, Modifier.weight(1f).fillMaxHeight())
                }
                // A phone: one step at a time.
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ui.group != null) FocusCard(onClick = vm::back) {
                        Text("‹ " + (if (ui.channel != null) ui.groups.firstOrNull { it.key == ui.group }?.name.orEmpty() else "Groups"), color = Vanta.Text)
                    }
                    when {
                        ui.channel != null -> Programmes(ui, vm, time, onPlayRecording, Modifier.fillMaxSize())
                        ui.group != null -> Channels(ui, vm, Modifier.fillMaxSize())
                        else -> Groups(ui, vm, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun Groups(ui: CatchUpUi, vm: CatchUpViewModel, modifier: Modifier) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(ui.groups, key = { it.key }) { g ->
            FocusCard(onClick = { vm.pickGroup(g.key) }, selected = ui.group == g.key, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(g.name, color = if (ui.group == g.key) Vanta.Cyan else Vanta.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${g.channels.size}", color = Vanta.TextDim, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun Channels(ui: CatchUpUi, vm: CatchUpViewModel, modifier: Modifier) {
    val list = ui.groups.firstOrNull { it.key == ui.group }?.channels.orEmpty()
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(list, key = { it.id }) { c ->
            FocusCard(onClick = { vm.pickChannel(c) }, selected = ui.channel?.id == c.id, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(width = 44.dp, height = 30.dp).clip(Vanta.Shape).background(Vanta.Bg)) {
                        c.logo?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(ChannelNames.display(c.name), color = if (ui.channel?.id == c.id) Vanta.Cyan else Vanta.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${c.archiveDays} day${if (c.archiveDays == 1) "" else "s"} back", color = Vanta.TextDim, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Programmes(ui: CatchUpUi, vm: CatchUpViewModel, time: SimpleDateFormat, onPlay: () -> Unit, modifier: Modifier) {
    val c = ui.channel
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (c == null) { Text("Pick a channel.", color = Vanta.TextDim); return@Column }
        Text(ChannelNames.display(c.name), color = Vanta.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items((0 until minOf(c.archiveDays + 1, 15)).toList()) { d ->
                FocusCard(onClick = { vm.pickDay(d) }, selected = ui.day == d) {
                    Text(CatchUpViewModel.dayLabel(d), color = if (ui.day == d) Vanta.Cyan else Vanta.Text, maxLines = 1)
                }
            }
        }
        if (ui.programmes.isEmpty()) Text(
            if (c.epgChannelId == null) "This channel has no TV guide, so its programmes can't be listed." else "Nothing to catch up on for this day.",
            color = Vanta.TextDim,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(ui.programmes, key = { it.start }) { p ->
                ProgrammeRow(p, time.format(Date(p.start)) + "–" + time.format(Date(p.stop))) { vm.play(c, p, onPlay) }
            }
        }
    }
}

@Composable
private fun ProgrammeRow(p: ProgrammeEntity, line: String, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(p.title, color = Vanta.Text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("▶ $line", color = Vanta.TextDim, fontSize = 13.sp, maxLines = 1)
            p.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, color = Vanta.TextDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
