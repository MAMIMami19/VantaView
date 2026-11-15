// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VantaApp : Application() {
    @javax.inject.Inject lateinit var trakt: com.vantaview.app.data.trakt.TraktSync

    override fun onCreate() {
        super.onCreate()
        // Profiles linked to Trakt keep their Watchlist, watched and Continue watching in step.
        trakt.start()
        // Certificates the user chose to trust (Sources › Add-ons), read before any connection.
        com.vantaview.app.data.security.CertPins.init(this)
    }
}
