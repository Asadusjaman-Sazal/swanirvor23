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

/**
 * Room Database holder for the Swanirvor-23 application.
 * Manages tables for members, individual savings contributions, and app settings.
 */
@Database(entities = [Member::class, Savings::class, AppSettings::class, ChangeRequest::class], version = 9, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun memberDao(): MemberDao
    abstract fun savingsDao(): SavingsDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun changeRequestDao(): ChangeRequestDao

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
                .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
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
    }
}
