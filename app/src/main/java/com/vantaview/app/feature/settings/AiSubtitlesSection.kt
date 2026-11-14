// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
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
        Requirements(ai)

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
            // What gets downloaded, so people can look it up first.
            Column(
                Modifier.fillMaxWidth().background(Vanta.SurfaceHigh, RoundedCornerShape(10.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("What gets downloaded", color = Vanta.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("${model.file} (${model.mb} MB), a Whisper speech model converted by the whisper.cpp project", color = Vanta.TextDim, fontSize = 13.sp)
                Text("From: ${ai.sourceShort}", color = Vanta.Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Free, open source, MIT licence. Stored only on this device; delete it below at any time.", color = Vanta.TextDim, fontSize = 12.sp)
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

/** A clear box: what AI subtitles need, and how this device measures up. */
@Composable
private fun Requirements(ai: AiSubtitles) {
    val fit = remember { ai.deviceFit() }
    val colour = when (fit.level) {
        com.vantaview.app.data.aisubs.DeviceFit.Level.GOOD -> androidx.compose.ui.graphics.Color(0xFF19B36B)
        com.vantaview.app.data.aisubs.DeviceFit.Level.OK -> androidx.compose.ui.graphics.Color(0xFFE0A100)
        com.vantaview.app.data.aisubs.DeviceFit.Level.POOR -> Vanta.Error
    }
    Column(
        Modifier.fillMaxWidth()
            .border(2.dp, colour, RoundedCornerShape(12.dp))
            .background(colour.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("⚠  Before you turn this on", color = Vanta.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        listOf(
            "Needs a fairly powerful device: a phone or tablet from about 2019 or newer with 4 GB of memory, or a strong TV box (Nvidia Shield, Chromecast with Google TV, Fire TV Stick 4K Max, Fire TV Cube).",
            "Not for 1 GB devices like the basic Fire TV Stick or Fire TV Stick Lite.",
            "Works the processor hard: phones drain faster and get warm, and weaker devices may stutter.",
            "Words appear about 5 to 7 seconds after they're spoken.",
            "Accuracy drops with music, crowd noise, people talking over each other and strong accents. Translation is into English only.",
            "Needs 31 MB (Fast) or 57 MB (Better) of storage for the speech model.",
        ).forEach { Text("•  $it", color = Vanta.Text, fontSize = 13.sp, lineHeight = 18.sp) }
        Text(
            "This device: ${fit.memoryGb} GB memory · ${if (fit.is64) "64-bit" else "32-bit"} · ${fit.cores} cores",
            color = Vanta.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp),
        )
        Text(fit.advice, color = colour, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
