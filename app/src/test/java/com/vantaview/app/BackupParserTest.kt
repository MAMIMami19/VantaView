// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import com.vantaview.app.data.addon.parseBackup
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupParserTest {
    @Test
    fun vantaViewBackup() {
        val r = parseBackup("""{"app":"VantaView","addons":[{"manifestUrl":"https://a.example/manifest.json","name":"A"}]}""")
        assertEquals(listOf("https://a.example/manifest.json" to "A"), r)
    }

    @Test
    fun bootstrapperStremioBackup() {
        val r = parseBackup(
            """{"result":{"addons":[
                {"transportUrl":"https://v3-cinemeta.strem.io/manifest.json","manifest":{"name":"Cinemeta"}},
                {"transportUrl":"http://127.0.0.1:11470/local-addon/manifest.json","manifest":{"name":"Local Files"}}
            ]}}"""
        )
        assertEquals("Cinemeta", r[0].second)
        assertEquals(2, r.size)
    }

    @Test
    fun bootstrapperNuvioBackupKeepsSortOrder() {
        val r = parseBackup(
            """[
              {"id":"x","user_id":"u","url":"https://b.example/manifest.json","name":"B","enabled":true,"sort_order":1,"profile_id":1},
              {"id":"y","user_id":"u","url":"https://a.example/manifest.json","name":"A","enabled":false,"sort_order":0,"profile_id":1}
            ]"""
        )
        assertEquals(listOf("A", "B"), r.map { it.second })
    }

    @Test
    fun plainLinks() {
        val r = parseBackup("https://a.example/manifest.json\n\n  stremio://b.example/manifest.json \nnot a link")
        assertEquals(2, r.size)
    }
}
