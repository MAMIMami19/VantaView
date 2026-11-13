// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import com.vantaview.app.data.debrid.DebridFile
import com.vantaview.app.data.debrid.FilePicker
import org.junit.Assert.assertEquals
import org.junit.Test

class FilePickerTest {
    private val pack = listOf(
        DebridFile("1", "Show.S02/Show.S02E01.1080p.mkv", 2_000),
        DebridFile("2", "Show.S02/Show.S02E02.1080p.mkv", 2_100),
        DebridFile("3", "Show.S02/Show.S02E10.1080p.mkv", 2_050),
        DebridFile("4", "Show.S02/Sample/show.sample.mkv", 50),
        DebridFile("5", "Show.S02/info.nfo", 1),
    )

    @Test
    fun picksEpisode() {
        assertEquals("2", FilePicker.pick(pack, null, 2, 2)?.id)
        assertEquals("3", FilePicker.pick(pack, null, 2, 10)?.id)
        assertEquals("1", FilePicker.pick(pack, null, 2, 1)?.id)
    }

    @Test
    fun prefersFilenameHint() {
        assertEquals("3", FilePicker.pick(pack, "Show.S02E10.1080p.mkv", 2, 1)?.id)
    }

    @Test
    fun filmTakesLargestNonSample() {
        val film = listOf(
            DebridFile("a", "Film.2019/Film.2019.2160p.mkv", 40_000),
            DebridFile("b", "Film.2019/Film.2019.sample.mkv", 900_000),
            DebridFile("c", "Film.2019/Film.nfo", 1),
        )
        assertEquals("a", FilePicker.pick(film, null, null, null)?.id)
    }

    @Test
    fun crossFormatEpisode() {
        val f = listOf(DebridFile("x", "show 1x05 name.mp4", 10), DebridFile("y", "show 1x50.mp4", 20))
        assertEquals("x", FilePicker.pick(f, null, 1, 5)?.id)
    }
}
