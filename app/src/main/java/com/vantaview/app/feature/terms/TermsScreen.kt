// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.terms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta
import com.vantaview.app.ui.VantaLogo

/** The terms of use, shown on first launch and again in Settings › Help and about. */
const val TERMS_TEXT =
    "VantaView is only a player for IPTV and video on demand. It does not come with, " +
        "host, sell or recommend any channels, films or series.\n\n" +
        "You add your own playlists and sources. Finding and using those links is at your own " +
        "discretion, and you are responsible for making sure you have the right to watch " +
        "what they contain.\n\n" +
        "VantaView is free, open-source software released under the GNU GPL version 3 or later, " +
        "with no warranty."

@Composable
fun TermsScreen(onAgree: () -> Unit, onDisagree: () -> Unit) {
    val agreeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { agreeFocus.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Vanta.Bg).safeDrawingPadding().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 640.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            VantaLogo(size = 112.dp)
            Spacer(Modifier.height(8.dp))
            Text("VantaView", color = Vanta.Text, fontSize = 34.sp, fontWeight = FontWeight.Black)
            Text("IPTV & VOD", color = Vanta.TextDim, fontSize = 14.sp)
            Spacer(Modifier.height(24.dp))
            Text("Terms of use", color = Vanta.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                TERMS_TEXT,
                color = Vanta.Text,
                fontSize = 16.sp,
                lineHeight = 23.sp,
            )
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FocusCard(onClick = onDisagree, contentAlignment = Alignment.Center) {
                    Text("Disagree", color = Vanta.Text, fontWeight = FontWeight.Bold)
                }
                GradientButton("Agree", onClick = onAgree, modifier = Modifier.focusRequester(agreeFocus))
            }
        }
    }
}
