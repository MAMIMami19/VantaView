// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import com.vantaview.app.data.repo.CatchUp
import org.junit.Assert.assertEquals
import org.junit.Test

class CatchUpTest {
    // 2026-11-15 20:00:00 UTC to 21:00, watched at 22:00.
    private val start = 1_794_772_800_000L
    private val stop = start + 3_600_000L
    private val now = stop + 3_600_000L

    @Test
    fun xtreamTimeshiftInServerTime() {
        assertEquals(
            "http://tv.example.com:8080/timeshift/bob/pw/60/2026-11-15:20-00/123.ts",
            CatchUp.build("xc|UTC", "http://tv.example.com:8080/live/bob/pw/123.ts", start, stop, now),
        )
        assertEquals(
            "http://tv.example.com/timeshift/bob/pw/60/2026-11-15:21-00/7.ts",
            CatchUp.build("xc|Europe/Paris", "http://tv.example.com/bob/pw/7", start, stop, now),
        )
    }

    @Test
    fun m3uTemplates() {
        assertEquals(
            "http://a.example/ch.m3u8?utc=1794772800&lutc=1794780000",
            CatchUp.build("append|?utc={utc}&lutc={lutc}", "http://a.example/ch.m3u8", start, stop, now),
        )
        assertEquals(
            "http://arc.example/x/20261115/2000/3600.ts",
            CatchUp.build("default|http://arc.example/x/{Y}{m}{d}/{H}{M}/\${duration}.ts", "http://a.example/ch", start, stop, now),
        )
        assertEquals(
            "http://a.example/ch?token=1&utc=1794772800&lutc=1794780000",
            CatchUp.build("shift", "http://a.example/ch?token=1", start, stop, now),
        )
    }

    @Test
    fun flussonic() {
        assertEquals("http://f.example/ch1/index-1794772800-3600.m3u8?t=2", CatchUp.build("flussonic", "http://f.example/ch1/index.m3u8?t=2", start, stop, now))
        assertEquals("http://f.example/ch1/timeshift_abs-1794772800.ts", CatchUp.build("flussonic", "http://f.example/ch1/mpegts", start, stop, now))
    }
}
