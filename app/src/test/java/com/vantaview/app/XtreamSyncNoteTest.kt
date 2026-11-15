// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import com.vantaview.app.data.repo.syncNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The warning on a playlist card when a sync only partly worked. */
class XtreamSyncNoteTest {

    @Test fun nothingFailedGivesNoNote() {
        assertNull(syncNote(emptyList(), false))
    }

    @Test fun bothVodKindsFailed() {
        assertEquals(
            "Films and series did not load · Try Refresh to try again",
            syncNote(listOf("movie", "series"), false),
        )
    }

    @Test fun oneVodKindFailed() {
        assertEquals("Films did not load · Try Refresh to try again", syncNote(listOf("movie"), false))
        assertEquals("Series did not load · Try Refresh to try again", syncNote(listOf("series"), false))
    }

    @Test fun liveFailedToo() {
        assertEquals("Some channels did not load · Try Refresh to try again", syncNote(emptyList(), true))
        assertEquals(
            "Films and series did not load · Some channels did not load · Try Refresh to try again",
            syncNote(listOf("movie", "series"), true),
        )
    }
}
