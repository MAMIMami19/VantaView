// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import com.vantaview.app.data.ChannelNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelNamesTest {
    @Test fun dividers() {
        assertTrue(ChannelNames.isDivider("✦●✦ SUPERSPORTS ✦●✦"))
        assertTrue(ChannelNames.isDivider("◆◆◆ CINEMA HD ◆◆◆"))
        assertTrue(ChannelNames.isDivider("♦♦♦UK SPORTS♦♦♦"))
        assertTrue(ChannelNames.isDivider("##########"))
        assertFalse(ChannelNames.isDivider("UK| SKY CINEMA ACTION HD"))
        assertFalse(ChannelNames.isDivider("UK| Amazon UK 02 [EVENT ONLY]"))
        assertFalse(ChannelNames.isDivider("BBC One (HD)"))
        assertEquals("SUPERSPORTS", ChannelNames.dividerTitle("✦●✦ SUPERSPORTS ✦●✦"))
        assertEquals("CINEMA HD", ChannelNames.dividerTitle("◆◆◆ CINEMA HD ◆◆◆"))
    }

    @Test fun displayNames() {
        assertEquals("SKY CINEMA ACTION HD", ChannelNames.display("UK| SKY CINEMA ACTION HD"))
        assertEquals("FOX SPORTS 507 HD", ChannelNames.display("UK | FOX SPORTS 507 HD"))
        assertEquals("BBC One", ChannelNames.display("[UK] BBC One"))
        assertEquals("Amazon UK 02", ChannelNames.display("UK| Amazon UK 02 [EVENT ONLY]"))
        assertEquals("BBC One", ChannelNames.display("BBC One"))
        assertEquals("Sky Sports F1", ChannelNames.display("Sky Sports F1"))
    }

    @Test fun eventOnly() {
        assertTrue(ChannelNames.isEventOnly("UK| Amazon UK 02 [EVENT ONLY]"))
        assertTrue(ChannelNames.isEventOnly("Solid Sport 1 (Event Only)"))
        assertFalse(ChannelNames.isEventOnly("UK| SKY CINEMA ACTION HD"))
    }
}
