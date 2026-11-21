package com.example.util

import android.app.Activity
import android.util.Base64
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Result of a successful native Google credential request.
 * The raw nonce is the value Supabase must verify; only its SHA-256 hash ever reaches Google.
 */
data class GoogleIdTokenResult(
    val idToken: String,
    val rawNonce: String
)

/**
 * Explicit outcomes so the login screen can tell a user dismissal apart from a broken
 * Google/SHA/package/client-ID setup. Android reports the latter as a credential failure,
 * which must be visible rather than silently treated as a cancellation.
 */
sealed class GoogleSignInOutcome {
    data class Success(val result: GoogleIdTokenResult) : GoogleSignInOutcome()
    object Cancelled : GoogleSignInOutcome()
    object ConfigurationError : GoogleSignInOutcome()
}

/**
 * Native Google sign-in through Credential Manager. No browser, no redirect URL, no deep link.
 */
object GoogleSignIn {

    /**
     * Opens the phone's Google account sheet over the current activity and returns the ID token.
     * Must be called from a coroutine; Credential Manager owns the sheet's lifetime.
     */
    suspend fun requestIdToken(activity: Activity, serverClientId: String): GoogleSignInOutcome {
        val rawNonce = generateNonce()
        val hashedNonce = sha256Hex(rawNonce)

        return try {
            // Comment: Google receives only the hash; Supabase receives the raw value and hashes it
            // again for comparison. Passing the raw nonce to both sides breaks the check silently.
            val option = GetSignInWithGoogleOption.Builder(serverClientId = serverClientId)
                .setNonce(hashedNonce)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()

            val response = CredentialManager.create(activity).getCredential(activity, request)
            // Comment: GetSignInWithGoogleOption returns the SIWG credential type; older Google
            // credential responses use the standard type. Both carry a parseable Google ID token.
            val credentialType = response.credential.type
            if (credentialType != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL &&
                credentialType != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL
            ) {
                return GoogleSignInOutcome.ConfigurationError
            }

            val credential = GoogleIdTokenCredential.createFrom(response.credential.data)
            GoogleSignInOutcome.Success(GoogleIdTokenResult(credential.idToken, rawNonce))
        } catch (e: GetCredentialCancellationException) {
            // Comment: The user closed the account sheet. This is a normal dismissal, not an error.
            GoogleSignInOutcome.Cancelled
        } catch (e: GetCredentialInterruptedException) {
            // Comment: A newer request interrupted the sheet; treat it as a dismissal, not a setup failure.
            GoogleSignInOutcome.Cancelled
        } catch (e: NoCredentialException) {
            // Comment: No Google account is available on this phone/profile, so this is a visible
            // setup failure rather than a silent dismissal.
            GoogleSignInOutcome.ConfigurationError
        } catch (e: GoogleIdTokenParsingException) {
            // Comment: The returned credential was not a Google ID token, so the Google setup is wrong.
            GoogleSignInOutcome.ConfigurationError
        } catch (e: GetCredentialException) {
            // Comment: Covers provider-configuration and no-credential failures (wrong SHA-1,
            // package name, or Web client ID), which Android otherwise reports like a dismissal.
            GoogleSignInOutcome.ConfigurationError
        } catch (e: Exception) {
            GoogleSignInOutcome.ConfigurationError
        }
    }

    /** Random 256-bit nonce, URL-safe so it survives the Google token round trip unchanged. */
    fun generateNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    /** Lowercase SHA-256 hex digest, the only form Google is allowed to see. */
    fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
