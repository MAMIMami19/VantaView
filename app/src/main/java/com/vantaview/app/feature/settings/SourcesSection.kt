// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import coil.imageLoader
import com.vantaview.app.data.RefreshEvery
import com.vantaview.app.data.SourceSettings
import com.vantaview.app.data.country.CountryStore
import com.vantaview.app.data.db.ProviderEntity
import com.vantaview.app.data.guide.GuideRepository
import com.vantaview.app.data.repo.ProviderRepository
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject

/** One kind of saved data and how much space it takes. */
data class StorageUse(val label: String, val bytes: Long)

@HiltViewModel
class SourcesStorageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val settings: SourceSettings,
    private val providers: ProviderRepository,
    private val guide: GuideRepository,
    private val countries: CountryStore,
) : ViewModel() {
    val playlists: StateFlow<List<ProviderEntity>> =
        providers.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun freeTv(list: List<ProviderEntity>) = list.firstOrNull(providers::isFreeTv)

    fun addFreeTv() { viewModelScope.launch { providers.addFreeTv() } }
    fun removeFreeTv(p: ProviderEntity) { viewModelScope.launch { providers.delete(p.id); measure() } }
    fun chooseCountries(p: ProviderEntity) { countries.editRequest.value = p.id }
    fun refreshAllNow() { playlists.value.forEach { providers.sync(it.id) } }

    private val _usage = MutableStateFlow<List<StorageUse>?>(null)
    val usage: StateFlow<List<StorageUse>?> = _usage
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private fun size(f: File?): Long = when {
        f == null || !f.exists() -> 0
        f.isFile -> f.length()
        else -> f.listFiles()?.sumOf { size(it) } ?: 0
    }

    private val pictures get() = File(context.cacheDir, "image_cache")
    private val updates get() = File(context.cacheDir, "updates")
    private val aiModels get() = File(context.filesDir, "aisubs")
    private val backups get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups")

    fun measure() {
        viewModelScope.launch {
            _usage.value = withContext(Dispatchers.IO) {
                val dbDir = context.getDatabasePath("vanta.db").parentFile
                val db = dbDir?.listFiles { f -> f.name.startsWith("vanta.db") }?.sumOf { it.length() } ?: 0
                listOf(
                    StorageUse("Channels and TV guide", db),
                    StorageUse("Pictures (posters and logos)", size(pictures)),
                    StorageUse("AI subtitle models", size(aiModels)),
                    StorageUse("Backups saved on this device", size(backups)),
                    StorageUse("Update downloads", size(updates)),
                )
            }
        }
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearPictures() = clear("Pictures cleared. They download again as you browse.") {
        context.imageLoader.memoryCache?.clear()
        context.imageLoader.diskCache?.clear()
    }

    fun clearGuide() = clear("TV guide cleared. It loads again next time you open Live TV or the Guide.") { guide.clearAll() }

    fun clearUpdates() = clear("Update downloads cleared.") { updates.deleteRecursively() }

    private fun clear(done: String, work: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { work() } }
                .onSuccess { _message.value = done }
                .onFailure { _message.value = "That couldn't be cleared." }
            measure()
        }
    }
}

/** Settings › Sources and storage. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourcesSection(vm: SourcesStorageViewModel = hiltViewModel()) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var playlistEvery by remember { mutableStateOf(vm.settings.playlists) }
    var guideEvery by remember { mutableStateOf(vm.settings.guide) }
    var confirmRemove by remember { mutableStateOf<ProviderEntity?>(null) }
    LaunchedEffect(Unit) { vm.measure() }
    val free = vm.freeTv(playlists)

    Section("Refresh playlists") {
        Text(
            "When VantaView starts, playlists older than this download their channels again.",
            color = Vanta.TextDim, fontSize = 13.sp,
        )
        Choices(RefreshEvery.entries, playlistEvery) { playlistEvery = it; vm.settings.playlists = it }
        if (playlists.isNotEmpty()) FocusCard(onClick = vm::refreshAllNow) { Text("Refresh all playlists now", color = Vanta.Text) }
    }

    Section("Refresh the TV guide") {
        Text("The guide loads again when it's older than this. Never means only when you refresh it.", color = Vanta.TextDim, fontSize = 13.sp)
        Choices(listOf(RefreshEvery.HALF_DAY, RefreshEvery.DAILY, RefreshEvery.NEVER), guideEvery) { guideEvery = it; vm.settings.guide = it }
    }

    Section("Free TV channels") {
        Text(
            "Officially free channels from around the world, from the Free TV list (github.com/Free-TV/IPTV), with a TV guide from epgshare01.",
            color = Vanta.TextDim, fontSize = 13.sp,
        )
        if (free == null) GradientButton("Add free channels", onClick = vm::addFreeTv)
        else {
            Text("Added · ${"%,d".format(Locale.UK, free.channelCount)} channels", color = Vanta.Cyan, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusCard(onClick = { vm.chooseCountries(free) }) { Text("Choose countries", color = Vanta.Text) }
                FocusCard(onClick = { confirmRemove = free }) { Text("Remove", color = Vanta.Text) }
            }
        }
    }

    Section("Storage") {
        val list = usage
        if (list == null) Text("Working out space used…", color = Vanta.TextDim)
        else {
            list.forEach { u ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(u.label, color = Vanta.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(sizeText(u.bytes), color = Vanta.TextDim, fontSize = 14.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Total", color = Vanta.Text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(sizeText(list.sumOf { it.bytes }), color = Vanta.Text, fontWeight = FontWeight.Bold)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FocusCard(onClick = vm::clearPictures) { Text("Clear pictures", color = Vanta.Text) }
            FocusCard(onClick = vm::clearGuide) { Text("Clear TV guide", color = Vanta.Text) }
            FocusCard(onClick = vm::clearUpdates) { Text("Clear update downloads", color = Vanta.Text) }
        }
        Text("AI subtitle models are deleted in Subtitles and audio; backups in Backup and restore.", color = Vanta.TextDim, fontSize = 12.sp)
        message?.let { Text(it, color = Vanta.Cyan, fontSize = 13.sp) }
    }

    confirmRemove?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            containerColor = Vanta.Surface,
            title = { Text("Remove free channels?", color = Vanta.Text) },
            text = { Text("Free TV's channels, favourites and guide go from this profile. You can add them again any time.", color = Vanta.TextDim) },
            confirmButton = { GradientButton("Remove", onClick = { vm.removeFreeTv(p); confirmRemove = null }) },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(options: List<RefreshEvery>, chosen: RefreshEvery, onPick: (RefreshEvery) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FocusCard(onClick = { onPick(o) }, selected = o == chosen) {
                Text(o.label, color = if (o == chosen) Vanta.Cyan else Vanta.Text, fontWeight = if (o == chosen) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
            }
        }
    }
}

private fun sizeText(b: Long): String = when {
    b >= 1_073_741_824L -> "%.1f GB".format(Locale.UK, b / 1_073_741_824.0)
    b >= 1_048_576L -> "%.1f MB".format(Locale.UK, b / 1_048_576.0)
    b >= 1024L -> "%.0f KB".format(Locale.UK, b / 1024.0)
    else -> "$b B"
}
