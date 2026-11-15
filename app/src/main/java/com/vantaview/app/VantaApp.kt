// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VantaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Certificates the user chose to trust (Sources › Add-ons), read before any connection.
        com.vantaview.app.data.security.CertPins.init(this)
    }
}
