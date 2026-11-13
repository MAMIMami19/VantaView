// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProviderEntity::class, CategoryEntity::class, ChannelEntity::class, FavouriteEntity::class,
        AddonEntity::class, ProfileEntity::class, KidsCategoryEntity::class,
    ],
    // Version 3 (profiles) starts the test database afresh: playlists and add-ons are added again.
    version = 4,
    exportSchema = false,
)
abstract class VantaDb : RoomDatabase() {
    abstract fun providers(): ProviderDao
    abstract fun content(): ContentDao
    abstract fun addons(): AddonDao
    abstract fun profiles(): ProfileDao

    companion object {
        /** Version 4 adds each channel's country; existing playlists are kept and sorted on next open. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `channel` ADD COLUMN `country` TEXT")
            }
        }

        /** Version 2 adds installed add-ons; playlists and favourites are kept. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `addon` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`manifestUrl` TEXT NOT NULL, `name` TEXT NOT NULL, `manifestJson` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, `position` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_addon_manifestUrl` ON `addon` (`manifestUrl`)")
            }
        }
    }
}
