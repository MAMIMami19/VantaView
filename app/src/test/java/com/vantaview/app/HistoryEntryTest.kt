// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import com.vantaview.app.data.history.HistoryEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryEntryTest {
    private fun e(pos: Long, dur: Long) = HistoryEntry("movie", "tt1", "tt1", "Film", null, null, null, null, null, pos, dur, 0)

    @Test
    fun finishedAndResumable() {
        val hour = 3_600_000L
        assertFalse(e(30_000, hour).resumable)          // under a minute in
        assertTrue(e(20 * 60_000, hour).resumable)      // part-way
        assertTrue(e((hour * 0.95).toLong(), hour).finished)
        assertTrue(e(hour - 120_000, hour).finished)     // credits: under 3 minutes left
        assertFalse(e(hour - 120_000, hour).resumable)
    }
}
