// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.profile

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Which profile is using the app right now. Remembered between launches. */
@Singleton
class ProfileSession @Inject constructor(@ApplicationContext context: Context) {
    private val sp = context.getSharedPreferences("vantaview", Context.MODE_PRIVATE)
    private val _current = MutableStateFlow(sp.getLong("current_profile", 0L))
    val current: StateFlow<Long> = _current

    val id: Long get() = _current.value

    fun set(id: Long) {
        sp.edit().putLong("current_profile", id).apply()
        _current.value = id
    }

    /** Last Live TV playlist used, per profile. */
    fun lastProvider(): Long = sp.getLong("last_provider_$id", 0L)
    fun setLastProvider(providerId: Long) = sp.edit().putLong("last_provider_$id", providerId).apply()

    /** Last live channel watched, per profile (shown in Home's preview). */
    fun lastChannel(): Long = sp.getLong("last_channel_$id", 0L)
    fun setLastChannel(channelId: Long) {
        sp.edit().putLong("last_channel_$id", channelId).apply()
        _lastChannelChanges.value++
    }
    private val _lastChannelChanges = MutableStateFlow(0)
    /** Goes up whenever the last channel changes (or is cleared). */
    val lastChannelChanges: StateFlow<Int> = _lastChannelChanges

    /** The parent PIN that guards leaving a Kids profile and changing profiles. */
    var parentPinHash: String?
        get() = sp.getString("parent_pin_hash", null)
        set(v) = sp.edit().putString("parent_pin_hash", v).apply()
    var parentPinSalt: String?
        get() = sp.getString("parent_pin_salt", null)
        set(v) = sp.edit().putString("parent_pin_salt", v).apply()
}
