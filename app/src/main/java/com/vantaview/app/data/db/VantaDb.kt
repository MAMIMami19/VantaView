// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ProviderEntity::class, CategoryEntity::class, ChannelEntity::class, FavouriteEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class VantaDb : RoomDatabase() {
    abstract fun providers(): ProviderDao
    abstract fun content(): ContentDao
}
