// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A playlist the user added. kind is "M3U" or "XTREAM". */
@Entity(tableName = "provider")
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    val baseUrl: String,
    val username: String = "",
    val passwordEnc: String = "",
    val epgUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastSyncAt: Long = 0,
    val channelCount: Int = 0,
    val lastError: String? = null,
)

@Entity(tableName = "category", indices = [Index("providerId")])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val remoteId: String,
    val name: String,
    val position: Int,
)

/**
 * A live channel. remoteId is the provider's own id (text), so favourites survive a re-sync.
 * For Xtream, streamUrl is "xtream:<stream_id>" and is turned into a real address just before playback,
 * so the login is never stored inside channel rows.
 */
@Entity(
    tableName = "channel",
    indices = [Index("providerId"), Index("providerId", "categoryRemoteId"), Index("providerId", "remoteId")],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val remoteId: String,
    val name: String,
    val sortKey: String,
    val number: Int,
    val logo: String? = null,
    val categoryRemoteId: String? = null,
    val streamUrl: String,
    val epgChannelId: String? = null,
    val headersJson: String? = null,
)

@Entity(tableName = "favourite", primaryKeys = ["providerId", "remoteId"])
data class FavouriteEntity(
    val providerId: Long,
    val remoteId: String,
    val addedAt: Long = System.currentTimeMillis(),
)

data class ChannelRow(
    @Embedded val channel: ChannelEntity,
    val isFav: Boolean,
)
