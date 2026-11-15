// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.trakt

import android.content.Context
import com.vantaview.app.data.security.CredentialCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What a profile's Trakt link keeps in step. */
data class TraktOptions(
    val watchlist: Boolean = true,
    val history: Boolean = true,
    val progress: Boolean = true,
    val lists: Boolean = true,
)

/** A profile's link to a Trakt account. */
data class TraktLink(val username: String, val tokens: TraktTokens, val options: TraktOptions, val lastSync: Long)

/**
 * Trakt links, one per profile. Tokens are encrypted with the Android Keystore before they are stored.
 * Also keeps, per profile, what was in step at the last sync, so later syncs can tell additions from removals.
 */
@Singleton
class TraktStore @Inject constructor(
    @ApplicationContext context: Context,
    private val crypto: CredentialCrypto,
) {
    private val sp = context.getSharedPreferences("trakt", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)
    /** Changes whenever a link, its options or its last sync time change. */
    val version: StateFlow<Int> = _version
    private fun bump() { _version.value++ }

    fun link(profile: Long): TraktLink? {
        val access = sp.getString("access_$profile", null)?.let { runCatching { crypto.decrypt(it) }.getOrNull() } ?: return null
        val refresh = sp.getString("refresh_$profile", null)?.let { runCatching { crypto.decrypt(it) }.getOrNull() }.orEmpty()
        if (access.isEmpty()) return null
        return TraktLink(
            username = sp.getString("user_$profile", "").orEmpty(),
            tokens = TraktTokens(access, refresh, sp.getLong("expires_$profile", 0L)),
            options = TraktOptions(
                watchlist = sp.getBoolean("o_watch_$profile", true),
                history = sp.getBoolean("o_hist_$profile", true),
                progress = sp.getBoolean("o_prog_$profile", true),
                lists = sp.getBoolean("o_lists_$profile", true),
            ),
            lastSync = sp.getLong("synced_$profile", 0L),
        )
    }

    fun saveTokens(profile: Long, t: TraktTokens, username: String? = null) {
        sp.edit().putString("access_$profile", crypto.encrypt(t.access)).putString("refresh_$profile", crypto.encrypt(t.refresh))
            .putLong("expires_$profile", t.expiresAt).apply {
                if (username != null) putString("user_$profile", username)
            }.apply()
        bump()
    }

    fun setOptions(profile: Long, o: TraktOptions) {
        sp.edit().putBoolean("o_watch_$profile", o.watchlist).putBoolean("o_hist_$profile", o.history)
            .putBoolean("o_prog_$profile", o.progress).putBoolean("o_lists_$profile", o.lists).apply()
        bump()
    }

    fun markSynced(profile: Long) { sp.edit().putLong("synced_$profile", System.currentTimeMillis()).apply(); bump() }

    fun unlink(profile: Long) {
        val e = sp.edit()
        sp.all.keys.filter { it.endsWith("_$profile") }.forEach { e.remove(it) }
        e.apply()
        bump()
    }

    // ---- What was in step at the last sync ("snapshots"), as keys like "movie:tt123" or "tt123:1:2" ----

    fun snapshot(profile: Long, name: String): Set<String>? =
        if (sp.contains("snap_${name}_$profile")) sp.getStringSet("snap_${name}_$profile", emptySet()).orEmpty().toSet() else null

    fun saveSnapshot(profile: Long, name: String, keys: Set<String>) { sp.edit().putStringSet("snap_${name}_$profile", keys).apply() }
}
