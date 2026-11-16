// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.browse

import android.content.Context
import com.vantaview.app.data.security.CredentialCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's own TMDB API key, when they entered one in Settings › TMDB. The key is encrypted
 * with the Android Keystore before it is stored (same as provider passwords); only the masked
 * form (•••• plus the last 4 characters) is ever shown. Builds without a TMDB key need one for
 * film and series details in the user's language — otherwise Cinemeta (English only) fills in.
 */
@Singleton
class TmdbKeyStore @Inject constructor(
    @ApplicationContext context: Context,
    private val crypto: CredentialCrypto,
) {
    private val sp = context.getSharedPreferences("tmdb", Context.MODE_PRIVATE)

    private val _key = MutableStateFlow(load())
    val key: StateFlow<String?> = _key

    val current: String? get() = _key.value

    val hasKey: Boolean get() = !current.isNullOrBlank()

    /** Masked display, e.g. ••••3298. */
    val masked: String? get() = current?.let(::mask)

    private fun load(): String? =
        sp.getString("api_key", null)?.takeIf { it.isNotBlank() }?.let(crypto::decrypt)?.takeIf { it.isNotBlank() }

    @Synchronized
    fun set(key: String?) {
        val clean = key?.trim()?.takeIf { it.isNotBlank() }
        sp.edit().putString("api_key", clean?.let(crypto::encrypt).orEmpty()).apply()
        _key.value = clean
    }

    fun clear() = set(null)

    companion object {
        /** A key shown masked: •••• plus its last 4 characters (just •••• when too short to mask). */
        fun mask(key: String): String = if (key.length > 8) "••••" + key.takeLast(4) else "••••"
    }
}
