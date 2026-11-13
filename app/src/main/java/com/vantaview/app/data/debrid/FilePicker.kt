// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.debrid

/** Chooses which file in a torrent to play. */
object FilePicker {
    private val videoExt = Regex("(?i)\\.(mkv|mp4|m4v|avi|mov|wmv|ts|m2ts|webm|mpg|mpeg|flv)$")
    private val sample = Regex("(?i)(\\bsample\\b|\\btrailer\\b|\\bextras?\\b|featurette)")

    fun isVideo(name: String) = videoExt.containsMatchIn(name)

    private fun base(name: String) = name.substringAfterLast('/').lowercase()

    private fun episodeRegex(season: Int, episode: Int) = Regex(
        "(?i)(s0*$season[ ._-]?e0*$episode(?!\\d)|\\b0*$season[x]0*$episode(?!\\d)|season[ ._-]?0*$season[ ._-]*episode[ ._-]?0*$episode(?!\\d))"
    )

    /**
     * Best file: the add-on's own filename hint if it matches; then, for an episode, a file named with that
     * season and episode; otherwise the largest video file that isn't a sample.
     */
    fun pick(files: List<DebridFile>, filename: String?, season: Int?, episode: Int?): DebridFile? {
        val videos = files.filter { isVideo(it.name) && !sample.containsMatchIn(base(it.name)) }.ifEmpty { files.filter { isVideo(it.name) } }
        if (videos.isEmpty()) return files.maxByOrNull { it.size }
        filename?.takeIf { it.isNotBlank() }?.lowercase()?.let { hint ->
            videos.firstOrNull { base(it.name) == hint.substringAfterLast('/') }?.let { return it }
        }
        if (season != null && episode != null) {
            val re = episodeRegex(season, episode)
            videos.filter { re.containsMatchIn(base(it.name)) }.maxByOrNull { it.size }?.let { return it }
        }
        return videos.maxByOrNull { it.size }
    }
}
