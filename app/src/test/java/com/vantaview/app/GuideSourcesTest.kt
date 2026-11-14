// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import com.vantaview.app.data.guide.GuideSources
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideSourcesTest {
    private val base = "https://epgshare01.online/epgshare01/epg_ripper_"
    private val urls = listOf("AL1", "ALJAZEERA1", "ALL_SOURCES1", "IN1", "IN4", "UK1", "US1", "US_LOCALS2", "IT1")
        .map { "$base$it.xml.gz" }

    @Test fun splitsAndTrims() {
        assertEquals(listOf("http://a/x.xml", "https://b/y.xml.gz"), GuideSources.split("http://a/x.xml, https://b/y.xml.gz ,junk"))
    }

    @Test fun singleLinkIsKept() {
        assertEquals(listOf("http://a/x.xml"), GuideSources.pick(listOf("http://a/x.xml"), emptyList()))
    }

    @Test fun picksCountriesOfTheChannels() {
        val keys = listOf("BBCOne.uk", "BBCTwo.uk", "ITV1.uk", "Rai1.it")
        assertEquals(listOf("${base}UK1.xml.gz", "${base}IT1.xml.gz"), GuideSources.pick(urls, keys))
    }

    @Test fun keepsEveryFileForACountryButNotSpecialOnes() {
        assertEquals(listOf("${base}IN1.xml.gz", "${base}IN4.xml.gz"), GuideSources.pick(urls, listOf("DDNational.in")))
        assertEquals(listOf("${base}US1.xml.gz"), GuideSources.pick(urls, listOf("Fox.us")))
    }

    @Test fun fallsBackToTheFirstLink() {
        assertEquals(listOf("${base}AL1.xml.gz"), GuideSources.pick(urls, listOf("Something.com")))
    }

    @Test fun capsHowManyAreFetched() {
        assertEquals(2, GuideSources.pick(urls, listOf("a.uk", "b.it", "c.us", "d.in"), max = 2).size)
    }
}
