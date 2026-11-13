// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.country

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class CountryDetectTest {
    @Test fun prefixes() {
        assertEquals("UK", CountryDetect.fromName("UK | Entertainment"))
        assertEquals("UK", CountryDetect.fromName("|UK| Sports"))
        assertEquals("UK", CountryDetect.fromName("GB: News"))
        assertEquals("DE", CountryDetect.fromName("DE - Kids"))
        assertEquals("FR", CountryDetect.fromName("[FR] Cinéma"))
        assertEquals("AR", CountryDetect.fromName("AR: MBC"))
        assertEquals("US", CountryDetect.fromName("USA | NFL"))
        assertEquals("EXYU", CountryDetect.fromName("EX-YU: Sport"))
        assertEquals("DE", CountryDetect.fromName("Germany | Movies"))
        assertEquals("UK", CountryDetect.fromName("United Kingdom: Documentary"))
        assertEquals("IN", CountryDetect.fromName("Hindi | Movies"))
    }

    @Test fun trailingAndSpace() {
        assertEquals("UK", CountryDetect.fromName("Sports (UK)"))
        assertEquals("PL", CountryDetect.fromName("News [PL]"))
        assertEquals("UK", CountryDetect.fromName("UK BBC One HD"))
    }

    @Test fun noCountry() {
        assertNull(CountryDetect.fromName("Entertainment"))
        assertNull(CountryDetect.fromName("News: World"))
        assertNull(CountryDetect.fromName("IT Crowd Marathon"))
        assertNull(CountryDetect.fromName("4K | Movies"))
        assertNull(CountryDetect.fromName("Kids: Cartoons"))
    }

    @Test fun guideIds() {
        assertEquals("UK", CountryDetect.fromGuideId("bbcone.uk"))
        assertEquals("US", CountryDetect.fromGuideId("cnn.us"))
        assertNull(CountryDetect.fromGuideId("channel.hd"))
        assertNull(CountryDetect.fromGuideId(null))
    }

    @Test fun iptvOrgGuideIds() {
        assertEquals("UK", CountryDetect.fromGuideId("BBCOne.uk@SD"))
        assertEquals("US", CountryDetect.fromGuideId("CNNInternational.us@HD"))
        assertEquals("DE", CountryDetect.fromGuideId("DasErste.de"))
    }

    @Test fun categoryNameWins() {
        assertEquals(listOf("UK", "UK"), CountryDetect.assign("UK | Sports", listOf("A" to null, "B" to "x.us")))
    }

    @Test fun mixedGenreCategoryUsesEachChannel() {
        // iptv-org style: "News" holds channels from many countries, tagged by guide id.
        val news = listOf("BBC News" to "BBCNews.uk@SD", "CNN" to "CNN.us@SD", "Tagesschau" to "Tagesschau24.de", "Mystery" to null)
        assertEquals(listOf("UK", "US", "DE", CountryDetect.OTHER), CountryDetect.assign("News", news))
    }

    @Test fun untaggedChannelsFollowTheCategoryMajority() {
        val ent = List(8) { "UK: Channel $it" to null } + List(2) { "Channel $it" to null }
        assertEquals(List(10) { "UK" }, CountryDetect.assign("Entertainment", ent))
        assertEquals(emptyList<String>(), CountryDetect.assign("Empty", emptyList()))
    }

    @Test fun device() {
        assertEquals("UK", CountryDetect.deviceCountry(Locale.UK))
        assertEquals("DE", CountryDetect.deviceCountry(Locale.GERMANY))
    }
}
