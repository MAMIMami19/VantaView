// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vantaview.app.data.aisubs.AiMode
import com.vantaview.app.data.aisubs.AiModel
import com.vantaview.app.data.aisubs.AiSubtitles
import com.vantaview.app.data.aisubs.ModelState
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta

/** Settings › Subtitles and audio › AI subtitles (beta). */
@Composable
fun AiSubtitlesSection(ai: AiSubtitles) {
    var mode by remember { mutableStateOf(ai.mode) }
    var model by remember { mutableStateOf(ai.model) }
    val state by ai.modelState.collectAsState()

    Section("AI subtitles (beta)") {
        Text(
            "Turns speech into subtitles as you watch, on this device. Nothing is sent online. Words appear a few seconds after they're spoken, and it uses more battery.",
            color = Vanta.TextDim, fontSize = 14.sp,
        )
        if (!ai.supported) {
            Text("This device's processor isn't supported.", color = Vanta.Error, fontSize = 14.sp)
            return@Section
        }
        if (ai.lowMemory) Text("This device has little memory, so AI subtitles may be slow. Use the Fast model.", color = Vanta.Cyan, fontSize = 13.sp)

        AiMode.entries.forEach { m ->
            FocusCard(onClick = { mode = m; ai.mode = m }, selected = mode == m, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text((if (mode == m) "●  " else "○  ") + m.label, color = if (mode == m) Vanta.Cyan else Vanta.Text)
                    if (m.detail.isNotEmpty()) Text(m.detail, color = Vanta.TextDim, fontSize = 12.sp)
                }
            }
        }

        if (mode != AiMode.OFF) {
            Text("Speech model", color = Vanta.Text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                AiModel.entries.forEach { m ->
                    FocusCard(onClick = { model = m; ai.model = m }, selected = model == m, modifier = Modifier.weight(1f)) {
                        Column {
                            Text("${m.label} · ${m.mb} MB", color = if (model == m) Vanta.Cyan else Vanta.Text)
                            Text(m.detail, color = Vanta.TextDim, fontSize = 12.sp)
                        }
                    }
                }
            }
            when (val s = state) {
                ModelState.Missing -> GradientButton("Download the ${model.label} model (${model.mb} MB)", onClick = ai::download)
                is ModelState.Downloading -> {
                    Text("Downloading… ${s.percent}%", color = Vanta.Cyan)
                    LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
                ModelState.Ready -> Text("Ready. AI subtitles start with the next video you play.", color = Vanta.Cyan, fontSize = 14.sp)
                is ModelState.Failed -> {
                    Text(s.message, color = Vanta.Error, fontSize = 13.sp)
                    GradientButton("Try again", onClick = ai::download)
                }
            }
        }
        if (state == ModelState.Ready || mode == AiMode.OFF) {
            FocusCard(onClick = ai::deleteModels) { Text("Delete downloaded models", color = Vanta.Text) }
        }
        Text("Speech recognition by whisper.cpp and OpenAI's Whisper models (MIT licence).", color = Vanta.TextDim, fontSize = 11.sp)
    }
}
