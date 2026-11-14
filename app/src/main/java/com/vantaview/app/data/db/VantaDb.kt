// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProviderEntity::class, CategoryEntity::class, ChannelEntity::class, FavouriteEntity::class,
        AddonEntity::class, ProfileEntity::class, KidsCategoryEntity::class, ProgrammeEntity::class,
    ],
    // Version 3 (profiles) starts the test database afresh: playlists and add-ons are added again.
    version = 5,
    exportSchema = false,
)
abstract class VantaDb : RoomDatabase() {
    abstract fun providers(): ProviderDao
    abstract fun content(): ContentDao
    abstract fun addons(): AddonDao
    abstract fun profiles(): ProfileDao
    abstract fun guide(): GuideDao

    companion object {
        /** Version 5 adds the TV guide's programmes; everything else is kept. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `programme` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`providerId` INTEGER NOT NULL, `channelKey` TEXT NOT NULL, `start` INTEGER NOT NULL, " +
                        "`stop` INTEGER NOT NULL, `title` TEXT NOT NULL, `description` TEXT, `category` TEXT)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_programme_providerId_channelKey_start` " +
                        "ON `programme` (`providerId`, `channelKey`, `start`)"
                )
            }
        }

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
