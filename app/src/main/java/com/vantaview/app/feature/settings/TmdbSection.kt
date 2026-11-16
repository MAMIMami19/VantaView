// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import com.vantaview.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vantaview.app.data.browse.BrowseRepository
import com.vantaview.app.data.browse.TmdbKeyStore
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TmdbViewModel @Inject constructor(
    private val store: TmdbKeyStore,
    private val browse: BrowseRepository,
) : ViewModel() {
    val key: StateFlow<String?> = store.key

    /** True when this build was made with a TMDB key (so details work even without a user key). */
    val hasBuildKey: Boolean get() = browse.hasBuildKey

    val masked: String? get() = store.masked

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking

    private val _error = MutableStateFlow(false)
    val error: StateFlow<Boolean> = _error

    /** Checks the key with TMDB first; only a working key is saved. */
    fun save(key: String, onDone: () -> Unit) {
        viewModelScope.launch {
            _checking.value = true
            _error.value = false
            val ok = runCatching { browse.testKey(key) }.getOrDefault(false)
            _checking.value = false
            if (ok) {
                store.set(key)
                onDone()
            } else {
                _error.value = true
            }
        }
    }

    fun clearError() {
        _error.value = false
    }

    fun remove() = store.clear()
}

/** Settings › TMDB: your own TMDB API key, for film and series details in your language. */
@Composable
fun TmdbSection(vm: TmdbViewModel = hiltViewModel()) {
    val key by vm.key.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Section("TMDB API key", alwaysOpen = true) {
            if (key.isNullOrBlank()) {
                Text(
                    "TMDB gives films and series their posters, descriptions, cast and trailers in your language. " +
                        "Without a key, VantaView uses Cinemeta instead, which only has English.",
                    color = Vanta.TextDim, fontSize = 14.sp,
                )
                if (vm.hasBuildKey) {
                    Text(
                        "This build already has a TMDB key. Yours takes over when you enter one.",
                        color = Vanta.TextDim, fontSize = 14.sp,
                    )
                }
                GradientButton("Enter TMDB API key", onClick = { dialog = true })
            } else {
                Text(
                    com.vantaview.app.ui.tr("Your TMDB key: {0}", vm.masked ?: ""),
                    color = Vanta.Text, fontSize = 14.sp,
                )
                Text("Details come from TMDB in your language.", color = Vanta.TextDim, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FocusCard(onClick = { dialog = true }) { Text("Change key", color = Vanta.Text) }
                    FocusCard(onClick = vm::remove) { Text("Remove", color = Vanta.Error) }
                }
            }
        }
    }
    if (dialog) {
        TmdbKeyDialog(
            checking = checking,
            error = error,
            onSave = { vm.save(it) { dialog = false } },
            onCancel = { dialog = false; vm.clearError() },
        )
    }
}

@Composable
private fun TmdbKeyDialog(checking: Boolean, error: Boolean, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Vanta.Panel,
        title = { Text("TMDB API key", color = Vanta.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Enter your TMDB API key (32 characters) or read access token. Find it free at " +
                        "themoviedb.org/settings/api. It's stored encrypted on this device.",
                    color = Vanta.TextDim, fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = key, onValueChange = { key = it.trim() }, label = { Text("API key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error) Text("That key didn't work. Check it and try again.", color = Vanta.Error, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(key) }, enabled = key.isNotBlank() && !checking) {
                Text(if (checking) "Checking…" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
