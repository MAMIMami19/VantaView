// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import com.vantaview.app.data.remote.SmartAdd
import com.vantaview.app.data.remote.SmartAddResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartAddTest {
    private fun xtream(server: String, user: String, pass: String) = SmartAddResult.Xtream(server, user, pass)

    @Test
    fun getPhpLink() {
        assertEquals(
            xtream("http://tv.example.com:8080", "bob", "s3cret"),
            SmartAdd.parse("Here is your line: http://tv.example.com:8080/get.php?username=bob&password=s3cret&type=m3u_plus&output=ts"),
        )
    }

    @Test
    fun playerApiLink() {
        assertEquals(
            xtream("https://tv.example.com", "bob", "p w"),
            SmartAdd.parse("https://tv.example.com/player_api.php?username=bob&password=p%20w"),
        )
    }

    @Test
    fun livePathLink() {
        assertEquals(
            xtream("http://tv.example.com:80", "bob", "s3cret"),
            SmartAdd.parse("http://tv.example.com:80/live/bob/s3cret/1234.ts"),
        )
    }

    @Test
    fun labelledMessage() {
        val message = """
            Thanks for your order!
            Server URL: http://line.example.net:2095
            Username: alice99
            Password: Xy7_kk2
            Enjoy.
        """.trimIndent()
        assertEquals(xtream("http://line.example.net:2095", "alice99", "Xy7_kk2"), SmartAdd.parse(message))
    }

    @Test
    fun labelledMessageWithoutScheme() {
        val message = "host = line.example.net:8000\nuser = carl\npass = 1234"
        assertEquals(xtream("http://line.example.net:8000", "carl", "1234"), SmartAdd.parse(message))
    }

    @Test
    fun labelledMessageWithEmoji() {
        val message = "✅ Portal: http://p.example.org:8880/\n👤 Login: dave\n🔑 Password: qwerty"
        assertEquals(xtream("http://p.example.org:8880", "dave", "qwerty"), SmartAdd.parse(message))
    }

    @Test
    fun plainM3uLink() {
        assertEquals(
            SmartAddResult.M3u("https://example.com/lists/uk.m3u8"),
            SmartAdd.parse("try this https://example.com/lists/uk.m3u8."),
        )
    }

    @Test
    fun otherLinkIsTreatedAsM3u() {
        assertEquals(SmartAddResult.M3u("http://example.com/playlist"), SmartAdd.parse("http://example.com/playlist"))
    }

    @Test
    fun nothingFound() {
        assertEquals(SmartAddResult.None, SmartAdd.parse("hello there, no details here"))
    }

    @Test
    fun normalizeServer() {
        assertEquals("http://a.example.com:8080", SmartAdd.normalizeServer("a.example.com:8080/"))
        assertEquals("https://a.example.com", SmartAdd.normalizeServer("HTTPS://a.example.com/player_api.php"))
        assertEquals("http://a.example.com/panel", SmartAdd.normalizeServer("http://a.example.com/panel/"))
        assertEquals("http://a.example.com/panel", SmartAdd.normalizeServer("http://a.example.com/panel/get.php?username=x"))
        assertEquals("http://a.example.com:80", SmartAdd.normalizeServer("a.example.com:80/live/u/p/1.ts"))
    }

    @Test
    fun stalkerPortalAndMac() {
        assertEquals(
            SmartAddResult.Stalker("http://portal.example.com:8080/c/", "00:1A:79:AB:CD:EF"),
            SmartAdd.parse("Portal: http://portal.example.com:8080/c/\nMAC: 00:1a:79:ab:cd:ef\nExpires 2027"),
        )
    }

    @Test
    fun stalkerMacWithDashesAndUnlabelledPortal() {
        assertEquals(
            SmartAddResult.Stalker("http://tv.example.com/stalker_portal/c", "00:1A:79:00:11:22"),
            SmartAdd.parse("Your box 00-1A-79-00-11-22 is active on http://tv.example.com/stalker_portal/c"),
        )
    }

    @Test
    fun xtreamLinkStillWinsOverAMac() {
        assertEquals(
            xtream("http://tv.example.com:8080", "bob", "pw"),
            SmartAdd.parse("http://tv.example.com:8080/get.php?username=bob&password=pw (box 00:1A:79:00:11:22)"),
        )
    }

    @Test
    fun macNormalising() {
        assertEquals("00:1A:79:AB:CD:EF", com.vantaview.app.data.stalker.StalkerClient.normalizeMac("001a79abcdef"))
        assertEquals("00:1A:79:AB:CD:EF", com.vantaview.app.data.stalker.StalkerClient.normalizeMac(" 00-1a-79-ab-cd-ef "))
        assertEquals(null, com.vantaview.app.data.stalker.StalkerClient.normalizeMac("00:1A:79:AB:CD"))
        assertEquals(null, com.vantaview.app.data.stalker.StalkerClient.normalizeMac("zz:1A:79:AB:CD:EF"))
    }
}
