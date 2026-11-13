// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vantaview.app.R

/** The built-in avatars from the signed-off profile design: icon plus its gradient. */
object Avatars {
    data class Avatar(val icon: Int, val from: Color, val to: Color)

    val all = listOf(
        Avatar(R.drawable.avatar_star, Color(0xFF2F7BFF), Color(0xFF7B5CFF)),
        Avatar(R.drawable.avatar_bolt, Color(0xFFFFC81A), Color(0xFFFF5A14)),
        Avatar(R.drawable.avatar_rocket, Color(0xFF19E57A), Color(0xFF14E0E0)),
        Avatar(R.drawable.avatar_planet, Color(0xFFFF4FD8), Color(0xFF7B5CFF)),
        Avatar(R.drawable.avatar_waves, Color(0xFF14E0E0), Color(0xFF2F7BFF)),
        Avatar(R.drawable.avatar_flame, Color(0xFFFF5A14), Color(0xFFFF4F7A)),
        Avatar(R.drawable.avatar_leaf, Color(0xFF19E57A), Color(0xFFFFC81A)),
        Avatar(R.drawable.avatar_moon, Color(0xFF7B5CFF), Color(0xFF14E0E0)),
    )

    fun get(i: Int) = all[((i % all.size) + all.size) % all.size]
}

@Composable
fun AvatarCircle(index: Int, size: Dp, modifier: Modifier = Modifier, ring: Boolean = false) {
    val a = Avatars.get(index)
    Box(
        modifier
            .size(size)
            .then(if (ring) Modifier.border(3.dp, Vanta.GradientBrush, CircleShape).padding(5.dp) else Modifier)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(a.from, a.to))),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(a.icon), contentDescription = null, modifier = Modifier.size(size * 0.45f))
    }
}
