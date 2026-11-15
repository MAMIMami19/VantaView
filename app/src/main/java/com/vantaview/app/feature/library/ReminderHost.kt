// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.library

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vantaview.app.data.library.LibraryStore
import com.vantaview.app.data.library.Reminder
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta
import kotlinx.coroutines.delay

/** While VantaView is open: pops up "starts in 5 minutes" for each reminder that's due. */
@Composable
fun ReminderHost(library: LibraryStore, onWatch: (Long) -> Unit) {
    var showing by remember { mutableStateOf<Reminder?>(null) }
    val shown = remember { mutableSetOf<String>() }
    LaunchedEffect(Unit) {
        while (true) {
            if (showing == null) {
                val now = System.currentTimeMillis()
                showing = library.remindersNow().firstOrNull { it.key !in shown && now >= it.alertAt && now < it.start + 2 * 60_000L }
                showing?.let { shown += it.key }
            }
            delay(10_000)
        }
    }
    showing?.let { r ->
        val mins = ((r.start - System.currentTimeMillis()) / 60_000L).toInt()
        AlertDialog(
            onDismissRequest = { showing = null },
            containerColor = Vanta.Panel,
            title = { Text(r.title, color = Vanta.Text) },
            text = {
                Text(
                    (if (mins <= 0) "Starting now" else "Starts in $mins minute${if (mins == 1) "" else "s"}") + " on ${r.channelName}.",
                    color = Vanta.TextDim,
                )
            },
            confirmButton = { GradientButton("Watch now", onClick = { showing = null; library.removeReminder(r); onWatch(r.channelId) }) },
            dismissButton = { TextButton(onClick = { showing = null }) { Text("Dismiss") } },
        )
    }
}
