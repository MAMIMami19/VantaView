// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every translation of "What's new" must have the same bullets as docs/WHATS-NEW.md, or the app falls back to English. */
class WhatsNewTest {
    private fun bullets(f: File) = f.readLines().map { it.trim() }.filter { it.startsWith("- ") || it.startsWith("* ") }

    @Test fun translationsMatchTheEnglishList() {
        val docs = listOf(File("../docs"), File("docs")).first { it.isDirectory }
        val english = bullets(File(docs, "WHATS-NEW.md"))
        val langs = com.vantaview.app.ui.I18n.languages.map { it.first }.filter { it != "en" }
        langs.forEach { l ->
            val f = File(docs, "whats-new/$l.md")
            assertTrue("docs/whats-new/$l.md is missing", f.exists())
            assertEquals("docs/whats-new/$l.md has a different number of bullets from docs/WHATS-NEW.md", english.size, bullets(f).size)
        }
    }
}
