// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.profile

import android.util.Base64
import com.vantaview.app.data.db.KidsCategoryEntity
import com.vantaview.app.data.db.ProfileEntity
import com.vantaview.app.data.db.VantaDb
import com.vantaview.app.data.repo.ProviderRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ProfileRepository @Inject constructor(
    private val db: VantaDb,
    private val session: ProfileSession,
    private val providers: ProviderRepository,
) {
    fun observeAll(): Flow<List<ProfileEntity>> = db.profiles().observeAll()

    /** The profile in use, or null before the first one is made. */
    fun observeCurrent(): Flow<ProfileEntity?> = session.current.flatMapLatest { db.profiles().observe(it) }

    suspend fun all(): List<ProfileEntity> = db.profiles().all()

    suspend fun get(id: Long): ProfileEntity? = db.profiles().get(id)

    suspend fun current(): ProfileEntity? = db.profiles().get(session.id)

    suspend fun isKids(): Boolean = current()?.isKids == true

    /**
     * Makes a profile. The first profile takes over anything added before profiles existed.
     * [copyFrom] copies that profile's playlists and add-ons, which then belong to the new profile.
     */
    suspend fun create(name: String, avatar: Int, kids: Boolean, pin: String?, copyFrom: Long?): Long {
        require(name.isNotBlank()) { "Give the profile a name" }
        val first = db.profiles().all().isEmpty()
        val (hash, salt) = pin?.takeIf { it.isNotEmpty() }?.let { hashPin(it) } ?: (null to null)
        val id = db.profiles().insert(
            ProfileEntity(name = name.trim(), avatar = avatar, isKids = kids, pinHash = hash, pinSalt = salt)
        )
        if (first) {
            db.providers().adoptOrphans(id)
            db.addons().adoptOrphans(id)
        }
        if (copyFrom != null && copyFrom != id) {
            providers.copyAll(copyFrom, id)
            db.addons().allFor(copyFrom).forEach { a ->
                db.addons().upsert(a.copy(id = 0, profileId = id, addedAt = System.currentTimeMillis()))
            }
        }
        return id
    }

    suspend fun update(p: ProfileEntity, newPin: String?, removePin: Boolean) {
        val (hash, salt) = when {
            removePin -> null to null
            !newPin.isNullOrEmpty() -> hashPin(newPin)
            else -> p.pinHash to p.pinSalt
        }
        db.profiles().update(p.copy(name = p.name.trim(), pinHash = hash, pinSalt = salt))
    }

    /** Deletes a profile with its playlists, favourites and add-ons. The last profile can't be deleted. */
    suspend fun delete(id: Long) {
        require(db.profiles().all().size > 1) { "There must be at least one profile" }
        providers.deleteAllFor(id)
        db.addons().deleteFor(id)
        db.profiles().clearKidsCategories(id)
        db.profiles().delete(id)
        if (session.id == id) session.set(db.profiles().all().firstOrNull { !it.isKids }?.id ?: db.profiles().all().first().id)
    }

    fun select(id: Long) = session.set(id)

    fun checkPin(p: ProfileEntity, pin: String): Boolean =
        p.pinHash == null || (p.pinSalt != null && hashPin(pin, p.pinSalt).first == p.pinHash)

    val hasParentPin: Boolean get() = session.parentPinHash != null

    fun setParentPin(pin: String) {
        require(pin.length == 4 && pin.all { it.isDigit() }) { "The parent PIN must be 4 digits" }
        val (h, s) = hashPin(pin)
        session.parentPinHash = h
        session.parentPinSalt = s
    }

    fun checkParentPin(pin: String): Boolean {
        val h = session.parentPinHash ?: return true
        val s = session.parentPinSalt ?: return false
        return hashPin(pin, s).first == h
    }

    fun observeKidsCategories(profileId: Long): Flow<List<KidsCategoryEntity>> = db.profiles().observeKidsCategories(profileId)

    suspend fun setKidsCategory(profileId: Long, providerId: Long, remoteId: String, allowed: Boolean) {
        if (allowed) db.profiles().addKidsCategory(KidsCategoryEntity(profileId, providerId, remoteId))
        else db.profiles().removeKidsCategory(profileId, providerId, remoteId)
    }

    private fun hashPin(pin: String, saltB64: String? = null): Pair<String, String> {
        val salt = saltB64?.let { Base64.decode(it, Base64.NO_WRAP) } ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
        val digest = MessageDigest.getInstance("SHA-256").apply { update(salt) }.digest(pin.toByteArray())
        return Base64.encodeToString(digest, Base64.NO_WRAP) to Base64.encodeToString(salt, Base64.NO_WRAP)
    }

}
