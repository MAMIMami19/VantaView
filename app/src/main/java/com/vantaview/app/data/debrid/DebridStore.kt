// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.debrid

import android.content.Context
import com.vantaview.app.data.security.CredentialCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connected debrid accounts, shared by every profile, in the order the user set (first = tried first).
 * Keys and tokens are encrypted with the Android Keystore before they are stored.
 */
@Singleton
class DebridStore @Inject constructor(
    @ApplicationContext context: Context,
    private val crypto: CredentialCrypto,
) {
    private val sp = context.getSharedPreferences("debrid", Context.MODE_PRIVATE)
    private val _accounts = MutableStateFlow(load())
    val accounts: StateFlow<List<DebridAccount>> = _accounts

    val all: List<DebridAccount> get() = _accounts.value

    private fun load(): List<DebridAccount> = runCatching {
        val arr = JSONArray(sp.getString("accounts", "[]"))
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            val service = DebridService.byId(o.optString("service")) ?: return@mapNotNull null
            DebridAccount(
                service = service,
                label = o.optString("label"),
                token = crypto.decrypt(o.optString("token")),
                refreshToken = o.optString("refresh").ifBlank { null }?.let(crypto::decrypt),
                clientId = o.optString("clientId").ifBlank { null },
                clientSecret = o.optString("clientSecret").ifBlank { null }?.let(crypto::decrypt),
                expiresAt = o.optLong("expiresAt"),
                premiumUntil = o.optLong("premiumUntil"),
            ).takeIf { it.token.isNotEmpty() }
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<DebridAccount>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject()
                    .put("service", a.service.id)
                    .put("label", a.label)
                    .put("token", crypto.encrypt(a.token))
                    .put("refresh", a.refreshToken?.let(crypto::encrypt).orEmpty())
                    .put("clientId", a.clientId.orEmpty())
                    .put("clientSecret", a.clientSecret?.let(crypto::encrypt).orEmpty())
                    .put("expiresAt", a.expiresAt)
                    .put("premiumUntil", a.premiumUntil)
            )
        }
        sp.edit().putString("accounts", arr.toString()).apply()
        _accounts.value = list
    }

    /** Adds or replaces the account for that service (one account per service). */
    @Synchronized
    fun put(a: DebridAccount) {
        val list = all.toMutableList()
        val i = list.indexOfFirst { it.service == a.service }
        if (i >= 0) list[i] = a else list += a
        save(list)
    }

    @Synchronized
    fun remove(service: DebridService) = save(all.filter { it.service != service })

    @Synchronized
    fun move(service: DebridService, delta: Int) {
        val list = all.toMutableList()
        val i = list.indexOfFirst { it.service == service }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list[i] = list[j].also { list[j] = list[i] }
        save(list)
    }

    fun get(service: DebridService): DebridAccount? = all.firstOrNull { it.service == service }
}
