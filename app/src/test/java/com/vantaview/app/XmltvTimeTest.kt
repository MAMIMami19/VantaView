// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import com.vantaview.app.data.guide.XmltvTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XmltvTimeTest {
    // 2026-11-14 19:00:00 UTC
    private val base = 1794682800000L

    @Test fun utc() = assertEquals(base, XmltvTime.parse("20261114190000 +0000"))
    @Test fun noOffset() = assertEquals(base, XmltvTime.parse("20261114190000"))
    @Test fun noSeconds() = assertEquals(base, XmltvTime.parse("202611141900"))
    @Test fun plusOne() = assertEquals(base - 3_600_000L, XmltvTime.parse("20261114190000 +0100"))
    @Test fun minusFive() = assertEquals(base + 5 * 3_600_000L, XmltvTime.parse("20261114190000 -0500"))
    @Test fun colonOffset() = assertEquals(base - 3_600_000L, XmltvTime.parse("20261114190000 +01:00"))
    @Test fun rubbish() = assertNull(XmltvTime.parse("soon"))
}
