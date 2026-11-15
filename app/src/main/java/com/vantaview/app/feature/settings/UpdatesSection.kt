// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vantaview.app.BuildConfig
import com.vantaview.app.data.update.UpdateInfo
import com.vantaview.app.data.update.UpdateManager
import com.vantaview.app.data.update.UpdateState
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings › Updates. */
@Composable
fun UpdatesSection(m: UpdateManager) {
    val state by m.state.collectAsState()
    var test by remember { mutableStateOf(m.testBuilds) }
    val fmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.UK)

    Section("This version") {
        Text("VantaView ${BuildConfig.VERSION_NAME}", color = Vanta.Text, fontSize = 16.sp)
        Text("Build ${BuildConfig.VERSION_CODE}", color = Vanta.TextDim, fontSize = 13.sp)
        Text(
            "VantaView checks for a new version once a day when it starts.",
            color = Vanta.TextDim, fontSize = 13.sp,
        )
        when (val s = state) {
            UpdateState.Checking -> Text("Checking…", color = Vanta.Cyan)
            is UpdateState.UpToDate -> Text("Up to date. Checked ${fmt.format(Date(s.at))}.", color = Vanta.Cyan)
            is UpdateState.Failed -> Text(s.message, color = Vanta.Error)
            else -> if (m.lastCheck > 0 && s == UpdateState.Idle) {
                Text("Last checked ${fmt.format(Date(m.lastCheck))}.", color = Vanta.TextDim, fontSize = 13.sp)
            }
        }
        if (state !is UpdateState.Downloading) FocusCard(onClick = m::checkNow) { Text("Check now", color = Vanta.Text) }
    }

    (state as? UpdateState.Available)?.info?.let { UpdateCard(it, null, m) }
    (state as? UpdateState.Downloading)?.let { UpdateCard(it.info, it.percent, m) }
    (state as? UpdateState.NeedsPermission)?.let { s ->
        Section("Allow VantaView to install updates") {
            var noPage by remember { mutableStateOf(false) }
            Text(
                if (m.isFireTv || noPage)
                    "On Fire TV: Settings › My Fire TV › Developer options › Install unknown apps › VantaView: On. " +
                        "(On some Fire TVs: Settings › Applications › Install unknown apps.) Then come back and press Update now."
                else "Android needs you to allow VantaView to install apps. Turn on \"Allow from this source\", come back, then press Update now again.",
                color = Vanta.TextDim, fontSize = 14.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!m.isFireTv) GradientButton("Open the setting", onClick = { noPage = !m.openInstallPermission() })
                FocusCard(onClick = { m.install(s.info) }) { Text("Update now", color = Vanta.Text) }
            }
        }
    }

    Section("Test builds") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Offer test builds", color = Vanta.Text)
                Text(
                    "Also offer the newest test build from GitHub. They have the latest changes but may have bugs.",
                    color = Vanta.TextDim, fontSize = 13.sp,
                )
            }
            Switch(checked = test, onCheckedChange = { test = it; m.testBuilds = it; m.checkNow() })
        }
    }
}

@Composable
private fun UpdateCard(info: UpdateInfo, percent: Int?, m: UpdateManager) {
    Section("${info.name} is available") {
        if (info.size > 0) Text("%.1f MB".format(Locale.UK, info.size / 1_048_576.0), color = Vanta.TextDim, fontSize = 13.sp)
        if (info.notes.isNotBlank()) {
            Text("What's new", color = Vanta.Text, fontWeight = FontWeight.Bold)
            Text(info.notes, color = Vanta.TextDim, fontSize = 14.sp, lineHeight = 20.sp)
        }
        if (percent != null) {
            Text("Downloading… $percent%", color = Vanta.Cyan)
            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
        } else {
            GradientButton("Update now", onClick = { m.install(info) })
            Text("Android asks you to confirm before installing. Your profiles, playlists and settings stay.", color = Vanta.TextDim, fontSize = 13.sp)
        }
    }
}

/** The update notice shown on launch. */
@Composable
fun UpdateNotice(info: UpdateInfo, m: UpdateManager, onSettings: () -> Unit) {
    val state by m.state.collectAsState()
    val downloading = state as? UpdateState.Downloading
    AlertDialog(
        onDismissRequest = { if (downloading == null) m.later(info) },
        containerColor = Vanta.Panel,
        title = { Text("${info.name} is available", color = Vanta.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (info.notes.isNotBlank()) {
                    Text("What's new", color = Vanta.Text, fontWeight = FontWeight.Bold)
                    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        Text(info.notes, color = Vanta.TextDim, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
                when {
                    downloading != null -> {
                        Text("Downloading… ${downloading.percent}%", color = Vanta.Cyan)
                        LinearProgressIndicator(progress = { downloading.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    state is UpdateState.NeedsPermission -> Text(
                        "Android needs you to allow VantaView to install apps first. Settings › Updates shows how.",
                        color = Vanta.Error, fontSize = 13.sp,
                    )
                    state is UpdateState.Failed -> Text((state as UpdateState.Failed).message, color = Vanta.Error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            if (state is UpdateState.NeedsPermission) GradientButton("Open Settings", onClick = { m.dismissNotice(); onSettings() })
            else if (downloading == null) GradientButton("Update now", onClick = { m.install(info) })
        },
        dismissButton = { if (downloading == null) TextButton(onClick = { m.later(info) }) { Text("Later") } },
    )
}
