package com.example.util

import com.example.data.model.BankDeposit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the Bank Deposits ledger arithmetic (totals, Cash in Hand, serials, and ordering).
 */
class BankLedgerTest {

    private fun deposit(
        id: Int,
        amount: Double,
        dateText: String,
        timestamp: Long = id.toLong()
    ) = BankDeposit(
        id = id,
        amount = amount,
        dateText = dateText,
        timestamp = timestamp,
        depositedById = 1,
        depositedByName = "Rafiq Hasan"
    )

    @Test
    fun totalDepositedSumsEveryRow() {
        val deposits = listOf(
            deposit(1, 20_000.0, "01-08-2026"),
            deposit(2, 25_000.0, "08-09-2026"),
            deposit(3, 5_500.0, "15-09-2026")
        )
        assertEquals(50_500.0, BankLedger.totalDeposited(deposits), 0.001)
    }

    @Test
    fun totalDepositedIsZeroForAnEmptyLedger() {
        assertEquals(0.0, BankLedger.totalDeposited(emptyList()), 0.001)
    }

    @Test
    fun cashInHandIsCollectedMinusBanked() {
        assertEquals(12_500.0, BankLedger.cashInHand(57_500.0, 45_000.0), 0.001)
    }

    @Test
    fun cashInHandIsZeroWhenEverythingIsBanked() {
        assertEquals(0.0, BankLedger.cashInHand(45_000.0, 45_000.0), 0.001)
    }

    // Comment: A negative till means more was banked than collected and must stay visible, not be clamped to zero
    @Test
    fun cashInHandGoesNegativeWhenMoreIsBankedThanCollected() {
        assertEquals(-5_000.0, BankLedger.cashInHand(40_000.0, 45_000.0), 0.001)
    }

    @Test
    fun formatSerialPadsToOneBasedThreeDigits() {
        assertEquals("#001", BankLedger.formatSerial(0))
        assertEquals("#009", BankLedger.formatSerial(8))
        assertEquals("#010", BankLedger.formatSerial(9))
        assertEquals("#100", BankLedger.formatSerial(99))
        assertEquals("#1000", BankLedger.formatSerial(999))
    }

    @Test
    fun orderedForLedgerSortsOldestFirstByDate() {
        val deposits = listOf(
            deposit(3, 5_500.0, "15-09-2026"),
            deposit(1, 20_000.0, "01-08-2026"),
            deposit(2, 25_000.0, "08-09-2026")
        )
        assertEquals(listOf(1, 2, 3), BankLedger.orderedForLedger(deposits).map { it.id })
    }

    @Test
    fun orderedForLedgerFallsBackToTimestampWhenDatesMatch() {
        val deposits = listOf(
            deposit(7, 1_000.0, "01-08-2026", timestamp = 200L),
            deposit(4, 1_000.0, "01-08-2026", timestamp = 100L)
        )
        assertEquals(listOf(4, 7), BankLedger.orderedForLedger(deposits).map { it.id })
    }
}
