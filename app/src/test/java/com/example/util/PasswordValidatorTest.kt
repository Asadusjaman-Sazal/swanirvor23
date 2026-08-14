package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PasswordValidatorTest {

    @Test
    fun `accepts a strong password`() {
        assertNull(PasswordValidator.validate("Str0ngPass"))
    }

    @Test
    fun `rejects a password shorter than the minimum`() {
        assertEquals(
            "Password must be at least 8 characters long.",
            PasswordValidator.validate("Ab1")
        )
    }

    @Test
    fun `rejects a password without a number`() {
        assertEquals(
            "Password must contain at least one letter and one number.",
            PasswordValidator.validate("password")
        )
    }

    @Test
    fun `rejects a password without a letter`() {
        assertEquals(
            "Password must contain at least one letter and one number.",
            PasswordValidator.validate("12345678")
        )
    }
}
