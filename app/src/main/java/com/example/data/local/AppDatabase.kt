package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.AppSettings
import com.example.data.model.Member
import com.example.data.model.Savings
import com.example.data.model.ChangeRequest
import com.example.data.model.BankDeposit
import com.example.data.model.CommunitySettings

/**
 * Room Database holder for the Swanirvor-23 application.
 * Manages tables for members, individual savings contributions, and app settings.
 */
@Database(entities = [Member::class, Savings::class, AppSettings::class, ChangeRequest::class, BankDeposit::class, CommunitySettings::class], version = 15, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun memberDao(): MemberDao
    abstract fun savingsDao(): SavingsDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun changeRequestDao(): ChangeRequestDao
    abstract fun bankDepositDao(): BankDepositDao
    abstract fun communitySettingsDao(): CommunitySettingsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Gets the singleton instance of the database.
         */
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lexsave_database"
                )
                .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
                .build()
                INSTANCE = instance
                instance
            }
        }

        // Comment: Migrate Room database from version 6 to 7 by recreating the members table without the plaintext password column.
        // The table is recreated (copy data, drop old, rename) because SQLite's DROP COLUMN is unsupported on older devices (SQLite < 3.35, API < 31).
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `members_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `email` TEXT NOT NULL, `avatarUrl` TEXT, `totalSavings` REAL NOT NULL, `role` TEXT NOT NULL, `status` TEXT NOT NULL, `receivedAdminNotification` INTEGER NOT NULL, `membershipNo` TEXT NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `members_new` (`id`, `name`, `email`, `avatarUrl`, `totalSavings`, `role`, `status`, `receivedAdminNotification`, `membershipNo`) SELECT `id`, `name`, `email`, `avatarUrl`, `totalSavings`, `role`, `status`, `receivedAdminNotification`, `membershipNo` FROM `members`"
                )
                db.execSQL("DROP TABLE `members`")
                db.execSQL("ALTER TABLE `members_new` RENAME TO `members`")
            }
        }

        // Comment: Migrate Room database from version 7 to 8 by adding the stable sync key column to savings.
        // Existing rows start with an empty key and are assigned one by the one-time backfill during the next sync.
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `savings` ADD COLUMN `syncKey` TEXT NOT NULL DEFAULT ''")
            }
        }

        // Comment: Migrate Room database from version 8 to 9 by adding the mobile number column to app_settings
        // for the new Settings > Profile Information > Mobile No. field. Existing rows start empty.
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `app_settings` ADD COLUMN `mobileNo` TEXT NOT NULL DEFAULT ''")
            }
        }

        // Comment: Migrate Room database from version 9 to 10 by adding the mobile number column to members
        // so the Admin Panel can call/message members using their saved mobile number. Existing rows start empty.
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `members` ADD COLUMN `mobileNo` TEXT NOT NULL DEFAULT ''")
            }
        }

        // Comment: Migrate Room database from version 10 to 11 by adding the bank deposit ledger table.
        // This ledger is separate from savings so "Cash in Hand" (collected minus banked) can be derived.
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bank_deposits` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `amount` REAL NOT NULL, `dateText` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `depositedById` INTEGER NOT NULL, `depositedByName` TEXT NOT NULL, `syncKey` TEXT NOT NULL)"
                )
            }
        }

        // Comment: Migrate Room database from version 11 to 12 by adding the shared community_settings table.
        // It holds the single central Weekly Savings Goal row, seeded at 500৳ so a new admin can edit a value
        // instead of a missing row. The goal moved off the per-user app_settings table, which each user owned.
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `community_settings` (`id` INTEGER NOT NULL, `weeklyGoal` REAL NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("INSERT OR IGNORE INTO `community_settings` (`id`, `weeklyGoal`) VALUES (1, 500.0)")
            }
        }

        // Comment: Migrate Room database from version 12 to 13 by adding the remote version stamp that guards the
        // central Weekly Savings Goal. It records the version of the shared row this device last read (0 = never
        // confirmed), so an admin device holding an older goal cannot overwrite a newer one.
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `community_settings` ADD COLUMN `remoteVersion` INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Comment: Migrate Room database from version 13 to 14 by turning the single society-wide Weekly Savings Goal
        // into one goal per member, because members save different amounts. The table is recreated (copy, drop, rename)
        // since its primary key changes from the singleton id to the member's lowercased email, which SQLite cannot
        // alter in place. Each existing member is seeded with the old shared goal (or the 500৳ default) so no one
        // loses continuity when the society-wide value had been customized; duplicate emails collapse to one row.
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `community_settings_new` (`memberEmail` TEXT NOT NULL, `weeklyGoal` REAL NOT NULL, `remoteVersion` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`memberEmail`))"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `community_settings_new` (`memberEmail`, `weeklyGoal`, `remoteVersion`) " +
                        "SELECT lower(trim(`email`)), COALESCE((SELECT `weeklyGoal` FROM `community_settings` WHERE `id` = 1), 500.0), 0 " +
                        "FROM `members` GROUP BY lower(trim(`email`))"
                )
                db.execSQL("DROP TABLE `community_settings`")
                db.execSQL("ALTER TABLE `community_settings_new` RENAME TO `community_settings`")
            }
        }

        // Comment: Migrate Room database from version 14 to 15 by storing the owning member's id and display name on
        // every Weekly Savings Goal row, because the email alone does not identify the member well enough. Both columns
        // are additive with defaults, so existing rows survive; they are then backfilled from the shared members table
        // (case-insensitive on the email) so rows written before this column existed carry the same identity as new ones.
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `community_settings` ADD COLUMN `memberId` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `community_settings` ADD COLUMN `memberName` TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "UPDATE `community_settings` SET " +
                        "`memberId` = COALESCE((SELECT `id` FROM `members` WHERE lower(trim(`email`)) = `community_settings`.`memberEmail` LIMIT 1), 0), " +
                        "`memberName` = COALESCE((SELECT `name` FROM `members` WHERE lower(trim(`email`)) = `community_settings`.`memberEmail` LIMIT 1), '')"
                )
            }
        }
    }
}
