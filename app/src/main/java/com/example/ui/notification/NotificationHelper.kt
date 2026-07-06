package com.example.ui.notification

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.AppSettings
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * NotificationReceiver is triggered by AlarmManager when the weekly contribution reminder time arrives,
 * or by the system on device reboot.
 * It builds and posts a system status bar notification with the configured text.
 */
class NotificationReceiver : BroadcastReceiver() {
    private val TAG = "NotificationReceiver"

    // BroadcastReceiver onReceive callback executed when alarm triggers or boot completes
    override fun onReceive(context: Context, intent: Intent) {
        // Comment: Handle device boot completed action to reschedule saved alarms
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device booted! Rescheduling weekly contribution reminder notifications...")
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = com.example.data.local.AppDatabase.getDatabase(context)
                    val settings = db.appSettingsDao().getSettingsDirect()
                    if (settings != null) {
                        NotificationScheduler.scheduleNotification(context, settings)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error rescheduling on boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }

        val text = intent.getStringExtra("notification_text") ?: "Reminder: Your weekly contribution is due."
        val title = "Weekly Contribution Reminder"
        val channelId = "weekly_contribution_channel"

        Log.d(TAG, "Alarm triggered! Showing notification to user: $text")

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Setup the notification channel required for Android O (API 26) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Weekly Contribution",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Weekly contribution automated notifications"
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Define an intent to open MainActivity when the notification is clicked
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Load large icon bitmap (colored logo) for status bar/drawer notifications
        val largeIcon = getBitmapFromDrawable(context, R.drawable.ic_favicon_placeholder)

        // Build the system status bar notification with proper monochrome small icon and colored large icon
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_small) // Monochrome transparent silhouette
            .apply {
                if (largeIcon != null) {
                    setLargeIcon(largeIcon)
                }
            }
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        // Post the notification using system notification manager
        notificationManager.notify(1002, notification)
    }
}

/**
 * NotificationScheduler manages the scheduling of the weekly contribution alarms.
 * It uses Android AlarmManager to schedule precise recurring-like weekly events based on AppSettings.
 */
object NotificationScheduler {
    private const val TAG = "NotificationScheduler"

    // Schedule the alarm based on provided AppSettings configurations
    fun scheduleNotification(context: Context, settings: AppSettings) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NotificationReceiver::class.java).apply {
            putExtra("notification_text", settings.notificationText)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // If notification is disabled, cancel any existing alarm
        if (!settings.enableNotifications) {
            alarmManager.cancel(pendingIntent)
            Log.d(TAG, "Notification disabled, cancelled active alarms.")
            return
        }

        // Map settings day to Calendar day value
        val dayOfWeek = mapDayOfWeek(settings.notificationDay)

        // Parse the formatted setting time string to integer hour and minute
        val (hour, minute) = parseTime(settings.notificationTime)

        // Calculate epoch timestamp for the next occurrence of specified day/time
        val triggerTime = calculateNextTriggerTime(dayOfWeek, hour, minute)
        Log.d(TAG, "Scheduling alarm for: ${SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.US).format(Date(triggerTime))}")

        try {
            // Schedule the alarm; setAndAllowWhileIdle executes alarm even if the system is in Doze power saving mode
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed scheduling with setAndAllowWhileIdle", e)
            try {
                // Fallback to standard set in case of any platform permission issues with exact alarms
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } catch (ex: Exception) {
                Log.e(TAG, "Failed fallback scheduling method", ex)
            }
        }
    }

    // Map text weekday name to Calendar integer constants
    private fun mapDayOfWeek(day: String): Int {
        return when (day.trim().lowercase(Locale.US)) {
            "sunday" -> Calendar.SUNDAY
            "monday" -> Calendar.MONDAY
            "tuesday" -> Calendar.TUESDAY
            "wednesday" -> Calendar.WEDNESDAY
            "thursday" -> Calendar.THURSDAY
            "friday" -> Calendar.FRIDAY
            "saturday" -> Calendar.SATURDAY
            else -> Calendar.THURSDAY // Default fallback to Thursday
        }
    }

    // Parse setting string of format "HH:mm" into components
    private fun parseTime(time: String): Pair<Int, Int> {
        return try {
            val parts = time.split(":")
            val hour = parts[0].toInt()
            val minute = parts[1].toInt()
            Pair(hour, minute)
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing time string: $time, defaulting to 09:00", e)
            Pair(9, 0)
        }
    }

    // Calculate the next Calendar epoch milliseconds for scheduled alarm
    private fun calculateNextTriggerTime(dayOfWeek: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance()
        val currentDayOfWeek = cal.get(Calendar.DAY_OF_WEEK)

        // Target calendar setup for today at specified hour/minute
        val targetCal = cal.clone() as Calendar
        targetCal.set(Calendar.HOUR_OF_DAY, hour)
        targetCal.set(Calendar.MINUTE, minute)
        targetCal.set(Calendar.SECOND, 0)
        targetCal.set(Calendar.MILLISECOND, 0)

        var daysDiff = dayOfWeek - currentDayOfWeek
        if (daysDiff < 0) {
            // The scheduled day of week has already passed in the current week, schedule for next week
            daysDiff += 7
        } else if (daysDiff == 0) {
            // The scheduled day is today. Check if the specified hour/minute has already elapsed
            if (cal.after(targetCal)) {
                daysDiff = 7
            }
        }
        targetCal.add(Calendar.DAY_OF_YEAR, daysDiff)
        return targetCal.timeInMillis
    }
}

