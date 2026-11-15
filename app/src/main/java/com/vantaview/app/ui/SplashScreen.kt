// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The loading screen: logo, name and a loading bar over the light ripple. */
@Composable
fun SplashScreen(leaving: Boolean = false) {
    val fade by animateFloatAsState(if (leaving) 0f else 1f, tween(400), label = "fade")
    // Leaving: the OpenGL ripple can't fade as a layer, so it's removed and the rest fades over Home,
    // instead of going through black.
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade }.background(Color.Black)) {
        if (!leaving) RippleBackground(Modifier.fillMaxSize(), brightness = 1.05f)
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val big = maxWidth > maxHeight
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                VantaLogo(size = if (big) 110.dp else 96.dp)
                Text("VantaView", color = Vanta.Text, fontSize = if (big) 48.sp else 40.sp, fontWeight = FontWeight.Black)
                Text("IPTV  &  VOD", color = Vanta.TextDim, fontSize = 14.sp, letterSpacing = 4.sp)
                Spacer(Modifier.height(14.dp))
                LoadingBar()
                Text("Loading…", color = Vanta.TextDim, fontSize = 13.sp)
            }
            Text(
                tr("v{0} · build {1}", com.vantaview.app.BuildConfig.VERSION_NAME, com.vantaview.app.BuildConfig.VERSION_CODE),
                color = Vanta.TextDim.copy(alpha = 0.8f), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun LoadingBar() {
    val move by rememberInfiniteTransition(label = "load").animateFloat(
        initialValue = -1f, targetValue = 2.5f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "bar",
    )
    Box(Modifier.width(220.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x1FFFFFFF))) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(0.4f)
                .graphicsLayer { translationX = move * size.width }
                .background(Brush.horizontalGradient(listOf(Color(0xFF14E0E0), Color(0xFFFF4FD8))), RoundedCornerShape(2.dp))
        )
    }
}
