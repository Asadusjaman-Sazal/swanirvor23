package com.example.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Keeps the daily background app-update check scheduled. The work is periodic, unique and constrained
 * to an available network, so the OS batches it into one small run per day instead of a polling loop.
 */
object UpdateCheckScheduler {

    private const val UPDATE_CHECK_WORK_NAME = "app_update_check_daily"
    private const val CHECK_INTERVAL_HOURS = 24L

    // Comment: KEEP leaves an already-scheduled request untouched, so opening the app repeatedly can
    // never keep resetting the 24-hour window and starve the check.
    fun scheduleDailyCheck(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            CHECK_INTERVAL_HOURS,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                UPDATE_CHECK_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }
}
