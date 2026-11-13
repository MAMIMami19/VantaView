// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {
    @Query("SELECT * FROM provider ORDER BY createdAt")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM provider WHERE id = :id")
    suspend fun get(id: Long): ProviderEntity?

    @Query("SELECT COUNT(*) FROM provider")
    suspend fun count(): Int

    @Insert
    suspend fun insert(provider: ProviderEntity): Long

    @Update
    suspend fun update(provider: ProviderEntity)

    @Query("DELETE FROM provider WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ContentDao {
    @Query("SELECT * FROM category WHERE providerId = :providerId ORDER BY position")
    fun observeCategories(providerId: Long): Flow<List<CategoryEntity>>

    @Query(
        """
        SELECT c.*, (f.remoteId IS NOT NULL) AS isFav
        FROM channel c
        LEFT JOIN favourite f ON f.providerId = c.providerId AND f.remoteId = c.remoteId
        WHERE c.providerId = :providerId
          AND (:category IS NULL OR c.categoryRemoteId = :category)
          AND (:query = '' OR c.name LIKE '%' || :query || '%')
          AND (:favOnly = 0 OR f.remoteId IS NOT NULL)
        ORDER BY c.number, c.sortKey
        LIMIT 3000
        """
    )
    fun observeChannels(providerId: Long, category: String?, query: String, favOnly: Int): Flow<List<ChannelRow>>

    @Query("SELECT * FROM channel WHERE id = :id")
    suspend fun channel(id: Long): ChannelEntity?

    @Query(
        """
        SELECT * FROM channel
        WHERE providerId = :providerId AND (:category IS NULL OR categoryRemoteId = :category)
        ORDER BY number, sortKey
        LIMIT 5000
        """
    )
    suspend fun siblings(providerId: Long, category: String?): List<ChannelEntity>

    @Insert
    suspend fun insertCategories(items: List<CategoryEntity>)

    @Insert
    suspend fun insertChannels(items: List<ChannelEntity>)

    @Query("DELETE FROM category WHERE providerId = :providerId")
    suspend fun deleteCategories(providerId: Long)

    @Query("DELETE FROM channel WHERE providerId = :providerId")
    suspend fun deleteChannels(providerId: Long)

    @Query("DELETE FROM favourite WHERE providerId = :providerId")
    suspend fun deleteFavourites(providerId: Long)

    /** Swap a provider's live content in one go, so a failed sync never leaves an empty list. */
    @Transaction
    suspend fun replaceLive(providerId: Long, categories: List<CategoryEntity>, channels: List<ChannelEntity>) {
        deleteCategories(providerId)
        deleteChannels(providerId)
        categories.chunked(500).forEach { insertCategories(it) }
        channels.chunked(500).forEach { insertChannels(it) }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavourite(fav: FavouriteEntity)

    @Query("DELETE FROM favourite WHERE providerId = :providerId AND remoteId = :remoteId")
    suspend fun removeFavourite(providerId: Long, remoteId: String)
}
