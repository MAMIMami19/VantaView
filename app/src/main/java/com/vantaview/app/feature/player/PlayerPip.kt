// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Picture-in-picture on phones and tablets: a player shrinks into a small window while you use other
 * apps (the PiP button, or pressing Home while it plays). TVs don't offer it.
 */
object PlayerPip {
    /** A player is on screen and playing, so leaving the app should shrink it rather than stop it. */
    var playing by mutableStateOf(false)
    /** The app is showing in a small picture-in-picture window. */
    var inPip by mutableStateOf(false)
    /** Settings › Playback › Picture-in-picture; kept in step with the profile's setting. */
    var enabled by mutableStateOf(true)

    fun supported(activity: Activity?): Boolean =
        enabled && activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            !activity.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    fun enter(activity: Activity?): Boolean {
        if (!supported(activity) || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching {
            activity!!.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }.getOrDefault(false)
    }
}
