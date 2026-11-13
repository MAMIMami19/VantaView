// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import com.vantaview.app.data.db.CategoryEntity
import com.vantaview.app.data.db.ChannelEntity
import com.vantaview.app.data.profile.KidsFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KidsFilterTest {
    private fun cat(id: String, name: String) = CategoryEntity(providerId = 1, remoteId = id, name = name, position = 0)
    private fun ch(name: String, cat: String?, adult: Boolean = false) =
        ChannelEntity(providerId = 1, remoteId = name, name = name, sortKey = name, number = 1, categoryRemoteId = cat, streamUrl = "x", isAdult = adult)

    @Test
    fun spotsAdultNames() {
        listOf("XXX", "UK | ADULTS", "Adult 18+", "+18 Channels", "For Adults Only", "NSFW").forEach { assertTrue(it, KidsFilter.looksAdult(it)) }
        listOf("Kids", "Sports", "Essex News", "Middlesex FM", "Documentary").forEach { assertFalse(it, KidsFilter.looksAdult(it)) }
    }

    @Test
    fun filtersCategoriesAndChannels() {
        val cats = listOf(cat("1", "Kids"), cat("2", "News"), cat("3", "XXX"))
        assertEquals(listOf("1", "2"), KidsFilter.categories(cats, emptySet()).map { it.remoteId })
        assertEquals(listOf("1"), KidsFilter.categories(cats, setOf("1")).map { it.remoteId })

        val adultCats = setOf("3")
        assertTrue(KidsFilter.channelAllowed(ch("Cartoon Fun", "1"), adultCats, emptySet()))
        assertFalse(KidsFilter.channelAllowed(ch("Hot TV", "3"), adultCats, emptySet()))
        assertFalse(KidsFilter.channelAllowed(ch("Plain", "2", adult = true), adultCats, emptySet()))
        assertFalse(KidsFilter.channelAllowed(ch("News 24", "2"), adultCats, setOf("1")))
        assertTrue(KidsFilter.channelAllowed(ch("Cartoon Fun", "1"), adultCats, setOf("1")))
    }

    @Test
    fun familyGenres() {
        assertTrue(KidsFilter.hasFamilyGenre(listOf("Drama", "Family")))
        assertTrue(KidsFilter.hasFamilyGenre(listOf("Animation")))
        assertFalse(KidsFilter.hasFamilyGenre(listOf("Horror", "Thriller")))
        assertFalse(KidsFilter.hasFamilyGenre(emptyList()))
        assertTrue(KidsFilter.looksFamily("Kids Movies"))
        assertFalse(KidsFilter.looksFamily("Top Movies"))
    }
}
