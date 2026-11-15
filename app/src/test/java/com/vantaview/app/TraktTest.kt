// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import com.vantaview.app.data.trakt.TraktApi
import com.vantaview.app.data.trakt.TraktEpisode
import com.vantaview.app.data.trakt.TraktMerge
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TraktTest {
    @Test fun firstSyncKeepsBothSides() {
        val m = TraktMerge.merge(setOf("a", "b"), setOf("b", "c"), null)
        assertEquals(setOf("a", "b", "c"), m.result)
        assertEquals(setOf("a"), m.pushAdd)
        assertEquals(emptySet<String>(), m.pushRemove)
    }

    @Test fun removedOnThisDeviceIsRemovedFromTrakt() {
        val m = TraktMerge.merge(local = setOf("a"), remote = setOf("a", "b"), last = setOf("a", "b"))
        assertEquals(setOf("a"), m.result)
        assertEquals(setOf("b"), m.pushRemove)
        assertEquals(emptySet<String>(), m.pushAdd)
    }

    @Test fun removedOnTraktIsRemovedHere() {
        val m = TraktMerge.merge(local = setOf("a", "b"), remote = setOf("a"), last = setOf("a", "b"))
        assertEquals(setOf("a"), m.result)
        assertEquals(emptySet<String>(), m.pushRemove)
        assertEquals(emptySet<String>(), m.pushAdd)
    }

    @Test fun additionsOnBothSidesMeet() {
        val m = TraktMerge.merge(local = setOf("a", "x"), remote = setOf("a", "y"), last = setOf("a"))
        assertEquals(setOf("a", "x", "y"), m.result)
        assertEquals(setOf("x"), m.pushAdd)
    }

    @Test fun readsTitlesWithImdbIdsOnly() {
        val film = TraktApi.titleOf(JSONObject("""{"movie":{"title":"Heat","year":1995,"ids":{"imdb":"tt0113277","tmdb":949}}}"""))!!
        assertEquals("movie", film.type); assertEquals("tt0113277", film.imdb); assertEquals(949, film.tmdb)
        val show = TraktApi.titleOf(JSONObject("""{"show":{"title":"Doctor Who","ids":{"imdb":"tt31433814"}}}"""))!!
        assertEquals("series", show.type)
        assertNull(TraktApi.titleOf(JSONObject("""{"movie":{"title":"No id","ids":{"tmdb":1}}}""")))
    }

    @Test fun episodeVideoIdMatchesAddons() {
        assertEquals("tt31433814:1:2", TraktEpisode("tt31433814", 1, 2).videoId)
    }
}
