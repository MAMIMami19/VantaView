// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vantaview.app.data.AccentPreset
import com.vantaview.app.data.AppearanceSettings
import com.vantaview.app.data.TextSize
import com.vantaview.app.data.ThemeMode
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta
import java.util.Locale

/** Settings › Appearance. Changes show straight away. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSection(s: AppearanceSettings) {
    val a by s.state.collectAsState()

    Section("Theme") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { m ->
                Pick(m.label, a.theme == m) { s.update { it.copy(theme = m) } }
            }
        }
        if (a.theme == ThemeMode.BLACK) Text("True black saves battery on OLED screens.", color = Vanta.TextDim, fontSize = 13.sp)
        if (a.theme == ThemeMode.SYSTEM) Text("Switches between dark and light with your device.", color = Vanta.TextDim, fontSize = 13.sp)
        Text("The player always stays dark.", color = Vanta.TextDim, fontSize = 13.sp)
    }

    Section("Accent colour") {
        Text("Used for highlights, selected items and focus rings.", color = Vanta.TextDim, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AccentPreset.entries.forEach { p ->
                Swatch(Color(p.highlight), p.label, a.accent == p) { s.update { it.copy(accent = p) } }
            }
            Swatch(Color(a.custom), "Custom", a.accent == null) { s.update { it.copy(accent = null) } }
        }
        if (a.accent == null) CustomColour(Color(a.custom)) { c -> s.update { it.copy(custom = c.toArgb().toLong() and 0xFFFFFFFFL) } }
    }

    Section("Text size") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextSize.entries.forEach { t ->
                Box(Modifier.weight(1f)) {
                    Pick(t.label, a.textSize == t, Modifier.fillMaxWidth(), fontSize = (15 * t.scale).sp) { s.update { it.copy(textSize = t) } }
                }
            }
        }
    }

    Section("Sidebar and loading") {
        Toggle(
            "Hide the sidebar", "On TVs and wide screens, the sidebar tucks away after 5 seconds. Press Left to bring it back.",
            a.sidebarAutoHide,
        ) { on -> s.update { it.copy(sidebarAutoHide = on) } }
        Toggle("Loading screen", "Show the light ripple and logo when VantaView starts", a.loadingScreen) { on ->
            s.update { it.copy(loadingScreen = on) }
        }
    }

    Section("Language") {
        val device = Locale.getDefault().getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
        Text("English", color = Vanta.Text)
        Text(
            if (Locale.getDefault().language == "en") "VantaView follows your device's language."
            else "Your device is set to $device. VantaView will follow it once it's translated; until then it uses English.",
            color = Vanta.TextDim, fontSize = 13.sp,
        )
    }
}

@Composable
private fun Pick(label: String, on: Boolean, modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 14.sp, onClick: () -> Unit) {
    FocusCard(onClick = onClick, selected = on, modifier = modifier, contentAlignment = Alignment.Center) {
        Text(label, color = if (on) Vanta.Cyan else Vanta.Text, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, fontSize = fontSize)
    }
}

@Composable
private fun Swatch(color: Color, label: String, on: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FocusCard(onClick = onClick, shape = CircleShape, padding = androidx.compose.foundation.layout.PaddingValues(3.dp)) {
            Box(
                Modifier.size(40.dp).background(color, CircleShape)
                    .then(if (on) Modifier.border(3.dp, Vanta.Text, CircleShape) else Modifier)
            )
        }
        Text(label, color = if (on) Vanta.Text else Vanta.TextDim, fontSize = 12.sp)
    }
}

/** A hue slider and a hex box for a custom accent. */
@Composable
private fun CustomColour(current: Color, onPick: (Color) -> Unit) {
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(current.toArgb(), it) }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var hex by remember { mutableStateOf(String.format(Locale.UK, "%06X", current.toArgb() and 0xFFFFFF)) }
    var bad by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).background(current, CircleShape))
        Text("Colour", color = Vanta.Text, modifier = Modifier.width(60.dp))
        Slider(
            value = hue, onValueChange = { hue = it }, valueRange = 0f..360f, modifier = Modifier.weight(1f),
            onValueChangeFinished = {
                val c = Color.hsv(hue, 0.85f, 1f)
                hex = String.format(Locale.UK, "%06X", c.toArgb() and 0xFFFFFF)
                onPick(c)
            },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = hex, singleLine = true, label = { Text("Hex, e.g. FF4FD8") }, modifier = Modifier.weight(1f),
            onValueChange = { v ->
                hex = v.removePrefix("#").uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                bad = false
                if (hex.length == 6) onPick(Color(0xFF000000 or hex.toLong(16)))
            },
            isError = bad,
        )
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
