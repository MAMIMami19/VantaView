// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vantaview.app.data.profile.ProfileSession
import com.vantaview.app.data.trakt.TraktCode
import com.vantaview.app.data.trakt.TraktLink
import com.vantaview.app.data.trakt.TraktOptions
import com.vantaview.app.data.trakt.TraktState
import com.vantaview.app.data.trakt.TraktSync
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class TraktViewModel @Inject constructor(
    private val trakt: TraktSync,
    session: ProfileSession,
) : ViewModel() {
    val configured: Boolean get() = trakt.api.configured

    /** The profile in use's link, if any (updates on sign-in, option changes and syncs). */
    val link: StateFlow<TraktLink?> = combine(trakt.storeVersion, session.current) { _, p -> trakt.link(p) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, trakt.link())

    val state: StateFlow<TraktState> = trakt.state

    private val _code = MutableStateFlow<TraktCode?>(null)
    /** The code to enter at trakt.tv/activate while signing in. */
    val code: StateFlow<TraktCode?> = _code

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private var signing: Job? = null

    fun signIn() {
        signing?.cancel()
        _message.value = null
        signing = viewModelScope.launch {
            try {
                val c = trakt.startSignIn()
                _code.value = c
                val name = trakt.finishSignIn(c)
                _message.value = if (name.isNotBlank()) "Linked to $name. Syncing now…" else "Linked. Syncing now…"
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) _message.value = e.message ?: "Couldn't link Trakt."
            } finally {
                _code.value = null
            }
        }
    }

    fun cancelSignIn() { signing?.cancel(); _code.value = null }
    fun setOptions(o: TraktOptions) = trakt.setOptions(o)
    fun syncNow() = trakt.syncNow()
    fun unlink() { trakt.unlink(); _message.value = "Trakt unlinked from this profile." }
}

/** Settings › Trakt: link this profile to a Trakt account and choose what stays in step. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TraktSection(vm: TraktViewModel = hiltViewModel()) {
    val link by vm.link.collectAsStateWithLifecycle()
    val code by vm.code.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!vm.configured) {
            Section("Trakt") {
                Text("Trakt isn't set up in this build of VantaView yet.", color = Vanta.TextDim, fontSize = 14.sp)
            }
            return@Column
        }
        val l = link
        if (l == null) {
            Section("Link Trakt") {
                Text(
                    "Trakt keeps track of what you watch across apps. Link this profile to your Trakt account and VantaView " +
                        "keeps your Watchlist, what you've watched and Continue watching in step with it, and adds your " +
                        "Trakt recommendations and lists to Movies and TV Series. Each profile links its own account.",
                    color = Vanta.TextDim, fontSize = 14.sp,
                )
                val c = code
                if (c == null) {
                    GradientButton("Link Trakt", onClick = vm::signIn)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        QrCode(c.url, Modifier.size(132.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("On your phone or computer, go to", color = Vanta.TextDim, fontSize = 14.sp)
                            Text(c.url.removePrefix("https://"), color = Vanta.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text("and enter this code:", color = Vanta.TextDim, fontSize = 14.sp)
                            Text(c.userCode, color = Vanta.Cyan, fontSize = 30.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp)
                            Text("Waiting for you to approve it…", color = Vanta.TextDim, fontSize = 13.sp)
                        }
                    }
                    FocusCard(onClick = vm::cancelSignIn) { Text("Cancel", color = Vanta.Text) }
                }
            }
        } else {
            Section("Trakt") {
                Text(
                    if (l.username.isNotBlank()) "Linked to ${l.username}" else "Linked",
                    color = Vanta.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    if (l.lastSync > 0) "Last synced ${SimpleDateFormat("EEE d MMM, HH:mm", Locale.UK).format(Date(l.lastSync))}. Syncs every half hour while VantaView is open."
                    else "Not synced yet.",
                    color = Vanta.TextDim, fontSize = 13.sp,
                )
                when (val s = state) {
                    is TraktState.Busy -> Text(s.text, color = Vanta.Cyan, fontSize = 14.sp)
                    is TraktState.Done -> Text(s.text, color = Vanta.Cyan, fontSize = 14.sp)
                    is TraktState.Failed -> Text(s.text, color = Vanta.Error, fontSize = 14.sp)
                    TraktState.Idle -> Unit
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FocusCard(onClick = vm::syncNow) { Text("Sync now", color = Vanta.Text, maxLines = 1, softWrap = false) }
                    FocusCard(onClick = vm::unlink) { Text("Unlink", color = Vanta.Error, maxLines = 1, softWrap = false) }
                }
            }
            Section("What stays in step") {
                val o = l.options
                TraktToggle("Watchlist", "Your Trakt watchlist and VantaView's Watchlist match, both ways.", o.watchlist) { vm.setOptions(o.copy(watchlist = it)) }
                TraktToggle("What you've watched", "Films and episodes marked watched, both ways.", o.history) { vm.setOptions(o.copy(history = it)) }
                TraktToggle(
                    "Continue watching", "VantaView tells Trakt what you're watching and where you stopped, and picks up where you left off in other apps.",
                    o.progress,
                ) { vm.setOptions(o.copy(progress = it)) }
                TraktToggle("Recommendations and lists", "Rows on Movies and TV Series from your Trakt recommendations and your own lists.", o.lists) {
                    vm.setOptions(o.copy(lists = it))
                }
            }
        }
        message?.let { Text(it, color = Vanta.Cyan, fontSize = 14.sp) }
        Text("Uses the Trakt API. VantaView isn't made or endorsed by Trakt.", color = Vanta.TextDim, fontSize = 11.sp)
    }
}

@Composable
private fun TraktToggle(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Vanta.Text)
            Text(detail, color = Vanta.TextDim, fontSize = 13.sp)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}
