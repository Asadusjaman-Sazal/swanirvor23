package com.example.util

import com.example.data.model.Savings
import org.junit.Assert.assertEquals
import org.junit.Test

class SavingsOrderingTest {

    private fun saving(id: Int, dateText: String, timestamp: Long = 0L) = Savings(
        id = id,
        memberId = 1,
        memberName = "Test Member",
        amount = 100.0,
        dateText = dateText,
        timestamp = timestamp
    )

    @Test
    fun `orders by contribution date newest first regardless of insertion order`() {
        val ordered = SavingsOrdering.newestFirst(
            listOf(
                saving(1, "01-08-2026"),
                saving(2, "22-09-2026"),
                saving(3, "15-08-2026")
            )
        )
        assertEquals(listOf("22-09-2026", "15-08-2026", "01-08-2026"), ordered.map { it.dateText })
    }

    @Test
    fun `orders mixed date formats chronologically`() {
        val ordered = SavingsOrdering.newestFirst(
            listOf(
                saving(1, "2026-08-01"),    // ISO
                saving(2, "Sep 22, 2026"),  // MMM dd, yyyy
                saving(3, "15-08-2026")     // dd-MM-yyyy
            )
        )
        assertEquals(listOf(2, 3, 1), ordered.map { it.id })
    }

    @Test
    fun `breaks same-date ties by newest timestamp then id`() {
        val ordered = SavingsOrdering.newestFirst(
            listOf(
                saving(1, "15-08-2026", timestamp = 100L),
                saving(2, "15-08-2026", timestamp = 200L)
            )
        )
        assertEquals(listOf(2, 1), ordered.map { it.id })
    }
}
