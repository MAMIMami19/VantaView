// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Prefs @Inject constructor(@ApplicationContext context: Context) {
    private val sp = context.getSharedPreferences("vantaview", Context.MODE_PRIVATE)

    var termsAccepted: Boolean
        get() = sp.getBoolean("terms_accepted", false)
        set(value) = sp.edit().putBoolean("terms_accepted", value).apply()

    var lastProviderId: Long
        get() = sp.getLong("last_provider", 0)
        set(value) = sp.edit().putLong("last_provider", value).apply()
}
