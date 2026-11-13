// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

enum class Section(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Filled.Home),
    LIVE("live", "Live TV", Icons.Filled.PlayArrow),
    MOVIES("movies", "Movies", Icons.Filled.Star),
    SERIES("series", "TV Series", Icons.Filled.List),
    SOURCES("sources", "Sources", Icons.Filled.Settings),
}

/**
 * Side rail on TVs, tablets and wide screens; menu drawer on phones. The page content always sits at the
 * same place in the tree, so switching between full-screen pages (player, details) and sections keeps state.
 */
@Composable
fun AppShell(
    current: Section?,
    profile: com.vantaview.app.data.db.ProfileEntity?,
    onSelect: (Section) -> Unit,
    onProfiles: () -> Unit,
    content: @Composable () -> Unit,
) {
    // Kids profiles can't open Sources.
    val sections = Section.entries.filter { !(profile?.isKids == true && it == Section.SOURCES) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Vanta.Bg).safeDrawingPadding()) {
        val wide = maxWidth >= 720.dp
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = !wide && current != null,
            drawerContent = {
                ModalDrawerSheet(drawerContainerColor = Vanta.Surface, modifier = Modifier.width(260.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                            VantaLogo(size = 40.dp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("VantaView", color = Vanta.Text, fontWeight = FontWeight.Black, fontSize = 20.sp)
                                Text("IPTV & VOD", color = Vanta.TextDim, fontSize = 12.sp)
                            }
                        }
                        sections.forEach { s ->
                            NavItem(s, s == current) { scope.launch { drawer.close() }; onSelect(s) }
                        }
                        Spacer(Modifier.height(12.dp))
                        ProfileChip(profile) { scope.launch { drawer.close() }; onProfiles() }
                    }
                }
            },
        ) {
            Row(Modifier.fillMaxSize()) {
                if (wide && current != null) {
                    Column(
                        Modifier.width(172.dp).fillMaxHeight().background(Vanta.Surface).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                            VantaLogo(size = 34.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("VantaView", color = Vanta.Text, fontWeight = FontWeight.Black, fontSize = 17.sp)
                        }
                        sections.forEach { s -> NavItem(s, s == current) { onSelect(s) } }
                        Spacer(Modifier.weight(1f))
                        ProfileChip(profile, onProfiles)
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (!wide && current != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FocusCard(
                                onClick = { scope.launch { drawer.open() } },
                                padding = PaddingValues(10.dp),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = Vanta.Text) }
                            Spacer(Modifier.width(10.dp))
                            VantaLogo(size = 28.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("VantaView", color = Vanta.Text, fontWeight = FontWeight.Black, fontSize = 18.sp)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                }
            }
        }
    }
}

@Composable
private fun NavItem(s: Section, selected: Boolean, onClick: () -> Unit) {
    FocusCard(onClick = onClick, selected = selected, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(s.icon, contentDescription = null, tint = if (selected) Vanta.Cyan else Vanta.TextDim)
            Spacer(Modifier.width(12.dp))
            Text(
                s.label,
                color = if (selected) Vanta.Cyan else Vanta.Text,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
    Spacer(Modifier.height(0.dp))
}

/** Current profile, opens "Who's watching?" to switch. */
@Composable
private fun ProfileChip(profile: com.vantaview.app.data.db.ProfileEntity?, onClick: () -> Unit) {
    if (profile == null) return
    FocusCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarCircle(profile.avatar, 30.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(profile.name, color = Vanta.Text, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(if (profile.isKids) "Kids · switch" else "Switch profile", color = Vanta.TextDim, fontSize = 12.sp)
            }
        }
    }
}
