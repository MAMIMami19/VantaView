// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vantaview.app.data.BufferSize
import com.vantaview.app.data.PlaybackSettings
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta

/** Settings › Playback. Changes apply the next time a video starts. */
@Composable
fun PlaybackSection(s: PlaybackSettings) {
    var buffer by remember { mutableStateOf(s.buffer) }
    var controls by remember { mutableStateOf(s.controlsSeconds) }
    var mini by remember { mutableStateOf(s.miniPlayer) }
    var gestures by remember { mutableStateOf(s.gestures) }
    var startLast by remember { mutableStateOf(s.startOnLastChannel) }
    var rememberSize by remember { mutableStateOf(s.rememberSize) }
    var remindMins by remember { mutableStateOf(s.reminderMinutes) }

    Section("Buffer size") {
        Choices(BufferSize.entries, buffer, { it.label }) { buffer = it; s.buffer = it }
        Text(buffer.detail, color = Vanta.TextDim, fontSize = 13.sp)
    }

    Section("Player controls") {
        Text("How long the controls stay on screen", color = Vanta.Text)
        Choices(listOf(3, 5, 10, 0), controls, { if (it == 0) "Always" else "$it seconds" }) { controls = it; s.controlsSeconds = it }
        if (controls == 0) Text("They stay until you hide them (tap, or press OK or Back).", color = Vanta.TextDim, fontSize = 13.sp)
    }

    Section("Live TV") {
        Toggle("Mini player on Home", "A small, muted preview of the last channel you watched", mini) { mini = it; s.miniPlayer = it }
        Toggle("Start on last channel", "Open straight into the last channel when VantaView starts", startLast) { startLast = it; s.startOnLastChannel = it }
        Toggle(
            "Remember picture size",
            "Keep Fit, Fill or Zoom between channels. Change it with the Size button, or Menu on a remote.",
            rememberSize,
        ) { rememberSize = it; s.rememberSize = it }
    }

    Section("Programme reminders") {
        Text("Remind me this long before a programme starts", color = Vanta.Text)
        Choices(listOf(1, 5, 10, 15), remindMins, { "$it min" }) { remindMins = it; s.reminderMinutes = it }
    }

    Section("Phone gestures") {
        Toggle("Swipe and double tap", "Swipe left or right to change channel. Swipe up or down on the left for brightness, on the right for volume (up to 200%). Double tap for full screen.", gestures) { gestures = it; s.gestures = it }
    }

    Text("Changes apply the next time a video starts.", color = Vanta.TextDim, fontSize = 13.sp)
}

@Composable
private fun <T> Choices(options: List<T>, chosen: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FocusCard(onClick = { onPick(o) }, selected = o == chosen, modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    label(o), color = if (o == chosen) Vanta.Cyan else Vanta.Text,
                    fontWeight = if (o == chosen) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Vanta.Text)
            Text(detail, color = Vanta.TextDim, fontSize = 13.sp)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}
