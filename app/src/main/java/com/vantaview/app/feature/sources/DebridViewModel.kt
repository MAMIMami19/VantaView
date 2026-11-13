// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vantaview.app.data.debrid.CodeSignIn
import com.vantaview.app.data.debrid.DebridAccount
import com.vantaview.app.data.debrid.DebridManager
import com.vantaview.app.data.debrid.DebridService
import com.vantaview.app.data.debrid.DebridStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ConnectUi {
    data object Idle : ConnectUi
    data class EnterKey(val service: DebridService, val error: String? = null, val busy: Boolean = false) : ConnectUi
    data class Code(val service: DebridService, val code: CodeSignIn) : ConnectUi
    data class Message(val text: String, val isError: Boolean) : ConnectUi
}

@HiltViewModel
class DebridViewModel @Inject constructor(
    private val store: DebridStore,
    private val manager: DebridManager,
) : ViewModel() {
    val accounts: StateFlow<List<DebridAccount>> = store.accounts

    private val _connect = MutableStateFlow<ConnectUi>(ConnectUi.Idle)
    val connect: StateFlow<ConnectUi> = _connect
    private var poll: Job? = null

    fun enterKey(s: DebridService) { poll?.cancel(); _connect.value = ConnectUi.EnterKey(s) }

    fun saveKey(s: DebridService, key: String) {
        _connect.value = ConnectUi.EnterKey(s, busy = true)
        viewModelScope.launch {
            _connect.value = try {
                val a = manager.connectWithKey(s, key)
                ConnectUi.Message("Connected ${s.label}" + (a.label.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + ".", false)
            } catch (e: Exception) {
                ConnectUi.EnterKey(s, error = e.message ?: "That key didn't work")
            }
        }
    }

    /** Shows a code to enter on the service's website, then waits for the user to approve it. */
    fun startCode(s: DebridService) {
        poll?.cancel()
        poll = viewModelScope.launch {
            try {
                val code = when (s) {
                    DebridService.REAL_DEBRID -> manager.realDebrid.startCode()
                    DebridService.ALL_DEBRID -> manager.allDebrid.startCode()
                    else -> return@launch enterKey(s)
                }
                _connect.value = ConnectUi.Code(s, code)
                while (System.currentTimeMillis() < code.expiresAt) {
                    delay(code.intervalSec * 1000L)
                    val acc = runCatching {
                        when (s) {
                            DebridService.REAL_DEBRID -> manager.realDebrid.pollCode(code)
                            else -> manager.allDebrid.pollCode(code)
                        }
                    }.getOrNull()
                    if (acc != null) {
                        store.put(acc)
                        _connect.value = ConnectUi.Message("Connected ${s.label}" + (acc.label.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + ".", false)
                        return@launch
                    }
                }
                _connect.value = ConnectUi.Message("The ${s.label} code expired. Try again.", true)
            } catch (e: Exception) {
                _connect.value = ConnectUi.Message(e.message ?: "Couldn't start ${s.label} sign-in", true)
            }
        }
    }

    fun cancel() { poll?.cancel(); _connect.value = ConnectUi.Idle }

    fun remove(s: DebridService) = store.remove(s)

    fun move(s: DebridService, delta: Int) = store.move(s, delta)

    fun check(s: DebridService) {
        viewModelScope.launch {
            _connect.value = try {
                val info = manager.refreshInfo(s)
                ConnectUi.Message(
                    "${s.label}: " + when {
                        info == null -> "not connected"
                        info.premium -> "premium account working"
                        else -> "connected, but not premium. Debrid playback needs a premium account."
                    },
                    info?.premium != true,
                )
            } catch (e: Exception) {
                ConnectUi.Message(e.message ?: "${s.label} check failed", true)
            }
        }
    }
}
