package com.example.data.model

/**
 * Outcome of an admin's edit to the central Weekly Savings Goal, so the Admin Panel can tell a member-facing
 * save apart from an offline save and from a save that lost to a newer goal set on another admin's device.
 */
enum class WeeklyGoalUpdateResult {
    // Comment: The server confirmed the new goal, so every member will read it on their next sync
    SYNCED,
    // Comment: Saved on this device only (offline, no session, or the shared table is not reachable yet) — a later
    // sync retries the push, and it is reported so the admin does not believe the change already spread
    PENDING,
    // Comment: Another admin changed the goal after this device last synced, so the newer central value won and was
    // adopted locally instead of being reverted
    CONFLICT
}
