package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.model.AppSettings
import com.example.data.model.Member
import com.example.data.model.Savings
import com.example.data.model.ChangeRequest

/**
 * Room Database holder for the Swanirvor-23 application.
 * Manages tables for members, individual savings contributions, and app settings.
 */
@Database(entities = [Member::class, Savings::class, AppSettings::class, ChangeRequest::class], version = 6, exportSchema = false)
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
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
