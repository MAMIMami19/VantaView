// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import com.vantaview.app.data.remote.RadioDetect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioDetectTest {
    @Test
    fun radioStations() {
        assertTrue(RadioDetect.isRadio("BBC Radio 2", null))
        assertTrue(RadioDetect.isRadio("Kiss FM", null))
        assertTrue(RadioDetect.isRadio("Capital 95.8 FM", null))
        assertTrue(RadioDetect.isRadio("Absolute 80s", "Radio"))
        assertTrue(RadioDetect.isRadio("Anything", null, flagged = true))
    }

    @Test
    fun tvChannels() {
        assertFalse(RadioDetect.isRadio("BBC One", "UK"))
        assertFalse(RadioDetect.isRadio("Radio Televisión Española", null))
        assertFalse(RadioDetect.isRadio("Radio Canada TV", null))
        assertFalse(RadioDetect.isRadio("Film4", "Movies"))
    }
}
