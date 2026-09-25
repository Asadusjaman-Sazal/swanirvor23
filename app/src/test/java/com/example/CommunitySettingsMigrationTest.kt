package com.example

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies migration 14->15 against a real SQLite database: the Weekly Savings Goal rows gain the owning member's id
 * and name, backfilled from the shared members table, because an email alone does not identify the user.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CommunitySettingsMigrationTest {

    @Test
    fun `migration 14 to 15 adds and backfills the member identity`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "community_settings_migration_test.db"
        context.deleteDatabase(dbName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(14) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        val db = helper.writableDatabase

        // Comment: Reproduce the version 14 shape of both tables, with a goal whose email differs in case from the member
        db.execSQL(
            "CREATE TABLE `members` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `email` TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE `community_settings` (`memberEmail` TEXT NOT NULL, `weeklyGoal` REAL NOT NULL, `remoteVersion` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`memberEmail`))"
        )
        db.execSQL("INSERT INTO `members` (`id`, `name`, `email`) VALUES (7, 'Asad', 'Asad@Example.com')")
        db.execSQL("INSERT INTO `community_settings` (`memberEmail`, `weeklyGoal`) VALUES ('asad@example.com', 800.0)")

        AppDatabase.MIGRATION_14_15.migrate(db)

        db.query("SELECT `memberId`, `memberName`, `weeklyGoal` FROM `community_settings`").use { cursor ->
            assertTrue("the goal row should survive the migration", cursor.moveToFirst())
            // Comment: The owner is resolved case-insensitively from the members table, and the goal itself is untouched
            assertEquals(7, cursor.getInt(0))
            assertEquals("Asad", cursor.getString(1))
            assertEquals(800.0, cursor.getDouble(2), 0.0)
        }

        helper.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration 14 to 15 leaves an unresolvable owner blank`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "community_settings_migration_test_orphan.db"
        context.deleteDatabase(dbName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(14) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        val db = helper.writableDatabase

        db.execSQL(
            "CREATE TABLE `members` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `email` TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE `community_settings` (`memberEmail` TEXT NOT NULL, `weeklyGoal` REAL NOT NULL, `remoteVersion` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`memberEmail`))"
        )
        db.execSQL("INSERT INTO `community_settings` (`memberEmail`, `weeklyGoal`) VALUES ('gone@example.com', 500.0)")

        AppDatabase.MIGRATION_14_15.migrate(db)

        db.query("SELECT `memberId`, `memberName` FROM `community_settings`").use { cursor ->
            assertTrue("the goal row should survive the migration", cursor.moveToFirst())
            // Comment: 0 / blank means the owner was not resolved, so the next write fills the identity in from the member
            assertEquals(0, cursor.getInt(0))
            assertEquals("", cursor.getString(1))
        }

        helper.close()
        context.deleteDatabase(dbName)
    }
}
