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
    /** Dialogs and menus: always solid, even when [surface] lets the background show through. */
    val panel: Color = surface,
    /** Looks with their own background (Glass, Broadcast, Wave, Classic) leave pages see-through. */
    val look: com.vantaview.app.data.Look = com.vantaview.app.data.Look.VANTA,
    /** A focused guide or channel row is filled with this colour (and its text turns [onFill]). */
    val fill: Color? = null,
    val onFill: Color = Color(0xFF0B1450),
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

/** Colours for each look. VANTA isn't here: it follows the theme and accent. */
fun lookPalette(look: com.vantaview.app.data.Look): VantaPalette? = when (look) {
    com.vantaview.app.data.Look.VANTA -> null
    com.vantaview.app.data.Look.GLASS -> VantaPalette(
        bg = Color(0xFF0A1A55), surface = Color(0x40FFFFFF), surfaceHigh = Color(0x66FFFFFF),
        text = Color.White, textDim = Color(0xFFC6D3FF), highlight = Color(0xFF9FD8FF),
        error = Color(0xFFFF7A8A), accent = Color.White, light = false, panel = Color(0xFF16307F), look = look,
    )
    com.vantaview.app.data.Look.BROADCAST -> VantaPalette(
        bg = Color(0xFF1F5AD0), surface = Color(0xFF2050C4), surfaceHigh = Color(0xFF3A72EC),
        text = Color.White, textDim = Color(0xFFD5E1FF), highlight = Color(0xFFFFCB3D),
        error = Color(0xFFFFB4B4), accent = Color(0xFFFFCB3D), light = false, panel = Color(0xFF1B47B0), look = look,
        fill = Color(0xFFFFCB3D),
    )
    com.vantaview.app.data.Look.WAVE -> VantaPalette(
        bg = Color(0xFF07305E), surface = Color(0x26FFFFFF), surfaceHigh = Color(0x4DFFFFFF),
        text = Color.White, textDim = Color(0xFFB7D3F0), highlight = Color(0xFF8FE3FF),
        error = Color(0xFFFF8A9A), accent = Color.White, light = false, panel = Color(0xFF0C3F78), look = look,
    )
    com.vantaview.app.data.Look.CLASSIC -> VantaPalette(
        bg = Color(0xFF0B1450), surface = Color(0xFF2341C4), surfaceHigh = Color(0xFF3557DA),
        text = Color.White, textDim = Color(0xFFBFCBFF), highlight = Color(0xFFFFC928),
        error = Color(0xFFFFB4B4), accent = Color(0xFFFFC928), light = false, panel = Color(0xFF16237A), look = look,
        fill = Color(0xFFFFC928),
    )
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
    /** Dialogs and menus. */
    val Panel get() = palette.panel
    /** A page's own background: see-through when the look draws one behind every page. */
    val Page get() = if (palette.look == com.vantaview.app.data.Look.VANTA) palette.bg else Color.Transparent
    val Look get() = palette.look
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
    val p = lookPalette(appearance.look) ?: paletteFor(appearance.theme, systemDark, highlight, focus)
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
            background = p.bg, onBackground = p.text, surface = p.panel, onSurface = p.text,
            surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.textDim, error = p.error,
        )
    } else darkColorScheme(
        primary = p.accent, onPrimary = Color.White, secondary = Color(0xFF7C4DFF),
        background = p.bg, onBackground = p.text, surface = p.panel, onSurface = p.text,
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

/**
 * Like [FocusCard], for guide cells and channel rows: in looks with a fill colour (Broadcast,
 * Classic) the focused row turns that colour. [content] gets the text colour to use.
 */
@Composable
fun FillCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    shape: Shape = RoundedCornerShape(12.dp),
    padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    content: @Composable (text: Color, dim: Color) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val fill = Vanta.palette.fill
    val filled = focused && fill != null
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (filled) fill!! else if (selected || focused) Vanta.SurfaceHigh else Vanta.Surface)
            .then(if (focused && !filled) Modifier.border(3.dp, Vanta.GradientBrush, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(padding),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (filled) content(Vanta.palette.onFill, Vanta.palette.onFill.copy(alpha = 0.75f)) else content(Vanta.Text, Vanta.TextDim)
    }
}
