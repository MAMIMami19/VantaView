// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.profile

import com.vantaview.app.data.db.CategoryEntity
import com.vantaview.app.data.db.ChannelEntity

/** Rules for what a Kids profile may see. */
object KidsFilter {
    private val adult = Regex(
        "(?i)(\\bxxx\\b|\\badults?\\b|18\\s*\\+|\\+\\s*18|for adults|\\bporn|\\berotic|\\bsex\\b|\\bplayboy\\b|\\bhustler\\b|\\bbrazzers\\b|\\bredlight\\b|\\bnsfw\\b)"
    )

    fun looksAdult(name: String?): Boolean = name != null && adult.containsMatchIn(name)

    /** Family-friendly genre names used by add-on catalogues. */
    private val familyGenres = setOf("family", "kids", "children", "animation", "cartoon", "cartoons")

    fun isFamilyGenre(genre: String): Boolean = genre.trim().lowercase() in familyGenres

    fun hasFamilyGenre(genres: List<String>): Boolean = genres.any(::isFamilyGenre)

    private val familyWords = Regex("(?i)\\b(kids?|family|children|childrens|cartoons?|junior|disney junior|toons?)\\b")

    fun looksFamily(name: String): Boolean = familyWords.containsMatchIn(name)

    /** Categories a Kids profile can see on one playlist. [allowed] empty means the parent hasn't picked yet. */
    fun categories(all: List<CategoryEntity>, allowed: Set<String>): List<CategoryEntity> =
        all.filter { !looksAdult(it.name) && (allowed.isEmpty() || it.remoteId in allowed) }

    fun channelAllowed(c: ChannelEntity, adultCategoryIds: Set<String>, allowed: Set<String>): Boolean =
        !c.isAdult && !looksAdult(c.name) &&
            (c.categoryRemoteId == null || c.categoryRemoteId !in adultCategoryIds) &&
            (allowed.isEmpty() || (c.categoryRemoteId != null && c.categoryRemoteId in allowed))
}
