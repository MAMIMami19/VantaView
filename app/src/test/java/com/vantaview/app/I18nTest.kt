// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import com.vantaview.app.ui.I18n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class I18nTest {
    private val table = mapOf(
        "Settings" to "Ajustes",
        "Season {0}" to "Temporada {0}",
        "Channel {0#}" to "Canal {0}",
        "{0} channels ready" to "{0} canales listos",
        "Paused" to "En pausa",
        "Starts at {0} on {1}. Tap to watch." to "Empieza a las {0} en {1}. Toca para verlo.",
        "▶ Watch" to "▶ Ver",
    )

    @After fun back() = I18n.setTable("en", emptyMap())

    @Test fun translatesWholeLinesAndTemplates() {
        I18n.setTable("es", table)
        assertEquals("Ajustes", I18n.tr("Settings"))
        assertEquals("Temporada 3", I18n.tr("Season 3"))
        assertEquals("1200 canales listos", I18n.tr("1200 channels ready"))
        assertEquals("Empieza a las 20:00 en BBC One. Toca para verlo.", I18n.tr("Starts at {0} on {1}. Tap to watch.", "20:00", "BBC One"))
    }

    @Test fun leavesNamesAlone() {
        I18n.setTable("es", table)
        assertEquals("BBC One HD", I18n.tr("BBC One HD"))
        assertEquals("Canal 5", I18n.tr("Channel 5"))
        assertEquals("Channel One", I18n.tr("Channel One"))
    }

    @Test fun translatesPiecesAndSymbols() {
        I18n.setTable("es", table)
        assertEquals("En pausa · −0:42", I18n.tr("Paused · −0:42"))
        assertEquals("▶ Ver", I18n.tr("▶ Watch"))
        assertEquals("★ Ajustes", I18n.tr("★ Settings"))
    }

    @Test fun englishIsUnchanged() {
        I18n.setTable("en", emptyMap())
        assertEquals("Season 3", I18n.tr("Season 3"))
    }
}
