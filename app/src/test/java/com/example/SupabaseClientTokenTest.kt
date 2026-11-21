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
        // Comment: Robolectric has no Android Keystore, so the encrypted store cannot be created here.
        // Inject a throwaway in-memory store to exercise the token logic; production still refuses to
        // save anything when secure storage is unavailable (asserted below).
        SupabaseClient.init(context)
        context.getSharedPreferences("token-test", Context.MODE_PRIVATE).edit().clear().commit()
        SupabaseClient.useSessionStoreForTest(
            context.getSharedPreferences("token-test", Context.MODE_PRIVATE)
        )
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

    @Test
    fun `saveSession refuses when secure storage is unavailable`() {
        SupabaseClient.useSessionStoreForTest(null)
        assertFalse(SupabaseClient.saveSession("{\"access_token\":\"never-written\"}"))
        assertNull(SupabaseClient.getSession())
        assertFalse(SupabaseClient.isSecureStorageAvailable())
    }
}
