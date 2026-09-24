package com.example.util

import com.example.data.model.Savings

/**
 * Pure helpers for ordering savings contributions for display.
 * Kept free of Android/Room types so the ordering can be unit-tested without a device.
 */
object SavingsOrdering {

    /**
     * Newest contribution first, ordered by the date the member actually entered rather than by
     * insertion time, so a back-dated or freshly synced row never shows up out of chronological
     * order. Ties fall back to the recorded timestamp and then the row id, keeping the order
     * deterministic when two deposits share a date.
     */
    fun newestFirst(savings: List<Savings>): List<Savings> = savings.sortedWith(
        compareByDescending<Savings> { parseDateTextToMillis(it.dateText) }
            .thenByDescending { it.timestamp }
            .thenByDescending { it.id }
    )
}
