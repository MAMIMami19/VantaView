// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vantaview.app.BuildConfig
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta

/** The Settings sections, in the agreed order. [keywords] are extra words the search box matches. */
enum class SettingsSection(val title: String, val summary: String, val keywords: String) {
    PLAYBACK(
        "Playback", "Buffer size, how long controls stay, mini player, start on last channel, picture size and phone gestures",
        "buffer buffering controls hide mini player preview start last channel picture size fit fill zoom gestures swipe double tap",
    ),
    APPEARANCE(
        "Appearance", "Look, theme, accent colour, text size, sidebar, loading screen and language",
        "look style skin glass broadcast wave classic theme dark light black oled follow device accent colour color text size font sidebar hide loading screen splash ripple language",
    ),
    SUBTITLES("Subtitles and audio", "Audio and subtitle languages, when subtitles show, their size and colour", "subtitle subtitles captions cc audio language track size yellow box"),
    SOURCES("Sources and storage", "Refreshing playlists and the TV guide, free channels, and space used", "playlist refresh free tv channels countries storage space cache clear pictures guide epg update downloads"),
    WEATHER("Weather", "Weather on Home: on or off, °C or °F, and where", "weather temperature celsius fahrenheit location town city forecast"),
    BACKUP("Backup and restore", "Back up to a file or your phone, restore, and automatic backups", "backup restore export import password qr phone automatic folder"),
    PARENTAL(
        "Parental control", "Hide adult categories, lock Sources, your profile PIN and clearing watch history",
        "parental pin lock adult kids history clear hide sources profile",
    ),
    UPDATES("Updates", "Check for a new version and install it, and test builds", "update updates version download install apk test build new"),
    ABOUT("Help and about", "App tour, support, terms, credits and version", "help support tour terms credits licence license about version");

    fun matches(query: String): Boolean {
        val hay = "$title $summary $keywords".lowercase()
        return query.lowercase().split(' ').filter { it.isNotBlank() }.all { it in hay }
    }
}

/**
 * The Settings page. On wide screens (TVs, tablets) the sections are on the left and the chosen one
 * shows on the right, changing as you move down the list. On phones it's a list; picking a section
 * opens it and Back returns to the list. [content] draws a section's options.
 */
@Composable
fun SettingsLayout(content: @Composable (SettingsSection) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var current by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    val found = SettingsSection.entries.filter { it.matches(query) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Vanta.Page).imePadding()) {
        if (maxWidth >= 720.dp) {
            val shown = current?.takeIf { it in found } ?: found.firstOrNull()
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ListHeader(query) { query = it }
                    found.forEach { sec ->
                        FocusCard(
                            onClick = { current = sec },
                            selected = sec == shown,
                            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) current = sec },
                        ) { Text(sec.title, color = Vanta.Text, fontWeight = if (sec == shown) FontWeight.Bold else FontWeight.Normal) }
                    }
                    if (found.isEmpty()) NoMatch()
                }
                if (shown != null) key(shown) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(shown.title, color = Vanta.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        content(shown)
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        } else {
            val open = current
            BackHandler(enabled = open != null) { current = null }
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(if (open == null) 8.dp else 14.dp),
            ) {
                if (open == null) {
                    ListHeader(query) { query = it }
                    found.forEach { sec ->
                        FocusCard(onClick = { current = sec }, modifier = Modifier.fillMaxWidth()) {
                            Column {
                                Text(sec.title, color = Vanta.Text, fontWeight = FontWeight.Bold)
                                Text(sec.summary, color = Vanta.TextDim, fontSize = 13.sp)
                            }
                        }
                    }
                    if (found.isEmpty()) NoMatch()
                } else key(open) {
                    FocusCard(onClick = { current = null }) { Text("‹ Settings", color = Vanta.Text) }
                    Text(open.title, color = Vanta.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    content(open)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun ListHeader(query: String, onQuery: (String) -> Unit) {
    Text("Settings", color = Vanta.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    com.vantaview.app.ui.SearchToggle(query, onQuery, "Search settings", Modifier.padding(bottom = 4.dp))
}

@Composable
private fun NoMatch() = Text("No settings match that.", color = Vanta.TextDim, fontSize = 14.sp)

/** Placeholder for a section whose options aren't built yet. */
@Composable
fun ComingSoon(section: SettingsSection) {
    Text(section.summary + ".", color = Vanta.TextDim, fontSize = 15.sp)
    Text("These options are coming in a later update.", color = Vanta.TextDim, fontSize = 14.sp)
    if (section == SettingsSection.ABOUT) Text("VantaView ${BuildConfig.VERSION_NAME}", color = Vanta.Text, fontSize = 15.sp)
}
