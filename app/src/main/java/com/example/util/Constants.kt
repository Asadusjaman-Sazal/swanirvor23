package com.example.util

/**
 * Shared constants used across the app so magic values live in a single place.
 */
object Constants {
    const val DEEP_LINK_SCHEME = "swanirvor23"
    const val MIN_PASSWORD_LENGTH = 8

    // Comment: Text size is deliberately app-owned: the OS font scale is ignored and the user
    // moves every style by one additive step from Settings > View Settings.
    const val FONT_SIZE_STEP_MIN = -2
    const val FONT_SIZE_STEP_MAX = 6
    const val FONT_SIZE_STEP_DEFAULT = 1

    // Comment: Clamp on read and write so older builds, restored backups and hand-edited values
    // can never push a label past the range the layouts were measured for.
    fun clampFontSizeStep(step: Int): Int = step.coerceIn(FONT_SIZE_STEP_MIN, FONT_SIZE_STEP_MAX)

    // Comment: Members marked with this status are soft-deleted — kept in the database/tables but excluded from all UI and syncs
    const val REMOVED_STATUS = "Removed"

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
