// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vantaview.app.data.db.ProviderEntity
import com.vantaview.app.data.repo.SyncState
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta
import com.vantaview.app.ui.VantaLogo

/** Playlist list and add-playlist form, shown on the Sources page. */
@Composable
fun PlaylistsContent(vm: PlaylistsViewModel = hiltViewModel()) {
    val providers by vm.providers.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            providers.forEach { p -> ProviderCard(p, sync[p.id], onRefresh = { vm.refresh(p.id) }, onCountries = { vm.editCountries(p.id) }, onDelete = { vm.delete(p.id) }) }
            if (providers.isEmpty()) {
                Text("No playlists yet. Add one below to get started.", color = Vanta.TextDim)
            }

            if (!vm.hasFreeTv(providers)) FreeTvCard(onAdd = vm::addFreeTv)

            Spacer(Modifier.height(6.dp))
            Text("Add a playlist", color = Vanta.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceTab.entries.forEach { tab ->
                    FocusCard(onClick = { vm.edit { it.copy(tab = tab) } }, selected = form.tab == tab) {
                        Text(
                            tab.label,
                            color = if (form.tab == tab) Vanta.Cyan else Vanta.Text,
                            fontWeight = if (form.tab == tab) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }

            when (form.tab) {
                SourceTab.SMART -> {
                    Text(
                        "Paste the message or link your provider sent you. VantaView will pick out the details, including a portal address and MAC address.",
                        color = Vanta.TextDim,
                    )
                    Field("Provider message or link", form.smartText, singleLine = false, minLines = 4) { v ->
                        vm.edit { it.copy(smartText = v) }
                    }
                    GradientButton("Find details", onClick = vm::detect, enabled = form.smartText.isNotBlank())
                }
                SourceTab.M3U -> {
                    Field("Playlist name (optional)", form.name) { v -> vm.edit { it.copy(name = v) } }
                    Field("M3U link", form.m3uUrl, keyboard = KeyboardType.Uri) { v -> vm.edit { it.copy(m3uUrl = v) } }
                    GradientButton("Add playlist", onClick = vm::add, enabled = form.m3uUrl.isNotBlank())
                }
                SourceTab.XTREAM -> {
                    Field("Playlist name (optional)", form.name) { v -> vm.edit { it.copy(name = v) } }
                    Field("Server address", form.server, keyboard = KeyboardType.Uri) { v -> vm.edit { it.copy(server = v) } }
                    Field("Username", form.username) { v -> vm.edit { it.copy(username = v) } }
                    Field("Password", form.password, password = true) { v -> vm.edit { it.copy(password = v) } }
                    GradientButton(
                        "Add playlist", onClick = vm::add,
                        enabled = form.server.isNotBlank() && form.username.isNotBlank() && form.password.isNotBlank(),
                    )
                }
                SourceTab.STALKER -> {
                    Text(
                        "For a MAG-style portal subscription. Use the portal address and the MAC address your provider registered for you.",
                        color = Vanta.TextDim,
                    )
                    Field("Portal name (optional)", form.name) { v -> vm.edit { it.copy(name = v) } }
                    Field("Portal address (like http://example.com:8080/c/)", form.portal, keyboard = KeyboardType.Uri) { v -> vm.edit { it.copy(portal = v) } }
                    Field("MAC address (like 00:1A:79:12:34:56)", form.mac, keyboard = KeyboardType.Ascii) { v -> vm.edit { it.copy(mac = v) } }
                    FocusCard(onClick = { vm.edit { it.copy(boxOpen = !it.boxOpen) } }) {
                        Text((if (form.boxOpen) "▾ " else "▸ ") + "Advanced: box details (optional)", color = Vanta.Text)
                    }
                    if (form.boxOpen) {
                        Text(
                            "Only needed if your provider locks your subscription to one MAG box. Copy these from that box " +
                                "(Settings › About on most MAG boxes). Leave them empty otherwise.",
                            color = Vanta.TextDim, fontSize = 13.sp,
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            com.vantaview.app.data.stalker.StalkerClient.Box.MODELS.forEach { m ->
                                FocusCard(onClick = { vm.edit { it.copy(model = m) } }, selected = form.model == m) {
                                    Text(m, color = if (form.model == m) Vanta.Cyan else Vanta.Text, fontWeight = if (form.model == m) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                        Field("Serial number", form.serial) { v -> vm.edit { it.copy(serial = v) } }
                        Field("Device ID", form.deviceId) { v -> vm.edit { it.copy(deviceId = v) } }
                        Field("Device ID 2", form.deviceId2) { v -> vm.edit { it.copy(deviceId2 = v) } }
                        Field("Signature", form.signature) { v -> vm.edit { it.copy(signature = v) } }
                    }
                    GradientButton("Add portal", onClick = vm::add, enabled = form.portal.isNotBlank() && form.mac.isNotBlank())
                }
            }

            form.message?.let { Text(it, color = if (form.isError) Vanta.Error else Vanta.Cyan) }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    singleLine: Boolean = true,
    minLines: Int = 1,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ProviderCard(p: ProviderEntity, state: SyncState?, onRefresh: () -> Unit, onCountries: () -> Unit, onDelete: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Vanta.Surface, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, color = Vanta.Text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (p.kind) { "XTREAM" -> "Xtream Codes"; "STALKER" -> "Stalker portal · live TV, films and series"; else -> "M3U playlist" },
                    color = Vanta.TextDim, fontSize = 13.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (p.channelCount > 0) {
                FocusCard(onClick = onCountries) { Text("Countries", color = Vanta.Text) }
                Spacer(Modifier.width(8.dp))
            }
            FocusCard(onClick = onRefresh) { Text("Refresh", color = Vanta.Text) }
            Spacer(Modifier.width(8.dp))
            FocusCard(onClick = onDelete) { Text("Remove", color = Vanta.Error) }
        }
        val (line, colour) = when (state) {
            is SyncState.Running -> "${state.stage}…" to Vanta.Cyan
            is SyncState.Failed -> state.message to Vanta.Error
            is SyncState.Done ->
                ("${state.channels} channels ready" +
                    if (state.skippedVod > 0) " · ${state.skippedVod} movie/series entries left out for now" else "") to Vanta.TextDim
            null -> when {
                p.lastError != null -> p.lastError to Vanta.Error
                p.channelCount > 0 -> "${p.channelCount} channels" to Vanta.TextDim
                else -> "Not loaded yet" to Vanta.TextDim
            }
        }
        Text(line, color = colour, fontSize = 14.sp)
    }
}

/** Optional free channels from Free TV, added only when the user asks. */
@Composable
fun FreeTvCard(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Vanta.Surface, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Try free channels", color = Vanta.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "Officially free-to-air TV channels from around the world. Pick your countries after adding. " +
                "Some channels only play inside their own country.",
            color = Vanta.TextDim,
        )
        GradientButton("Add free channels", onClick = onAdd)
        Text(
            "List by Free TV (github.com/Free-TV/IPTV). TV guide by epgshare01.online. Thanks to their maintainers and contributors.",
            color = Vanta.TextDim, fontSize = 12.sp,
        )
    }
}
