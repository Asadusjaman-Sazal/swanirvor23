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

// Get Sunday (00:00) to Saturday (23:59) range of the previous week
fun getPreviousCycleRange(): Pair<Long, Long> {
    val cal = Calendar.getInstance()
    cal.add(Calendar.WEEK_OF_YEAR, -1)

    // Comment: Calculate deterministic Sunday and Saturday by using explicit day offsets to avoid locale-specific Calendar bugs
    val currentDayOfWeek = cal.get(Calendar.DAY_OF_WEEK)

    val sundayCal = cal.clone() as Calendar
    sundayCal.add(Calendar.DAY_OF_YEAR, -(currentDayOfWeek - Calendar.SUNDAY))
    sundayCal.set(Calendar.HOUR_OF_DAY, 0)
    sundayCal.set(Calendar.MINUTE, 0)
    sundayCal.set(Calendar.SECOND, 0)
    sundayCal.set(Calendar.MILLISECOND, 0)

    val saturdayCal = cal.clone() as Calendar
    saturdayCal.add(Calendar.DAY_OF_YEAR, Calendar.SATURDAY - currentDayOfWeek)
    saturdayCal.set(Calendar.HOUR_OF_DAY, 23)
    saturdayCal.set(Calendar.MINUTE, 59)
    saturdayCal.set(Calendar.SECOND, 59)
    saturdayCal.set(Calendar.MILLISECOND, 999)

    return Pair(sundayCal.timeInMillis, saturdayCal.timeInMillis)
}

// Get Sunday (00:00) to Saturday (23:59) range of the current week
fun getCurrentCycleRange(): Pair<Long, Long> {
    val cal = Calendar.getInstance()

    // Comment: Calculate deterministic Sunday and Saturday by using explicit day offsets to avoid locale-specific Calendar bugs
    val currentDayOfWeek = cal.get(Calendar.DAY_OF_WEEK)

    val sundayCal = cal.clone() as Calendar
    sundayCal.add(Calendar.DAY_OF_YEAR, -(currentDayOfWeek - Calendar.SUNDAY))
    sundayCal.set(Calendar.HOUR_OF_DAY, 0)
    sundayCal.set(Calendar.MINUTE, 0)
    sundayCal.set(Calendar.SECOND, 0)
    sundayCal.set(Calendar.MILLISECOND, 0)

    val saturdayCal = cal.clone() as Calendar
    saturdayCal.add(Calendar.DAY_OF_YEAR, Calendar.SATURDAY - currentDayOfWeek)
    saturdayCal.set(Calendar.HOUR_OF_DAY, 23)
    saturdayCal.set(Calendar.MINUTE, 59)
    saturdayCal.set(Calendar.SECOND, 59)
    saturdayCal.set(Calendar.MILLISECOND, 999)

    return Pair(sundayCal.timeInMillis, saturdayCal.timeInMillis)
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
