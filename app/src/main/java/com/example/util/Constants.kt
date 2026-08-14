package com.example.util

/**
 * Shared constants used across the app so magic values live in a single place.
 */
object Constants {
    const val DEEP_LINK_SCHEME = "swanirvor23"
    const val MIN_PASSWORD_LENGTH = 8

    // Comment: Seed demo accounts are excluded from the real member/savings views.
    val SEED_EMAILS = setOf(
        "sarah.j@example.com",
        "m.reyes@example.com",
        "elena.r@example.com",
        "d.chen@example.com",
        "amanda@example.com",
        "jane.d@example.com",
        "r.smith@example.com",
        "ev.lin@example.com",
        "john.doe@example.com",
        "test@email.com"
    )
}
