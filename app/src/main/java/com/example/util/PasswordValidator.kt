package com.example.util

/**
 * Single source of truth for the password policy used at signup, password change,
 * and password recovery.
 */
object PasswordValidator {
    // Comment: Return a user-facing error message when the password fails the policy, or null when it is acceptable.
    fun validate(password: String): String? {
        if (password.length < Constants.MIN_PASSWORD_LENGTH) {
            return "Password must be at least ${Constants.MIN_PASSWORD_LENGTH} characters long."
        }
        if (!password.any { it.isDigit() } || !password.any { it.isLetter() }) {
            return "Password must contain at least one letter and one number."
        }
        return null
    }
}
