// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vantaview.app.data.db.CategoryEntity
import com.vantaview.app.data.db.ProfileEntity
import com.vantaview.app.data.db.ProviderEntity
import com.vantaview.app.data.db.VantaDb
import com.vantaview.app.data.profile.ProfileRepository
import com.vantaview.app.data.profile.ProfileSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class PlaylistCategories(val provider: ProviderEntity, val categories: List<CategoryEntity>)

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    val repo: ProfileRepository,
    private val session: ProfileSession,
    private val db: VantaDb,
) : ViewModel() {
    val profiles: StateFlow<List<ProfileEntity>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val currentId: Long get() = session.id

    suspend fun current(): ProfileEntity? = repo.current()

    /** Live TV categories on a profile's own playlists, for the Kids "allowed channels" list. */
    suspend fun categoriesFor(profileId: Long): List<PlaylistCategories> =
        db.providers().allFor(profileId).map { p ->
            PlaylistCategories(p, db.content().categories(p.id).filter { !com.vantaview.app.data.profile.KidsFilter.looksAdult(it.name) })
        }
}
