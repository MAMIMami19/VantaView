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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One set of colours for the whole app. */
data class VantaPalette(
    val bg: Color, val surface: Color, val surfaceHigh: Color, val text: Color, val textDim: Color,
    val highlight: Color, val error: Color, val accent: Color, val light: Boolean,
)

private val darkPalette = VantaPalette(
    bg = Color(0xFF07080F), surface = Color(0xFF10131F), surfaceHigh = Color(0xFF1A1F33),
    text = Color(0xFFF2F4FF), textDim = Color(0xFF9AA3C7), highlight = Color(0xFF00E5FF),
    error = Color(0xFFFF5370), accent = Color(0xFF6E54E6), light = false,
)

/** Builds the palette for a theme and accent. Light themes darken the highlight so it reads on white. */
fun paletteFor(theme: com.vantaview.app.data.ThemeMode, systemDark: Boolean, highlight: Color, focus: Color): VantaPalette {
    val mode = if (theme == com.vantaview.app.data.ThemeMode.SYSTEM) {
        if (systemDark) com.vantaview.app.data.ThemeMode.DARK else com.vantaview.app.data.ThemeMode.LIGHT
    } else theme
    return when (mode) {
        com.vantaview.app.data.ThemeMode.BLACK -> darkPalette.copy(
            bg = Color.Black, surface = Color(0xFF0B0C12), surfaceHigh = Color(0xFF161A2A), highlight = highlight, accent = focus,
        )
        com.vantaview.app.data.ThemeMode.LIGHT -> VantaPalette(
            bg = Color(0xFFF4F5FA), surface = Color.White, surfaceHigh = Color(0xFFE3E6F2),
            text = Color(0xFF12141F), textDim = Color(0xFF5A6385),
            highlight = androidx.compose.ui.graphics.lerp(highlight, Color.Black, 0.35f),
            error = Color(0xFFD32F45), accent = focus, light = true,
        )
        else -> darkPalette.copy(highlight = highlight, accent = focus)
    }
}

/**
 * The app's colours. They follow Settings › Appearance: the palette is snapshot state, so screens
 * redraw when the theme or accent changes.
 */
object Vanta {
    var palette by mutableStateOf(darkPalette)
    val Bg get() = palette.bg
    val Surface get() = palette.surface
    val SurfaceHigh get() = palette.surfaceHigh
    val Text get() = palette.text
    val TextDim get() = palette.textDim
    /** The highlight colour (named Cyan because that's the default). */
    val Cyan get() = palette.highlight
    val Error get() = palette.error
    val Gradient = listOf(
        Color(0xFF7C4DFF), Color(0xFF2979FF), Color(0xFF00E5FF),
        Color(0xFF00E676), Color(0xFFFFEA00), Color(0xFFFF9100),
    )
    /** Focus rings and main buttons; violet unless the accent is changed. */
    val Accent get() = palette.accent
    /** The full logo gradient, kept for the logo itself; the UI uses the solid [Accent] instead. */
    val LogoBrush: Brush get() = Brush.linearGradient(Gradient)
    val GradientBrush: Brush get() = androidx.compose.ui.graphics.SolidColor(Accent)
}

/** Settings › Appearance for the whole app (sidebar auto-hide, loading screen...). */
val LocalAppearance = androidx.compose.runtime.staticCompositionLocalOf { com.vantaview.app.data.Appearance() }

@Composable
fun VantaTheme(appearance: com.vantaview.app.data.Appearance = com.vantaview.app.data.Appearance(), content: @Composable () -> Unit) {
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val highlight = Color(appearance.accent?.highlight ?: appearance.custom)
    val focus = appearance.accent?.let { Color(it.focus) } ?: androidx.compose.ui.graphics.lerp(Color(appearance.custom), Color.Black, 0.2f)
    val p = paletteFor(appearance.theme, systemDark, highlight, focus)
    Vanta.palette = p
    // Dark status bar icons on the light theme.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        @Suppress("DEPRECATION")
        run { window.statusBarColor = p.bg.toArgb(); window.navigationBarColor = p.bg.toArgb() }
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = p.light
            isAppearanceLightNavigationBars = p.light
        }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val colors = if (p.light) {
        androidx.compose.material3.lightColorScheme(
            primary = p.accent, onPrimary = Color.White, secondary = Color(0xFF7C4DFF),
            background = p.bg, onBackground = p.text, surface = p.surface, onSurface = p.text,
            surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.textDim, error = p.error,
        )
    } else darkColorScheme(
        primary = p.accent, onPrimary = Color.White, secondary = Color(0xFF7C4DFF),
        background = p.bg, onBackground = p.text, surface = p.surface, onSurface = p.text,
        surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.textDim, error = p.error,
    )
    androidx.compose.runtime.CompositionLocalProvider(
        LocalAppearance provides appearance,
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
            density.density, density.fontScale * appearance.textSize.scale,
        ),
    ) {
        MaterialTheme(colorScheme = colors, content = content)
    }
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
            .then(if (focused) Modifier.border(3.dp, Vanta.GradientBrush, shape) else Modifier)
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
        Text(text, color = if (enabled) Color.White else Vanta.TextDim, fontWeight = FontWeight.Bold)
    }
}
