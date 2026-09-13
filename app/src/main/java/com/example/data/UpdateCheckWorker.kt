package com.example.data

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.BuildConfig
import com.example.ui.notification.UpdateNotificationHelper
import com.example.util.VersionComparator

/**
 * Checks the published GitHub release once per day in the background and posts a notification when a
 * newer version exists. WorkManager owns the cadence, so the app never polls: the check costs one
 * request per day and only runs while a network is available.
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Comment: The notification is this worker's only output, so stop before any network work when
        // the user has notifications turned off for the app.
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            return Result.success()
        }

        // Comment: A failed fetch (offline, GitHub hiccup) is not worth retrying sooner — the next
        // daily run and the manual "Check for Update" button both cover it, and backing off keeps the
        // radio idle instead of burning battery on repeated attempts.
        val release = UpdateChecker.fetchLatestRelease().getOrNull() ?: return Result.success()

        // Comment: Only announce a strictly newer release, and only once per version, so a user who
        // delays the update is not notified every single day for the same release.
        if (!VersionComparator.isNewer(release.version, BuildConfig.VERSION_NAME)) return Result.success()
        if (lastNotifiedVersion() == release.version) return Result.success()

        UpdateNotificationHelper.showUpdateAvailableNotification(applicationContext, release.version)
        markNotified(release.version)
        return Result.success()
    }

    // Comment: Read the version already announced from this worker's own SharedPreferences file, which
    // is separate from the Room-backed user settings.
    private fun lastNotifiedVersion(): String? =
        notificationPrefs().getString(KEY_LAST_NOTIFIED_VERSION, null)

    private fun markNotified(version: String) {
        notificationPrefs().edit().putString(KEY_LAST_NOTIFIED_VERSION, version).apply()
    }

    private fun notificationPrefs() =
        applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "app_update_check"
        private const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"
    }
}
