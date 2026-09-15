package com.example.util

import com.example.data.model.BankDeposit

/**
 * Pure helpers for the Bank Deposits ledger arithmetic.
 * Kept free of Android/Room types so the money math can be unit-tested without a device.
 */
object BankLedger {

    // Sum of every recorded bank deposit.
    fun totalDeposited(deposits: List<BankDeposit>): Double =
        deposits.sumOf { it.amount }

    /**
     * Cash still in hand: everything collected from members minus everything already banked.
     * Deliberately not clamped at zero — a negative result means more was banked than collected
     * and must stay visible instead of silently reading as an empty till.
     */
    fun cashInHand(totalCollected: Double, totalDeposited: Double): Double =
        totalCollected - totalDeposited

    // Zero-padded ledger serial for a 0-based index, e.g. index 0 -> "#001".
    fun formatSerial(index: Int): String =
        "#" + (index + 1).toString().padStart(3, '0')

    /**
     * Ledger rows oldest-first so serials stay stable as new deposits are appended at the end.
     * Undated rows fall back to their timestamp, then their id, so the order is always deterministic.
     */
    fun orderedForLedger(deposits: List<BankDeposit>): List<BankDeposit> =
        deposits.sortedWith(
            compareBy({ parseDateTextToMillis(it.dateText) }, { it.timestamp }, { it.id })
        )
}
