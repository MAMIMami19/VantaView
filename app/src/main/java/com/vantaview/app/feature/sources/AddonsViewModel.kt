// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vantaview.app.data.addon.AddonRepository
import com.vantaview.app.data.addon.InstalledAddon
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface RestoreUi {
    data object Idle : RestoreUi
    data class StremioLogin(val email: String = "", val error: String? = null) : RestoreUi
    data class Loading(val text: String) : RestoreUi
    data class Pick(val title: String, val items: List<AddonRepository.RemoteAddon>, val selected: Set<String>) : RestoreUi
    data class Done(val text: String, val isError: Boolean) : RestoreUi
}

data class AddonForm(
    val url: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

@HiltViewModel
class AddonsViewModel @Inject constructor(private val repo: AddonRepository) : ViewModel() {
    val addons: StateFlow<List<InstalledAddon>> =
        repo.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _form = MutableStateFlow(AddonForm())
    val form: StateFlow<AddonForm> = _form

    fun editUrl(v: String) = _form.update { it.copy(url = v, message = null, isError = false) }

    fun install(url: String = _form.value.url) {
        if (_form.value.busy) return
        _form.update { it.copy(busy = true, message = "Installing…", isError = false) }
        viewModelScope.launch {
            try {
                val a = repo.install(url)
                _form.value = AddonForm(message = "Installed ${a.manifest.name}.")
            } catch (e: Exception) {
                _form.update { it.copy(busy = false, message = e.message ?: "Couldn't install that add-on", isError = true) }
            }
        }
    }

    fun toggle(a: InstalledAddon) = viewModelScope.launch { repo.setEnabled(a.entity.id, !a.entity.enabled) }

    // ---- Restore ----

    private val _restore = MutableStateFlow<RestoreUi>(RestoreUi.Idle)
    val restore: StateFlow<RestoreUi> = _restore

    fun startStremio() { _restore.value = RestoreUi.StremioLogin() }

    fun cancelRestore() { _restore.value = RestoreUi.Idle }

    fun signInStremio(email: String, password: String) {
        _restore.value = RestoreUi.Loading("Signing in to Stremio…")
        viewModelScope.launch {
            try {
                val list = repo.stremioAccountAddons(email, password)
                _restore.value = pick("Your Stremio add-ons", list)
            } catch (e: Exception) {
                _restore.value = RestoreUi.StremioLogin(email, e.message ?: "Couldn't sign in")
            }
        }
    }

    fun loadBackup(text: String) {
        viewModelScope.launch {
            try {
                _restore.value = pick("Add-ons in the backup", repo.readBackup(text))
            } catch (e: Exception) {
                _restore.value = RestoreUi.Done(e.message ?: "Couldn't read that backup", isError = true)
            }
        }
    }

    private fun pick(title: String, list: List<AddonRepository.RemoteAddon>): RestoreUi =
        if (list.isEmpty()) RestoreUi.Done("No add-ons found.", isError = true)
        else RestoreUi.Pick(title, list, list.filter { !it.alreadyInstalled }.map { it.url }.toSet())

    fun togglePick(url: String) {
        val p = _restore.value as? RestoreUi.Pick ?: return
        _restore.value = p.copy(selected = if (url in p.selected) p.selected - url else p.selected + url)
    }

    fun importSelected() {
        val p = _restore.value as? RestoreUi.Pick ?: return
        val chosen = p.items.filter { it.url in p.selected }
        if (chosen.isEmpty()) { _restore.value = RestoreUi.Idle; return }
        viewModelScope.launch {
            val failed = ArrayList<String>()
            chosen.forEachIndexed { i, a ->
                _restore.value = RestoreUi.Loading("Installing ${i + 1} of ${chosen.size}: ${a.name}")
                runCatching { repo.install(a.url) }.onFailure { failed += "${a.name} (${it.message})" }
            }
            val ok = chosen.size - failed.size
            _restore.value = RestoreUi.Done(
                "Restored $ok of ${chosen.size} add-ons." + if (failed.isNotEmpty()) "\nNot restored: " + failed.joinToString("; ") else "",
                isError = failed.isNotEmpty() && ok == 0,
            )
        }
    }

    suspend fun backupText(): String = repo.backupJson()

    fun showMessage(text: String, isError: Boolean = false) { _restore.value = RestoreUi.Done(text, isError) }

    fun remove(a: InstalledAddon) = viewModelScope.launch { repo.remove(a.entity.id) }

    companion object {
        /** Stremio's own catalogue and information add-on. It has no streams. */
        const val CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"
    }
}
