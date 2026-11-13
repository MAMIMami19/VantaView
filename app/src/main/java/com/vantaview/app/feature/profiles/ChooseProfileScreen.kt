// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vantaview.app.data.db.ProfileEntity
import com.vantaview.app.ui.AvatarCircle
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta
import com.vantaview.app.ui.VantaLogo

private sealed interface Pending {
    data class Open(val p: ProfileEntity) : Pending
    data class Edit(val p: ProfileEntity) : Pending
    data object Add : Pending
}

/** "Who's watching?": pick a profile, add one, or manage them. */
@Composable
fun ChooseProfileScreen(
    onChosen: () -> Unit,
    onEdit: (Long) -> Unit,
    onAdd: () -> Unit,
    vm: ProfilesViewModel = hiltViewModel(),
) {
    val profiles by vm.profiles.collectAsState()
    var managing by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<ProfileEntity?>(null) }
    LaunchedEffect(profiles) { current = vm.current() }

    // Steps still to pass before an action: parent PIN (leaving Kids, managing), then the profile's own PIN.
    var parentGate by remember { mutableStateOf<Pending?>(null) }
    var profileGate by remember { mutableStateOf<ProfileEntity?>(null) }

    fun needsParent(action: Pending): Boolean {
        val cur = current
        if (!vm.repo.hasParentPin) return false
        return when (action) {
            is Pending.Open -> cur?.isKids == true && action.p.id != cur.id
            else -> profiles.any { it.isKids }
        }
    }

    fun proceed(action: Pending) {
        when (action) {
            is Pending.Open -> if (action.p.pinHash != null && action.p.id != current?.id) profileGate = action.p
                else { vm.repo.select(action.p.id); onChosen() }
            is Pending.Edit -> onEdit(action.p.id)
            Pending.Add -> onAdd()
        }
    }

    fun request(action: Pending) = if (needsParent(action)) parentGate = action else proceed(action)

    Box(Modifier.fillMaxSize().background(Vanta.Bg)) {
        VantaLogo(size = 48.dp, modifier = Modifier.padding(24.dp))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 80.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(if (managing) "Manage profiles" else "Who's watching?", color = Vanta.Text, fontSize = 32.sp, fontWeight = FontWeight.Black)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(horizontal = 24.dp)) {
                items(profiles, key = { it.id }) { p ->
                    ProfileTile(p, selected = p.id == current?.id, managing = managing) {
                        request(if (managing) Pending.Edit(p) else Pending.Open(p))
                    }
                }
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FocusCard(
                            onClick = { request(Pending.Add) }, shape = CircleShape,
                            padding = PaddingValues(0.dp), contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(120.dp).border(2.dp, Vanta.SurfaceHigh, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { Text("+", color = Vanta.TextDim, fontSize = 44.sp) }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("Add profile", color = Vanta.TextDim, fontWeight = FontWeight.Bold)
                    }
                }
            }
            FocusCard(onClick = { managing = !managing }) {
                Text(if (managing) "Done" else "Manage profiles", color = Vanta.Text)
            }
        }
    }

    parentGate?.let { action ->
        PinDialog(
            title = "Parent PIN",
            message = "Enter the parent PIN to continue.",
            check = { vm.repo.checkParentPin(it) },
            onOk = { parentGate = null; proceed(action) },
            onCancel = { parentGate = null },
        )
    }
    profileGate?.let { p ->
        PinDialog(
            title = p.name,
            message = "Enter ${p.name}'s PIN.",
            check = { vm.repo.checkPin(p, it) },
            onOk = { profileGate = null; vm.repo.select(p.id); onChosen() },
            onCancel = { profileGate = null },
        )
    }
}

@Composable
private fun ProfileTile(p: ProfileEntity, selected: Boolean, managing: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FocusCard(onClick = onClick, shape = CircleShape, padding = PaddingValues(4.dp), contentAlignment = Alignment.Center) {
            Box {
                AvatarCircle(p.avatar, 120.dp, ring = selected)
                if (p.pinHash != null) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(34.dp).background(Vanta.Bg, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("🔒", fontSize = 14.sp) }
                }
                if (managing) {
                    Box(
                        Modifier.align(Alignment.Center).size(120.dp).background(Color(0x88000000), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("✎", color = Color.White, fontSize = 32.sp) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(p.name, color = if (selected) Vanta.Text else Vanta.TextDim, fontWeight = FontWeight.Bold)
        if (p.isKids) {
            Spacer(Modifier.height(4.dp))
            Text(
                "KIDS", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.background(Color(0xFFFFC81A), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.width(0.dp))
    }
}
