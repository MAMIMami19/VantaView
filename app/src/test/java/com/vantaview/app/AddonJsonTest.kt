// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import com.vantaview.app.data.addon.AddonJson
import com.vantaview.app.data.addon.Manifest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonJsonTest {
    private val manifest = Manifest.parse(
        JSONObject(
            """
            {"id":"org.test","name":"Test","version":"1.0.0","types":["movie","series"],"idPrefixes":["tt"],
             "resources":["catalog",{"name":"meta","types":["series"],"idPrefixes":["tt"]},"stream"],
             "catalogs":[
               {"type":"movie","id":"top","name":"Top","extra":[{"name":"search"},{"name":"skip"}]},
               {"type":"movie","id":"find","name":"Find","extra":[{"name":"search","isRequired":true}]},
               {"type":"series","id":"old","name":"Old style","extraSupported":["search"],"extraRequired":["search"]}
             ]}
            """.trimIndent()
        )
    )

    @Test
    fun parsesCatalogsAndResources() {
        assertEquals(3, manifest.catalogs.size)
        assertTrue(manifest.catalogs[0].searchable)
        assertFalse(manifest.catalogs[0].needsExtra)
        assertTrue(manifest.catalogs[1].needsExtra)
        assertTrue(manifest.catalogs[2].needsExtra)
        assertTrue(manifest.serves("stream", "movie", "tt123"))
        assertFalse(manifest.serves("stream", "movie", "kitsu:1"))
        assertTrue(manifest.serves("meta", "series", "tt1"))
        assertFalse(manifest.serves("meta", "movie", "tt1"))
    }

    @Test
    fun normalizesInstallLinks() {
        assertEquals("https://a.example.com/x/manifest.json", Manifest.normalizeUrl("stremio://a.example.com/x/manifest.json"))
        assertEquals("https://a.example.com/x/manifest.json", Manifest.normalizeUrl("a.example.com/x/"))
        assertEquals("http://a.example.com/manifest.json", Manifest.normalizeUrl("http://a.example.com/manifest.json#top"))
    }

    @Test
    fun keepsDirectLinksAndRanksByQuality() {
        val json = JSONObject(
            """
            {"streams":[
              {"name":"A 720p","title":"Film.720p.mkv\n1.2 GB","url":"https://cdn.example.com/a.mkv"},
              {"name":"B","title":"Film 2160p HDR","url":"https://cdn.example.com/b.mkv",
               "behaviorHints":{"proxyHeaders":{"request":{"Referer":"https://site.example/"}},"videoSize":4294967296}},
              {"name":"Torrent 1080p","infoHash":"abcdef"},
              {"name":"Web","externalUrl":"https://example.com"},
              {"name":"C 1080p","url":"https://cdn.example.com/c.m3u8"}
            ]}
            """.trimIndent()
        )
        val list = AddonJson.streams(json, "Test", 0)
        assertEquals(3, list.size)
        val ranked = AddonJson.rank(list)
        assertEquals(listOf(2160, 1080, 720), ranked.map { it.quality })
        assertEquals("https://site.example/", ranked[0].headers["Referer"])
        assertEquals(4294967296L, ranked[0].sizeBytes)
        assertEquals(1288490188L, ranked[2].sizeBytes)
    }

    @Test
    fun readsSeriesEpisodesInOrder() {
        val meta = AddonJson.detail(
            JSONObject(
                """
                {"id":"tt1","type":"series","name":"Show","videos":[
                  {"id":"tt1:2:1","title":"S2E1","season":2,"episode":1},
                  {"id":"tt1:0:1","title":"Special","season":0,"episode":1},
                  {"id":"tt1:1:2","name":"S1E2","season":1,"episode":2},
                  {"id":"tt1:1:1","title":"S1E1","season":1,"episode":1}
                ]}
                """.trimIndent()
            ),
            "series",
        )
        assertEquals(listOf("tt1:1:1", "tt1:1:2", "tt1:2:1", "tt1:0:1"), meta.videos.map { it.id })
        assertEquals("S1E2", meta.videos[1].title)
    }
}
