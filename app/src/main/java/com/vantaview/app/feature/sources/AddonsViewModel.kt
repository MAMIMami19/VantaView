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

    fun remove(a: InstalledAddon) = viewModelScope.launch { repo.remove(a.entity.id) }

    companion object {
        /** Stremio's own catalogue and information add-on. It has no streams. */
        const val CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"
    }
}
