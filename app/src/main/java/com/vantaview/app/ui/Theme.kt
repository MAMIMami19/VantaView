// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

object Vanta {
    val Bg = Color(0xFF07080F)
    val Surface = Color(0xFF10131F)
    val SurfaceHigh = Color(0xFF1A1F33)
    val Text = Color(0xFFF2F4FF)
    val TextDim = Color(0xFF9AA3C7)
    val Cyan = Color(0xFF00E5FF)
    val Error = Color(0xFFFF5370)
    val Gradient = listOf(
        Color(0xFF7C4DFF), Color(0xFF2979FF), Color(0xFF00E5FF),
        Color(0xFF00E676), Color(0xFFFFEA00), Color(0xFFFF9100),
    )
    val GradientBrush: Brush get() = Brush.linearGradient(Gradient)
}

@Composable
fun VantaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Vanta.Cyan,
            onPrimary = Color.Black,
            secondary = Color(0xFF7C4DFF),
            background = Vanta.Bg,
            onBackground = Vanta.Text,
            surface = Vanta.Surface,
            onSurface = Vanta.Text,
            surfaceVariant = Vanta.SurfaceHigh,
            onSurfaceVariant = Vanta.TextDim,
            error = Vanta.Error,
        ),
        content = content,
    )
}

/**
 * A clickable block that works with touch and with a TV remote: it shows the logo-gradient ring
 * when it has focus.
 */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    shape: Shape = RoundedCornerShape(12.dp),
    padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    contentAlignment: Alignment = Alignment.CenterStart,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (selected || focused) Vanta.SurfaceHigh else Vanta.Surface)
            .then(if (focused) Modifier.border(2.dp, Vanta.GradientBrush, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(padding),
        contentAlignment = contentAlignment,
    ) { content() }
}

/** Main action button filled with the logo gradient. */
@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (enabled) Vanta.GradientBrush else Brush.linearGradient(listOf(Vanta.SurfaceHigh, Vanta.SurfaceHigh)))
            .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Color.Black else Vanta.TextDim, fontWeight = FontWeight.Bold)
    }
}
