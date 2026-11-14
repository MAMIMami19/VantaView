// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.profile

import android.content.Context
import com.vantaview.app.data.db.CategoryEntity
import com.vantaview.app.data.db.ChannelEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings › Parental control, for every profile on this device (Kids profiles have their own,
 * stricter rules). Guarded by the parent PIN. Entering the PIN unlocks until the app closes.
 */
@Singleton
class ParentalControl @Inject constructor(@ApplicationContext context: Context) {
    private val sp = context.getSharedPreferences("parental", Context.MODE_PRIVATE)

    /** Goes up on every change, so lists can re-filter. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes
    private fun changed() = _changes.update { it + 1 }

    /** True after the parent PIN is entered; forgotten when the app closes. */
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked
    fun unlock() { _unlocked.value = true; changed() }
    fun lock() { _unlocked.value = false; changed() }

    var hideAdult: Boolean
        get() = sp.getBoolean("hide_adult", false)
        set(v) { sp.edit().putBoolean("hide_adult", v).apply(); changed() }

    var lockSources: Boolean
        get() = sp.getBoolean("lock_sources", false)
        set(v) { sp.edit().putBoolean("lock_sources", v).apply(); changed() }

    /** Adult categories are hidden right now (on, and not unlocked). */
    val hiding: Boolean get() = hideAdult && !_unlocked.value

    /** Sources needs the PIN right now. */
    val sourcesLocked: Boolean get() = lockSources && !_unlocked.value

    // ---- Which categories count as adult ----

    private fun marked(providerId: Long) = sp.getStringSet("adult_$providerId", emptySet())!!
    private fun unmarked(providerId: Long) = sp.getStringSet("notadult_$providerId", emptySet())!!

    /** Found automatically from the name, unless the user said it isn't. */
    fun isAuto(c: CategoryEntity): Boolean = KidsFilter.looksAdult(c.name)

    fun isAdult(c: CategoryEntity): Boolean =
        c.remoteId in marked(c.providerId) || (isAuto(c) && c.remoteId !in unmarked(c.providerId))

    fun setAdult(c: CategoryEntity, adult: Boolean) {
        val m = marked(c.providerId).toMutableSet()
        val u = unmarked(c.providerId).toMutableSet()
        m.remove(c.remoteId); u.remove(c.remoteId)
        if (adult && !isAuto(c)) m += c.remoteId
        if (!adult && isAuto(c)) u += c.remoteId
        sp.edit().putStringSet("adult_${c.providerId}", m).putStringSet("notadult_${c.providerId}", u).apply()
        changed()
    }

    fun adultIds(cats: List<CategoryEntity>): Set<String> = cats.filter(::isAdult).map { it.remoteId }.toSet()

    /** A channel is hidden when its category is adult, or the playlist or its name says it is. */
    fun hidden(c: ChannelEntity, adultIds: Set<String>): Boolean {
        val cat = c.categoryRemoteId
        if (cat != null && cat in adultIds) return true
        if (cat != null && cat in unmarked(c.providerId)) return false
        return c.isAdult || KidsFilter.looksAdult(c.name)
    }
}