/**
 * AdminNotificationHelper is responsible for triggering system-level notifications
 * when users submit change requests, notifying administrators.
 */
object AdminNotificationHelper {
    private const val TAG = "AdminNotification"
    private const val CHANNEL_ID = "admin_change_requests_channel"
    private const val NOTIFICATION_ID = 1003

    // Comment: Post a status bar notification when a new pending change request is created, notifying administrators.
    // Clicking on this notification launches the app and navigates directly to Admin Panel > Change Requests section.
    fun showAdminChangeRequestNotification(context: Context, requesterName: String, requestType: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Set up a high importance channel for admin alerts on API 26+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Admin Change Requests",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "System notifications for pending member change requests"
                }
                notificationManager.createNotificationChannel(channel)
            }

            // Comment: Define an intent to open the app on click and pass a deep navigation extra to trigger the correct page routing
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("navigate_to", "admin_change_requests")
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Comment: Format the title and description exactly as requested by the user
            val text = "'$requesterName' has submitted a new '$requestType' request."
            val title = "New Change Request"

            // Load large icon bitmap (colored logo) for status bar/drawer notifications
            val largeIcon = getBitmapFromDrawable(context, R.drawable.ic_favicon_placeholder)

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_small) // Monochrome transparent silhouette
                .apply {
                    if (largeIcon != null) {
                        setLargeIcon(largeIcon)
                    }
                }
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "Successfully posted change request notification for $requesterName ($requestType)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post change request notification", e)
        }
    }

    // Comment: Post a status bar notification for the user who made the request, confirming that it has been submitted to admins.
    fun showUserChangeRequestSubmittedNotification(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val userChannelId = "user_change_requests_channel"
            val userNotificationId = 1004

            // Set up a standard importance channel for user alerts on API 26+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    userChannelId,
                    "Change Request Confirmation",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "System notifications confirming a submitted change request"
                }
                notificationManager.createNotificationChannel(channel)
            }

            // Define intent to open the app on click
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Comment: Format the title and description exactly as requested by the user
            val title = "Change Request Made"
            val text = "Your change request is submitted to the admins for approval."

            val largeIcon = getBitmapFromDrawable(context, R.drawable.ic_favicon_placeholder)

            val notification = NotificationCompat.Builder(context, userChannelId)
                .setSmallIcon(R.drawable.ic_notification_small)
                .apply {
                    if (largeIcon != null) {
                        setLargeIcon(largeIcon)
                    }
                }
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(userNotificationId, notification)
            Log.d(TAG, "Successfully posted user submitted change request notification")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post user submitted change request notification", e)
        }
    }
}

/**
 * Safely converts any vector or raster drawable resource into a Bitmap for use as a notification large icon.
 */
private fun getBitmapFromDrawable(context: Context, drawableResId: Int): Bitmap? {
    return try {
        val drawable = ContextCompat.getDrawable(context, drawableResId) ?: return null
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        bitmap
    } catch (e: Exception) {
        Log.e("NotificationHelper", "Failed to convert drawable to bitmap", e)
        null
    }
}

