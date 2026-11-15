// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.remote

/** Works out which entries in a playlist are radio stations rather than TV channels. */
object RadioDetect {
    private val word = Regex("""(?i)(\bradio\b|\bfm\b|\d{2,3}[.,]\d\s?fm|\bam\s?\d{3,4}\b|rádio|радио)""")

    /** "Radio Televisión…", "Radio Canada TV" and the like are TV channels. */
    private val tv = Regex("""(?i)(\btv\b|televi|télé|телев|\bhd\b)""")
    private val radioGroup = Regex("""(?i)(\bradio|rádio|радио)""")

    fun isRadio(name: String, group: String?, flagged: Boolean = false): Boolean =
        flagged || (!tv.containsMatchIn(name) && (word.containsMatchIn(name) || (group != null && radioGroup.containsMatchIn(group))))
}
