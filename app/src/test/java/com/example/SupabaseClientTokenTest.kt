package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.SupabaseClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SupabaseClientTokenTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // Comment: Init the encrypted prefs store; in the Robolectric sandbox encryption setup
        // may fail, in which case SupabaseClient falls back to plain prefs, which is fine for these tests.
        SupabaseClient.init(context)
    }

    private fun saveFixtureSession(expiresAt: Long? = null, refreshToken: String? = "test-refresh-token") {
        val json = StringBuilder("{")
        json.append("\"access_token\":\"test-access-token\"")
        if (expiresAt != null) json.append(",\"expires_at\":$expiresAt")
        if (refreshToken != null) json.append(",\"refresh_token\":\"$refreshToken\"")
        json.append("}")
        SupabaseClient.saveSession(json.toString())
    }

    @Test
    fun `expiring soon when less than five minutes remain`() {
        saveFixtureSession(expiresAt = System.currentTimeMillis() + 2 * 60 * 1000)
        assertTrue(SupabaseClient.isSessionExpiringSoon())
    }

    @Test
    fun `not expiring soon when more than five minutes remain`() {
        saveFixtureSession(expiresAt = System.currentTimeMillis() + 30 * 60 * 1000)
        assertFalse(SupabaseClient.isSessionExpiringSoon())
    }

    @Test
    fun `not expiring soon when expires_at is missing`() {
        saveFixtureSession(expiresAt = null)
        assertFalse(SupabaseClient.isSessionExpiringSoon())
    }

    @Test
    fun `expiring soon when there is no session at all`() {
        SupabaseClient.clearSession()
        assertTrue(SupabaseClient.isSessionExpiringSoon())
    }

    @Test
    fun `getRefreshToken returns the stored refresh token`() {
        saveFixtureSession(expiresAt = System.currentTimeMillis() + 30 * 60 * 1000, refreshToken = "rt-123")
        assertEquals("rt-123", SupabaseClient.getRefreshToken())
    }

    @Test
    fun `getRefreshToken returns null when there is no session`() {
        SupabaseClient.clearSession()
        assertNull(SupabaseClient.getRefreshToken())
    }
}
