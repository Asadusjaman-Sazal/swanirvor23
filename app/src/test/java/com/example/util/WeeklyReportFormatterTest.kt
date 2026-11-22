package com.example.util

import com.example.data.model.Member
import com.example.data.model.Savings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class WeeklyReportFormatterTest {

    private fun date(year: Int, month: Int, day: Int): Calendar =
        Calendar.getInstance(TimeZone.getDefault()).apply {
            set(year, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }

    private fun saving(id: Int, name: String, amount: Double, dateText: String) = Savings(
        id = id,
        memberId = id,
        memberName = name,
        amount = amount,
        dateText = dateText
    )

    private fun member(id: Int, name: String, total: Double) = Member(
        id = id,
        name = name,
        email = "${name.lowercase()}@example.com",
        totalSavings = total
    )

    @Test
    fun `builds the report with current week entries and alphabetized member totals`() {
        val report = WeeklyReportData(
            generatedAt = date(2026, Calendar.NOVEMBER, 22).time,
            weekStartMillis = date(2026, Calendar.NOVEMBER, 20).timeInMillis,
            weekEndMillis = date(2026, Calendar.NOVEMBER, 27).timeInMillis - 1,
            savings = listOf(
                saving(1, "Zara", 500.0, "20-11-2026"),
                saving(2, "Asadusjaman", 1_000.0, "20-11-2026"),
                saving(3, "Asadusjaman", 200.0, "22-11-2026"),
                saving(4, "Asadusjaman", 999.0, "01-11-2026")
            ),
            members = listOf(
                member(1, "Zara", 5_000.0),
                member(2, "Asadusjaman", 3_000.0)
            ),
            totalDeposited = 2_000.0,
            cashInHand = 6_000.0
        )

        val expected = """
            Weekly Report: 22/11/2026

            Savings made by members this week:
            -------------------------------------------
            20/11/2026
            Asadusjaman - 1,000৳
            Zara - 500৳

            22/11/2026
            Asadusjaman - 200৳
            -------------------------------------------------
            Total savings this week = 1,700৳

            Total Saving made by the members:
            -----------------------------------------
            * Asadusjaman - 3,000৳
            * Zara - 5,000৳
            ----------------------------------------
            Total savings = 8,000৳

            Amount deposited in bank = 2,000৳
            Awaiting bank deposit (Cash in hand) = 6,000৳
        """.trimIndent()

        assertEquals(expected, WeeklyReportFormatter.build(report).trimEnd())
    }
}
