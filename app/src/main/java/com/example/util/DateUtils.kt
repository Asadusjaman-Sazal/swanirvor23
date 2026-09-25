package com.example.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Helper model to carry calculated Next Thursday info
data class NextThursdayInfo(
    val daysRemaining: Int,
    val dayOfWeek: String,
    val monthText: String,
    val dayOfMonth: Int,
    val year: Int,
    val formattedDateText: String
)

// Parse date text (from input fields/seed data) to milliseconds
fun parseDateTextToMillis(dateText: String): Long {
    val formats = listOf(
        SimpleDateFormat("dd-MM-yyyy", Locale.US),
        SimpleDateFormat("yyyy-MM-dd", Locale.US),
        SimpleDateFormat("MMM dd, yyyy", Locale.US),
        SimpleDateFormat("MMMM dd, yyyy", Locale.US)
    )
    for (f in formats) {
        // Comment: Parse strictly so an ISO "yyyy-MM-dd" value is not misread by the "dd-MM-yyyy" format (lenient mode would roll the fields over instead of failing).
        f.isLenient = false
        try {
            val date = f.parse(dateText)
            if (date != null) return date.time
        } catch (e: Exception) {
            // Ignored, try next format
        }
    }
    return System.currentTimeMillis()
}

// Convert any date text to DD-MM-YYYY format safely
fun formatToDdMmYyyy(dateText: String): String {
    if (dateText.matches(Regex("\\d{2}-\\d{2}-\\d{4}"))) {
        return dateText
    }
    val millis = parseDateTextToMillis(dateText)
    val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.US)
    return sdf.format(Date(millis))
}

// Check if a contribution date falls in the current calendar month
fun isCurrentMonth(dateText: String): Boolean {
    try {
        val millis = parseDateTextToMillis(dateText)
        val cal = Calendar.getInstance()
        val currentYear = cal.get(Calendar.YEAR)
        val currentMonth = cal.get(Calendar.MONTH)

        val dateCal = Calendar.getInstance().apply { timeInMillis = millis }
        return dateCal.get(Calendar.YEAR) == currentYear && dateCal.get(Calendar.MONTH) == currentMonth
    } catch (e: Exception) {
        return false
    }
}

// Get Friday (00:00) to Thursday (23:59) range of the previous cycle
fun getPreviousCycleRange(): Pair<Long, Long> {
    val cal = Calendar.getInstance()
    // Comment: Step back exactly one week (7 days) from today, then snap to that cycle's Friday via the shared helper.
    cal.add(Calendar.DAY_OF_YEAR, -7)
    return fridayToThursdayRangeFor(cal)
}

// Get Friday (00:00) to Thursday (23:59) range of the current cycle
fun getCurrentCycleRange(): Pair<Long, Long> = fridayToThursdayRangeFor(Calendar.getInstance())

// Comment: Every savings cycle now runs Friday → Thursday. Calculate the deterministic Friday start and Thursday end by using explicit day offsets to avoid locale-specific Calendar bugs (devices whose first day of week is Monday or Saturday).
private fun fridayToThursdayRangeFor(cal: Calendar): Pair<Long, Long> {
    val currentDayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
    // Comment: Number of days to step back to reach this cycle's Friday, which opens the cycle at 00:00.
    val daysSinceFriday = (currentDayOfWeek - Calendar.FRIDAY + 7) % 7

    val fridayCal = cal.clone() as Calendar
    fridayCal.add(Calendar.DAY_OF_YEAR, -daysSinceFriday)
    fridayCal.set(Calendar.HOUR_OF_DAY, 0)
    fridayCal.set(Calendar.MINUTE, 0)
    fridayCal.set(Calendar.SECOND, 0)
    fridayCal.set(Calendar.MILLISECOND, 0)

    // Comment: The cycle closes on the following Thursday (Friday + 6 days) at 23:59:59.999.
    val thursdayCal = fridayCal.clone() as Calendar
    thursdayCal.add(Calendar.DAY_OF_YEAR, 6)
    thursdayCal.set(Calendar.HOUR_OF_DAY, 23)
    thursdayCal.set(Calendar.MINUTE, 59)
    thursdayCal.set(Calendar.SECOND, 59)
    thursdayCal.set(Calendar.MILLISECOND, 999)

    return Pair(fridayCal.timeInMillis, thursdayCal.timeInMillis)
}

// The savings community began on Sunday, 29 March 2026; under the Friday→Thursday cycle that date belongs to the first cycle, which opens on Friday, 27 March 2026.
private val COMMUNITY_START_MILLIS: Long = Calendar.getInstance().apply {
    set(2026, Calendar.MARCH, 27, 0, 0, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

// Comment: Count how many savings cycles (Fri→Thu weeks) have elapsed from the community start up to and including the current active cycle. Used for "Total Due" and "Total Projected Savings" on the Personal Dashboard.
fun getElapsedCycleCount(): Int {
    val (currentCycleStart, _) = getCurrentCycleRange()
    // Comment: Round to the nearest week so a daylight-saving hour shift never truncates the count down by one cycle.
    val weeksSinceStart = Math.round((currentCycleStart - COMMUNITY_START_MILLIS).toDouble() / (7L * 24 * 60 * 60 * 1000)).toInt()
    // Comment: Add 1 so the starting cycle itself is counted (week 0 since start === cycle 1), and never return less than 1.
    return maxOf(1, weeksSinceStart + 1)
}

// Calculate days remaining and formatted info for the upcoming Thursday
fun calculateNextThursday(): NextThursdayInfo {
    val cal = Calendar.getInstance()
    val currentDayOfWeek = cal.get(Calendar.DAY_OF_WEEK)

    var daysToThursday = (Calendar.THURSDAY - currentDayOfWeek + 7) % 7
    if (daysToThursday == 0) {
        daysToThursday = 7
    }

    val nextThursdayCal = cal.clone() as Calendar
    nextThursdayCal.add(Calendar.DAY_OF_YEAR, daysToThursday)

    val monthFormat = SimpleDateFormat("MMM", Locale.US)
    val monthText = monthFormat.format(nextThursdayCal.time)
    val dayOfMonth = nextThursdayCal.get(Calendar.DAY_OF_MONTH)
    val year = nextThursdayCal.get(Calendar.YEAR)

    return NextThursdayInfo(
        daysRemaining = daysToThursday,
        dayOfWeek = "Thursday",
        monthText = monthText,
        dayOfMonth = dayOfMonth,
        year = year,
        formattedDateText = "Thursday, $monthText $dayOfMonth, $year"
    )
}
