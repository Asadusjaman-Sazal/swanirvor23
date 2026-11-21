package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GoogleSignInTest {

    @Test
    fun sha256MatchesTheKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            GoogleSignIn.sha256Hex("abc")
        )
    }

    @Test
    fun sha256IsDeterministic() {
        assertEquals(GoogleSignIn.sha256Hex("raw-nonce"), GoogleSignIn.sha256Hex("raw-nonce"))
    }

    @Test
    fun nonceIsUrlSafeAndUnique() {
        val first = GoogleSignIn.generateNonce()
        val second = GoogleSignIn.generateNonce()
        assertNotEquals(first, second)
        assertFalse(first.contains("="))
        assertFalse(first.contains("+"))
        assertFalse(first.contains("/"))
        assertTrue(first.length >= 40)
    }
}
