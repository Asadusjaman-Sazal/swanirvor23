package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DateUtilsTest {

    @Test
    fun `parses dd-MM-yyyy into a positive timestamp`() {
        assertTrue(parseDateTextToMillis("15-08-2026") > 0L)
    }

    @Test
    fun `formats an ISO date back to dd-MM-yyyy`() {
        assertEquals("15-08-2026", formatToDdMmYyyy("2026-08-15"))
    }

    @Test
    fun `keeps an already dd-MM-yyyy date unchanged`() {
        assertEquals("15-08-2026", formatToDdMmYyyy("15-08-2026"))
    }

    @Test
    fun `current cycle range is a bounded Sunday to Saturday window`() {
        val (start, end) = getCurrentCycleRange()
        assertTrue(start <= end)
        // A week at most: 7 days in milliseconds
        assertTrue(end - start <= 7L * 24 * 60 * 60 * 1000)
    }

    @Test
    fun `previous cycle range is a bounded Sunday to Saturday window`() {
        val (start, end) = getPreviousCycleRange()
        assertTrue(start <= end)
        assertTrue(end - start <= 7L * 24 * 60 * 60 * 1000)
    }

    @Test
    fun `next Thursday is one to seven days away and labelled Thursday`() {
        val info = calculateNextThursday()
        assertTrue(info.daysRemaining in 1..7)
        assertEquals("Thursday", info.dayOfWeek)
        assertTrue(info.formattedDateText.startsWith("Thursday,"))
    }
}
