// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode(val label: String) { DARK("Dark"), BLACK("Black (OLED)"), LIGHT("Light"), SYSTEM("Follow device") }

/** Highlight colour (selected items, progress) and focus colour (focus rings, main buttons). */
enum class AccentPreset(val label: String, val highlight: Long, val focus: Long) {
    CYAN("Cyan", 0xFF00E5FF, 0xFF6E54E6),
    MAGENTA("Magenta", 0xFFFF4FD8, 0xFFC2369F),
    PURPLE("Purple", 0xFFA88BFF, 0xFF6E54E6),
    ORANGE("Orange", 0xFFFF9100, 0xFFE0631A),
    GREEN("Green", 0xFF19E57A, 0xFF12A65A),
    GOLD("Gold", 0xFFFFC81A, 0xFFD19A00),
}

enum class TextSize(val label: String, val scale: Float) { SMALL("Small", 0.9f), NORMAL("Normal", 1f), LARGE("Large", 1.15f) }

data class Appearance(
    val theme: ThemeMode = ThemeMode.DARK,
    val accent: AccentPreset? = AccentPreset.CYAN,
    /** Used when [accent] is null (a custom colour), as 0xAARRGGBB. */
    val custom: Long = 0xFF00E5FF,
    val textSize: TextSize = TextSize.NORMAL,
    val sidebarAutoHide: Boolean = true,
    val loadingScreen: Boolean = true,
)

/** Settings › Appearance, shared by every profile on this device. */
@Singleton
class AppearanceSettings @Inject constructor(@ApplicationContext context: Context) {
    private val sp = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Appearance> = _state

    private fun read() = Appearance(
        theme = runCatching { ThemeMode.valueOf(sp.getString("theme", null)!!) }.getOrDefault(ThemeMode.DARK),
        accent = sp.getString("accent", AccentPreset.CYAN.name).let { n -> AccentPreset.entries.firstOrNull { it.name == n } },
        custom = sp.getLong("custom", 0xFF00E5FF),
        textSize = runCatching { TextSize.valueOf(sp.getString("text", null)!!) }.getOrDefault(TextSize.NORMAL),
        sidebarAutoHide = sp.getBoolean("sidebar_autohide", true),
        loadingScreen = sp.getBoolean("loading_screen", true),
    )

    fun update(change: (Appearance) -> Appearance) {
        val a = change(_state.value)
        sp.edit()
            .putString("theme", a.theme.name)
            .putString("accent", a.accent?.name ?: "CUSTOM")
            .putLong("custom", a.custom)
            .putString("text", a.textSize.name)
            .putBoolean("sidebar_autohide", a.sidebarAutoHide)
            .putBoolean("loading_screen", a.loadingScreen)
            .apply()
        _state.value = a
    }

    /** Re-reads after a backup is restored. */
    fun reload() { _state.value = read() }
}
