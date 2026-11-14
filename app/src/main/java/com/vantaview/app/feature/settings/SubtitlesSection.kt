// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vantaview.app.data.PlaybackSettings
import com.vantaview.app.data.SubtitleMode
import com.vantaview.app.data.SubtitleSize
import com.vantaview.app.data.TRACK_LANGUAGES
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta

/** Settings › Subtitles and audio. Changes apply the next time a video starts. */
@Composable
fun SubtitlesSection(s: PlaybackSettings) {
    var audio by remember { mutableStateOf(s.audioLanguage) }
    var subLang by remember { mutableStateOf(s.subtitleLanguage) }
    var mode by remember { mutableStateOf(s.subtitleMode) }
    var size by remember { mutableStateOf(s.subtitleSize) }
    var yellow by remember { mutableStateOf(s.subtitleYellow) }
    var box by remember { mutableStateOf(s.subtitleBox) }
    var choosing by remember { mutableStateOf<String?>(null) } // "audio" or "sub"

    Section("Audio") {
        Text("Preferred audio language", color = Vanta.Text)
        Text("Picked automatically when a stream has it.", color = Vanta.TextDim, fontSize = 13.sp)
        FocusCard(onClick = { choosing = "audio" }) { Text(nameOf(audio) + "  ▾", color = Vanta.Text) }
    }

    Section("Subtitles") {
        Text("Show subtitles", color = Vanta.Text)
        SubtitleMode.entries.forEach { m ->
            FocusCard(onClick = { mode = m; s.subtitleMode = m }, selected = mode == m, modifier = Modifier.fillMaxWidth()) {
                Text(
                    (if (mode == m) "●  " else "○  ") + m.label,
                    color = if (mode == m) Vanta.Cyan else Vanta.Text,
                )
            }
        }
        if (mode != SubtitleMode.OFF) {
            Text("Preferred subtitle language", color = Vanta.Text)
            FocusCard(onClick = { choosing = "sub" }) { Text(nameOf(subLang) + "  ▾", color = Vanta.Text) }
        }
        Text("You can change audio and subtitles while watching too.", color = Vanta.TextDim, fontSize = 13.sp)
    }

    Section("Subtitle look") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubtitleSize.entries.forEach { z ->
                FocusCard(
                    onClick = { size = z; s.subtitleSize = z }, selected = size == z,
                    modifier = Modifier.weight(1f), contentAlignment = Alignment.Center,
                ) { Text(z.label, color = if (size == z) Vanta.Cyan else Vanta.Text, fontWeight = if (size == z) FontWeight.Bold else FontWeight.Normal) }
            }
        }
        Toggle("Yellow text", "Easier to read on bright pictures", yellow) { yellow = it; s.subtitleYellow = it }
        Toggle("Dark box behind", "Off gives an outline instead", box) { box = it; s.subtitleBox = it }
        // Preview on a picture-like background.
        Box(
            Modifier.fillMaxWidth().height(110.dp)
                .background(Brush.linearGradient(listOf(Color(0xFF3A6EA5), Color(0xFFE8B86B), Color(0xFF6B8E4E))), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Text(
                "This is how subtitles will look.",
                color = if (yellow) Color(0xFFFFD60A) else Color.White,
                fontSize = (17 * size.scale).sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                style = if (box) androidx.compose.ui.text.TextStyle.Default
                else androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 6f)),
                modifier = Modifier.padding(12.dp)
                    .then(if (box) Modifier.background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp) else Modifier),
            )
        }
    }

    Text("Changes apply the next time a video starts.", color = Vanta.TextDim, fontSize = 13.sp)

    choosing?.let { which ->
        AlertDialog(
            onDismissRequest = { choosing = null },
            containerColor = Vanta.Surface,
            title = { Text(if (which == "audio") "Audio language" else "Subtitle language", color = Vanta.Text) },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TRACK_LANGUAGES.forEach { (code, name) ->
                        val on = (if (which == "audio") audio else subLang) == code
                        FocusCard(onClick = {
                            if (which == "audio") { audio = code; s.audioLanguage = code } else { subLang = code; s.subtitleLanguage = code }
                            choosing = null
                        }, selected = on, modifier = Modifier.fillMaxWidth()) {
                            Text((if (on) "✓  " else "     ") + name, color = if (on) Vanta.Cyan else Vanta.Text)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = null }) { Text("Close") } },
        )
    }
}

private fun nameOf(code: String) = TRACK_LANGUAGES.firstOrNull { it.first == code }?.second ?: code

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
