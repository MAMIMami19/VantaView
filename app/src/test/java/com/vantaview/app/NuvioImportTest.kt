// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import com.vantaview.app.data.addon.parseBackup
import com.vantaview.app.data.backup.NuvioImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NuvioImportTest {
    private val sample = """
        {"app":"NuvioSync","backupVersion":"1.0.0","original":{
          "library":[{"content_id":"tt1","content_type":"movie","name":"Film One","poster":"https://p/1.jpg","release_info":"2020","added_at":10}],
          "watched":[
            {"content_id":"tt2","content_type":"movie","title":"Film Two","watched_at":20},
            {"content_id":"tt3","content_type":"series","title":"Show","season":1,"episode":2,"watched_at":50},
            {"content_id":"tt3","content_type":"series","title":"Show","season":1,"episode":1,"watched_at":40},
            {"content_id":"x","content_type":"other","title":"Odd","watched_at":5}],
          "progress":[
            {"content_id":"tt3","content_type":"series","video_id":"tt3:1:3","season":1,"episode":3,"position":600000,"duration":2400000,"last_watched":60},
            {"content_id":"tt4","content_type":"movie","video_id":"tt4","season":0,"episode":0,"position":1000,"duration":0,"last_watched":70}],
          "addons":[{"url":"https://b.example/manifest.json","name":"B","enabled":true,"sort_order":1},
                    {"url":"https://a.example/manifest.json","name":"A","enabled":false,"sort_order":0}],
          "collections":[]}}
    """.trimIndent()

    @Test
    fun readsLibraryWatchedAndProgress() {
        assertTrue(NuvioImport.isNuvio(sample))
        val d = NuvioImport.parse(sample)
        assertEquals(listOf("tt1"), d.watchlist.map { it.id })
        assertEquals("Film One", d.watchlist[0].name)
        assertEquals(listOf("tt2"), d.watchedFilms.map { it.id })
        // The series continues from its newest point (episode 3, part-watched); the zero-length film is skipped.
        assertEquals(1, d.history.size)
        val h = d.history[0]
        assertEquals("tt3:1:3", h.videoId)
        assertEquals(600000L, h.positionMs)
        assertEquals("Show", h.title)
    }

    @Test
    fun addOnsComeOutInOrder() {
        assertEquals(listOf("https://a.example/manifest.json", "https://b.example/manifest.json"), parseBackup(sample).map { it.first })
    }
}
