// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vantaview.app.data.backup.BackupInfo
import com.vantaview.app.data.backup.BackupManager
import com.vantaview.app.data.backup.BackupShareServer
import com.vantaview.app.data.backup.RestoreMode
import com.vantaview.app.data.backup.StoredBackup
import com.vantaview.app.data.backup.WrongPasswordException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUi(
    val busy: String? = null,
    val message: String? = null,
    val isError: Boolean = false,
    val autoEnabled: Boolean = false,
    val hasPassword: Boolean = false,
    val folderName: String? = null,
    val stored: List<StoredBackup> = emptyList(),
    /** A backup that has been read and is waiting for the user to choose how to restore it. */
    val pending: Pending? = null,
    /** Address a phone can download the backup from (shown as a QR code). */
    val shareUrl: String? = null,
)

data class Pending(val text: String, val info: BackupInfo, val wrongPassword: Boolean = false)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val backup: BackupManager,
    private val weather: com.vantaview.app.data.weather.WeatherRepository,
    val playback: com.vantaview.app.data.PlaybackSettings,
) : ViewModel() {
    // ---- Weather ----
    data class WeatherUi(val enabled: Boolean, val fahrenheit: Boolean, val place: String?, val usingDevice: Boolean)

    private val _weatherUi = MutableStateFlow(weatherNow())
    val weatherUi: StateFlow<WeatherUi> = _weatherUi
    private val _places = MutableStateFlow<List<com.vantaview.app.data.weather.Place>>(emptyList())
    val places: StateFlow<List<com.vantaview.app.data.weather.Place>> = _places

    private fun weatherNow() = WeatherUi(
        enabled = weather.enabled, fahrenheit = weather.fahrenheit,
        place = weather.place?.let { p -> listOf(p.name, p.area).filter { it.isNotBlank() }.joinToString(", ") },
        usingDevice = weather.place == null && weather.hasLocationPermission(),
    )
    private fun weatherChanged() {
        _weatherUi.value = weatherNow()
        viewModelScope.launch { weather.refresh(force = true) }
    }
    fun setWeatherOn(on: Boolean) { weather.enabled = on; weatherChanged() }
    fun setFahrenheit(on: Boolean) { weather.fahrenheit = on; weatherChanged() }
    fun useDeviceLocation() { weather.place = null; weatherChanged() }
    fun searchPlaces(q: String) { viewModelScope.launch { _places.value = weather.search(q) } }
    fun pickPlace(p: com.vantaview.app.data.weather.Place) { weather.place = p; _places.value = emptyList(); weatherChanged() }

    private val _ui = MutableStateFlow(SettingsUi())
    val ui: StateFlow<SettingsUi> = _ui
    val lastAuto: StateFlow<Long> = backup.lastAuto
    val rememberedPassword: String? get() = backup.rememberedPassword

    /** Text of the backup about to be saved through the file picker. */
    var prepared: String? = null
        private set
    private var share: BackupShareServer? = null

    init { refresh() }

    private fun refresh() = _ui.update {
        it.copy(
            autoEnabled = backup.autoEnabled,
            hasPassword = backup.rememberedPassword != null,
            folderName = backup.folder?.lastPathSegment?.substringAfterLast(':')?.ifBlank { null },
            stored = runCatching { backup.stored() }.getOrDefault(emptyList()),
        )
    }

    private fun say(msg: String, error: Boolean = false) = _ui.update { it.copy(message = msg, isError = error, busy = null) }

    fun fileName() = "vantaview-backup-${backup.stamp()}.vvbackup"

    /** Builds a backup in memory, then [onReady] runs (the screen opens the file picker or the QR code). */
    fun prepare(password: String, remember: Boolean, onReady: () -> Unit) {
        if (remember) backup.rememberedPassword = password
        _ui.update { it.copy(busy = "Making the backup…", message = null) }
        viewModelScope.launch {
            try {
                prepared = backup.build(password.ifEmpty { null })
                _ui.update { it.copy(busy = null) }
                refresh()
                onReady()
            } catch (e: Exception) {
                say(e.message ?: "The backup couldn't be made", true)
            }
        }
    }

    fun writeTo(uri: Uri, context: android.content.Context) {
        val text = prepared ?: return
        viewModelScope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("no stream")
            }.onSuccess { say("Backup saved.") }.onFailure { say("The backup couldn't be saved there", true) }
        }
    }

    /** For TVs with no file picker: save into the app's own folder. */
    fun saveInAppFolder() {
        val text = prepared ?: return
        viewModelScope.launch {
            runCatching { backup.writeToFolder(fileName(), text) }
                .onSuccess { where -> say("Backup saved in $where."); refresh() }
                .onFailure { say(it.message ?: "The backup couldn't be saved", true) }
        }
    }

    fun shareToPhone() {
        val text = prepared ?: return
        share?.stop()
        val s = BackupShareServer(fileName(), text.toByteArray())
        val url = runCatching { s.start() }.getOrNull()
        if (url == null) { say("This device isn't on a Wi-Fi or wired network, so it can't send to a phone.", true); return }
        share = s
        _ui.update { it.copy(shareUrl = url) }
    }

    fun stopSharing() {
        share?.stop(); share = null
        _ui.update { it.copy(shareUrl = null) }
    }

    // ---- Restore ----

    fun open(uri: Uri) {
        _ui.update { it.copy(busy = "Reading the backup…", message = null) }
        viewModelScope.launch {
            try {
                val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { backup.read(uri) }
                val info = backup.inspect(text)
                _ui.update { it.copy(busy = null, pending = Pending(text, info)) }
            } catch (e: Exception) {
                say(e.message ?: "That file couldn't be read", true)
            }
        }
    }

    fun cancelRestore() = _ui.update { it.copy(pending = null) }

    fun restore(password: String, mode: RestoreMode) {
        val p = _ui.value.pending ?: return
        _ui.update { it.copy(busy = "Restoring…") }
        viewModelScope.launch {
            try {
                backup.restore(p.text, password.ifEmpty { null }, mode)
                _ui.update { it.copy(pending = null) }
                say(
                    if (p.info.locked && password.isEmpty()) "Restored without logins. Add your playlist passwords again in Sources."
                    else "Restored. Your playlists are downloading again."
                )
                refresh()
            } catch (e: WrongPasswordException) {
                _ui.update { it.copy(busy = null, pending = p.copy(wrongPassword = true)) }
            } catch (e: Exception) {
                say(e.message ?: "The backup couldn't be restored", true)
            }
        }
    }

    // ---- Automatic backups ----

    fun setAuto(on: Boolean, password: String? = null) {
        if (password != null) backup.rememberedPassword = password
        if (on && backup.rememberedPassword == null) return
        backup.autoEnabled = on
        refresh()
        if (on) viewModelScope.launch {
            runCatching { backup.saveAutomatic() }
                .onSuccess { say("Automatic backups are on. The first one is saved."); refresh() }
                .onFailure { say(it.message ?: "The first automatic backup couldn't be saved", true) }
        }
    }

    fun forgetPassword() {
        backup.rememberedPassword = null
        backup.autoEnabled = false
        refresh()
        say("Backup password forgotten. Automatic backups are off.")
    }

    fun setFolder(uri: Uri?) {
        backup.folder = uri
        refresh()
    }

    override fun onCleared() { share?.stop() }
}
