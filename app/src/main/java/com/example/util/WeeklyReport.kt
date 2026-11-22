package com.example.util

import com.example.data.model.Member
import com.example.data.model.Savings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Snapshot of everything a Weekly Report needs, captured when the admin taps the button.
 * Kept free of Android types so the report text can be unit-tested without a device.
 */
data class WeeklyReportData(
    val generatedAt: Date,
    val weekStartMillis: Long,
    val weekEndMillis: Long,
    val savings: List<Savings>,
    val members: List<Member>,
    val totalDeposited: Double,
    val cashInHand: Double
)

/**
 * Builds the exact weekly report text shared from the Admin Panel.
 * The week follows the app's Friday -> Thursday savings cycle; all-time member totals are
 * sorted alphabetically and match the members shown on the Members Dashboard.
 */
object WeeklyReportFormatter {

    fun build(data: WeeklyReportData): String {
        val reportDate = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(data.generatedAt)

        // Comment: Include an entry by its contribution date, not its insertion timestamp, so a
        // back-dated entry appears in the week it belongs to.
        val weekSavings = data.savings
            .filter { parseDateTextToMillis(it.dateText) in data.weekStartMillis..data.weekEndMillis }
            .sortedWith(
                compareBy(
                    { parseDateTextToMillis(it.dateText) },
                    { it.memberName.lowercase(Locale.ROOT) },
                    { it.id }
                )
            )

        val memberNamesById = data.members.associateBy { it.id }
        val sortedMembers = data.members
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

        val totalThisWeek = weekSavings.sumOf { it.amount }
        val totalSavings = sortedMembers.sumOf { it.totalSavings }

        return buildString {
            appendLine("Weekly Report: $reportDate")
            appendLine()
            appendLine("Savings made by members this week:")
            appendLine("-------------------------------------------")

            if (weekSavings.isEmpty()) {
                appendLine("No savings recorded this week.")
            } else {
                // Comment: Grouping keeps one savings date heading above every member entry made on that date.
                var currentDate = ""
                weekSavings.forEach { saving ->
                    val savingDate = SimpleDateFormat("dd/MM/yyyy", Locale.US)
                        .format(Date(parseDateTextToMillis(saving.dateText)))
                    if (savingDate != currentDate) {
                        if (currentDate.isNotEmpty()) appendLine()
                        appendLine(savingDate)
                        currentDate = savingDate
                    }
                    val memberName = saving.memberName.ifBlank {
                        memberNamesById[saving.memberId]?.name ?: "Unknown member"
                    }
                    appendLine("$memberName - ${formatAmount(saving.amount)}")
                }
            }

            appendLine("-------------------------------------------------")
            appendLine("Total savings this week = ${formatAmount(totalThisWeek)}")
            appendLine()
            appendLine("Total Saving made by the members:")
            appendLine("-----------------------------------------")
            sortedMembers.forEach { member ->
                appendLine("* ${member.name} - ${formatAmount(member.totalSavings)}")
            }
            appendLine("----------------------------------------")
            appendLine("Total savings = ${formatAmount(totalSavings)}")
            appendLine()
            appendLine("Amount deposited in bank = ${formatAmount(data.totalDeposited)}")
            appendLine("Awaiting bank deposit (Cash in hand) = ${formatAmount(data.cashInHand)}")
        }
    }

    // Comment: Whole-taka formatting matches the rest of the app and the requested 1,000৳ example.
    private fun formatAmount(amount: Double): String =
        String.format(Locale.US, "%,.0f৳", amount)
}
