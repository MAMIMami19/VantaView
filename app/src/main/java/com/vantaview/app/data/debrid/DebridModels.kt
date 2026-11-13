// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.debrid

/** Supported debrid services. */
enum class DebridService(val id: String, val label: String, val short: String, val keyPage: String) {
    REAL_DEBRID("rd", "Real-Debrid", "RD", "https://real-debrid.com/apitoken"),
    ALL_DEBRID("ad", "AllDebrid", "AD", "https://alldebrid.com/apikeys/"),
    TORBOX("tb", "TorBox", "TB", "https://torbox.app/settings"),
    PREMIUMIZE("pm", "Premiumize", "PM", "https://www.premiumize.me/account");

    /** Services that can show a code to enter on their website (no typing a key on a TV). */
    val hasCodeSignIn: Boolean get() = this == REAL_DEBRID || this == ALL_DEBRID

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
    }
}

/** A connected debrid account. Secrets are held decrypted only in memory. */
data class DebridAccount(
    val service: DebridService,
    val label: String,
    val token: String,
    val refreshToken: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
    /** Epoch ms when [token] expires (Real-Debrid sign-in), or 0 if it doesn't. */
    val expiresAt: Long = 0,
    /** Epoch ms of premium expiry when the service reports it, else 0. */
    val premiumUntil: Long = 0,
)

/** Whether a torrent is ready to play on a service. */
enum class Availability { CACHED, UNCACHED, UNKNOWN }

/** A file inside a torrent on a debrid service. */
data class DebridFile(val id: String, val name: String, val size: Long, val link: String? = null)

/** Result of trying to get a playable link. */
sealed interface Resolve {
    data class Ready(val url: String, val filename: String?) : Resolve
    /** Not cached and not started. */
    data object NotCached : Resolve
    /** Downloading on the service; [percent] when known. */
    data class Pending(val percent: Int?) : Resolve
    data class Failed(val message: String) : Resolve
}

/** What to resolve: the torrent and hints for picking the right file. */
data class TorrentRequest(
    val infoHash: String,
    val name: String?,
    val filename: String?,
    val fileIdx: Int?,
    val season: Int?,
    val episode: Int?,
    val trackers: List<String> = emptyList(),
) {
    val hash: String get() = infoHash.lowercase()
    val magnet: String
        get() = buildString {
            append("magnet:?xt=urn:btih:").append(hash)
            name?.let { append("&dn=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
            trackers.take(10).forEach { append("&tr=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
        }
}
