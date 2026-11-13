// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.addon

import javax.inject.Inject
import javax.inject.Singleton

/** What the player should play next for a film or episode: the ranked streams and which one to start with. */
data class VodRequest(
    val title: String,
    val subtitle: String?,
    val streams: List<AddonStream>,
    val startIndex: Int = 0,
)

/** Hands a film or episode from the details page to the player. */
@Singleton
class VodSession @Inject constructor() {
    @Volatile var current: VodRequest? = null
}
