package com.example.data

import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import com.example.data.model.Member
import com.example.data.model.Savings
import com.example.data.model.AppSettings
import com.example.data.model.ChangeRequest

/**
 * Utility client to connect Swanirvor-23 to Supabase Auth REST API.
 * Credentials are injected at build time from the local .env file via the Secrets Gradle Plugin (see BuildConfig).
 */
object SupabaseClient {
    // Comment: Read the Supabase project URL and publishable/anon key from BuildConfig (populated from .env by the Secrets Gradle Plugin)
    private val SUPABASE_URL = com.example.BuildConfig.SUPABASE_URL
    private val SUPABASE_PUBLIC_KEY = com.example.BuildConfig.SUPABASE_ANON_KEY

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private var sharedPreferences: android.content.SharedPreferences? = null

    // Comment: Initialize an encrypted shared preferences session store so access/refresh tokens are protected at rest.
    // The encrypted store uses a new file name and the legacy plaintext prefs file is deleted so old tokens do not linger on disk.
    fun init(context: android.content.Context) {
        val appContext = context.applicationContext
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            sharedPreferences = EncryptedSharedPreferences.create(
                appContext,
                "SupabaseAuthEncryptedPrefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            // Comment: Remove the legacy plaintext prefs file (if present) so previously stored plaintext tokens are not left on disk
            appContext.deleteSharedPreferences("SupabaseAuthPrefs")
        } catch (e: Exception) {
            // Comment: Fall back to plain preferences only if keystore/encryption setup fails, so the app can still start
            Log.e("SupabaseAuth", "Failed to initialize encrypted preferences, falling back to plain preferences", e)
            sharedPreferences = appContext.getSharedPreferences("SupabaseAuthPrefs", android.content.Context.MODE_PRIVATE)
        }
    }

    // Comment: Save the active Supabase session JSON string
    fun saveSession(sessionJson: String) {
        sharedPreferences?.edit()?.putString("session_json", sessionJson)?.apply()
    }

    // Comment: Clear the stored session JSON string on logout
    fun clearSession() {
        sharedPreferences?.edit()?.remove("session_json")?.apply()
    }

    // Comment: Get the current session JSON string if it contains a valid access token
    fun getSession(): String? {
        val sessionStr = sharedPreferences?.getString("session_json", null)
        if (sessionStr.isNullOrBlank()) return null
        return try {
            val json = JSONObject(sessionStr)
            if (json.has("access_token") && !json.isNull("access_token")) {
                sessionStr
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    // Comment: Report whether the active session's access token expires within the next 5 minutes,
    // so callers can refresh it before making authenticated requests.
    fun isSessionExpiringSoon(): Boolean {
        val sessionStr = getSession() ?: return true
        return try {
            val json = JSONObject(sessionStr)
            val expiresAt = json.optLong("expires_at", 0L)
            if (expiresAt > 0L) {
                val timeUntilExpiry = expiresAt - System.currentTimeMillis()
                timeUntilExpiry < 5 * 60 * 1000 // Less than 5 minutes
            } else {
                false
            }
        } catch (e: Exception) {
            true
        }
    }

    // Comment: Extract the refresh token from the active Supabase session
    fun getRefreshToken(): String? {
        val sessionStr = getSession() ?: return null
        return try {
            val json = JSONObject(sessionStr)
            json.optString("refresh_token")
        } catch (e: Exception) {
            null
        }
    }

    // Comment: Exchange the stored refresh token for a fresh session via POST /auth/v1/token,
    // saving the new session JSON on success or clearing the session when the refresh is rejected.
    suspend fun refreshAccessToken(): SupabaseAuthResult = withContext(Dispatchers.IO) {
        val refreshToken = getRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            return@withContext SupabaseAuthResult(false, "No refresh token available. Please log in again.")
        }

        val url = "$SUPABASE_URL/auth/v1/token?grant_type=refresh_token"
        val jsonBody = JSONObject().apply {
            put("refresh_token", refreshToken)
        }.toString()

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(jsonMediaType))
            .addHeader("apikey", SUPABASE_PUBLIC_KEY)
            .addHeader("Content-Type", "application/json")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                Log.d("SupabaseAuth", "Token Refresh Response Code: ${response.code}")
                if (response.isSuccessful) {
                    val jsonResponse = JSONObject(bodyStr)
                    // Comment: Ensure expires_at is present so future expiry checks work even if the API omitted it
                    if (!jsonResponse.has("expires_at")) {
                        val expiresIn = jsonResponse.optLong("expires_in", 3600L)
                        jsonResponse.put("expires_at", System.currentTimeMillis() + expiresIn * 1000)
                    }
                    saveSession(jsonResponse.toString())
                    SupabaseAuthResult(true, "Token refreshed successfully", sessionExists = true)
                } else {
                    clearSession()
                    SupabaseAuthResult(false, "Failed to refresh token. Please log in again.", sessionExists = false)
                }
            }
        } catch (e: Exception) {
            SupabaseAuthResult(false, "Network error: ${e.localizedMessage}", sessionExists = false)
        }
    }

    // Comment: Extract the user email from the active Supabase session
    fun getSessionEmail(): String? {
        val sessionStr = getSession() ?: return null
        return try {
            val json = JSONObject(sessionStr)
            val userObj = json.optJSONObject("user")
            userObj?.optString("email")
        } catch (e: Exception) {
            null
        }
    }

    // Comment: Store the last registered/logged-in email address in local SharedPreferences
    fun saveRegisteredEmail(email: String) {
        sharedPreferences?.edit()?.putString("registered_email", email.trim())?.apply()
    }

    // Comment: Retrieve the last registered/logged-in email address from SharedPreferences
    fun getRegisteredEmail(): String {
        return sharedPreferences?.getString("registered_email", "") ?: ""
    }

    class SupabaseAuthResult(
        val success: Boolean,
        val message: String,
        val userEmail: String? = null,
        val sessionExists: Boolean = false,
        val sessionJson: String? = null
    )

    /**
     * Comment: Performs supabase.auth.signUp({ email, password }) via the REST API.
     */
    suspend fun signUp(email: String, password: String): SupabaseAuthResult = withContext(Dispatchers.IO) {
        val url = "$SUPABASE_URL/auth/v1/signup"
        val jsonBody = JSONObject().apply {
            put("email", email)
            put("password", password)
        }.toString()

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(jsonMediaType))
            .addHeader("apikey", SUPABASE_PUBLIC_KEY)
            .addHeader("Content-Type", "application/json")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                Log.d("SupabaseAuth", "SignUp Response Code: ${response.code}")
                if (response.isSuccessful) {
                    val jsonResponse = JSONObject(bodyStr)
                    val userObj = jsonResponse.optJSONObject("user")
                    val userEmail = userObj?.optString("email") ?: email

                    val sessionObj = jsonResponse.optJSONObject("session")
                    val hasSession = sessionObj != null && !jsonResponse.isNull("session")

                    if (hasSession && sessionObj != null) {
                        val sessionJsonStr = sessionObj.toString()
                        saveSession(sessionJsonStr)
                        SupabaseAuthResult(true, "Sign up successful!", userEmail, sessionExists = true, sessionJson = sessionJsonStr)
                    } else {
                        // signUp result is success but no session yet (email confirmation needed)
                        SupabaseAuthResult(true, "Check your email and confirm your account before logging in.", userEmail, sessionExists = false, sessionJson = null)
                    }
                } else {
                    val errorMsg = parseErrorMessage(bodyStr, "Sign up failed. Please check your inputs.")
                    SupabaseAuthResult(false, errorMsg, sessionExists = false)
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseAuth", "SignUp Error", e)
            SupabaseAuthResult(false, "Network error: ${e.localizedMessage ?: "Unknown error"}", sessionExists = false)
        }
    }

    /**
     * Comment: Performs supabase.auth.signInWithPassword({ email, password }) via the REST API.
     */
    suspend fun signInWithPassword(email: String, password: String): SupabaseAuthResult = withContext(Dispatchers.IO) {
        val url = "$SUPABASE_URL/auth/v1/token?grant_type=password"
        val jsonBody = JSONObject().apply {
            put("email", email)
            put("password", password)
        }.toString()

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(jsonMediaType))
            .addHeader("apikey", SUPABASE_PUBLIC_KEY)
            .addHeader("Content-Type", "application/json")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                Log.d("SupabaseAuth", "SignIn Response Code: ${response.code}")
                if (response.isSuccessful) {
                    val jsonResponse = JSONObject(bodyStr)
                    val userObj = jsonResponse.optJSONObject("user")
                    val userEmail = userObj?.optString("email") ?: email
                    val hasAccessToken = jsonResponse.has("access_token") && !jsonResponse.isNull("access_token")

                    if (hasAccessToken) {
                        saveSession(jsonResponse.toString())
                        SupabaseAuthResult(true, "Sign in successful!", userEmail, sessionExists = true, sessionJson = jsonResponse.toString())
                    } else {
                        SupabaseAuthResult(false, "Authentication succeeded but no active session token was returned.", userEmail, sessionExists = false)
                    }
                } else {
                    val errorMsg = parseErrorMessage(bodyStr, "Sign in failed. Incorrect email or password.")
                    SupabaseAuthResult(false, errorMsg, sessionExists = false)
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseAuth", "SignIn Error", e)
            SupabaseAuthResult(false, "Network error: ${e.localizedMessage ?: "Unknown error"}", sessionExists = false)
        }
    }

    /**
     * Comment: Fetch user details via the token and save the full session JSON
     */
    suspend fun fetchUserAndSaveSession(
        accessToken: String,
        refreshToken: String,
        expiresIn: String,
        tokenType: String
    ): SupabaseAuthResult = withContext(Dispatchers.IO) {
        val url = "$SUPABASE_URL/auth/v1/user"
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("apikey", SUPABASE_PUBLIC_KEY)
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                Log.d("SupabaseAuth", "FetchUser Response Code: ${response.code}")
                if (response.isSuccessful) {
                    val userObj = JSONObject(bodyStr)
                    val userEmail = userObj.optString("email") ?: ""

                    // Construct standard session JSON object
                    val sessionJson = JSONObject().apply {
                        put("access_token", accessToken)
                        put("refresh_token", refreshToken)
                        put("expires_in", expiresIn.toIntOrNull() ?: 3600)
                        // Comment: Store the absolute expiry time so token-refresh checks work for Google OAuth sessions too
                        put("expires_at", System.currentTimeMillis() + (expiresIn.toIntOrNull() ?: 3600) * 1000L)
                        put("token_type", tokenType)
                        put("user", userObj)
                    }
                    val sessionJsonStr = sessionJson.toString()
                    saveSession(sessionJsonStr)
                    SupabaseAuthResult(true, "Sign in successful!", userEmail, sessionExists = true, sessionJson = sessionJsonStr)
                } else {
                    SupabaseAuthResult(false, "Failed to retrieve Google user profile.", sessionExists = false)
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseAuth", "FetchUser Error", e)
            SupabaseAuthResult(false, "Network error: ${e.localizedMessage ?: "Unknown error"}", sessionExists = false)
        }
    }

    /**
     * Comment: Sends a password recovery email via the Supabase Auth API with a custom redirect URL.
     */
    suspend fun recoverPassword(email: String, redirectUrl: String = "swanirvor23://login-callback"): SupabaseAuthResult = withContext(Dispatchers.IO) {
        val encodedRedirect = java.net.URLEncoder.encode(redirectUrl, "UTF-8")
        val url = "$SUPABASE_URL/auth/v1/recover?redirectTo=$encodedRedirect&redirect_to=$encodedRedirect"
        val jsonBody = JSONObject().apply {
            put("email", email.trim())
        }.toString()

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(jsonMediaType))
            .addHeader("apikey", SUPABASE_PUBLIC_KEY)
            .addHeader("Content-Type", "application/json")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                Log.d("SupabaseAuth", "Recover Response Code: ${response.code}")
                if (response.isSuccessful) {
                    SupabaseAuthResult(true, "Password reset email sent successfully! Please check your inbox.")
                } else {
                    val errorMsg = parseErrorMessage(bodyStr, "Failed to send password reset email. Please try again.")
                    SupabaseAuthResult(false, errorMsg)
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseAuth", "Recover Error", e)
            SupabaseAuthResult(false, "Network error: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    /**
     * Comment: Updates the authenticated user's password in Supabase via PUT /auth/v1/user.
     */
    suspend fun updatePassword(newPassword: String): SupabaseAuthResult = withContext(Dispatchers.IO) {
        val token = getAccessToken()
        if (token.isNullOrBlank()) {
            return@withContext SupabaseAuthResult(false, "No active session found to update password.")
        }

        val url = "$SUPABASE_URL/auth/v1/user"
        val jsonBody = JSONObject().apply {
            put("password", newPassword)
        }.toString()

        val request = Request.Builder()
            .url(url)
            .put(jsonBody.toRequestBody(jsonMediaType))
            .addHeader("apikey", SUPABASE_PUBLIC_KEY)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                Log.d("SupabaseAuth", "Update Password Response Code: ${response.code}")
                if (response.isSuccessful) {
                    SupabaseAuthResult(true, "Password updated successfully!")
                } else {
                    val errorMsg = parseErrorMessage(bodyStr, "Failed to update password.")
                    SupabaseAuthResult(false, errorMsg)
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseAuth", "Update Password Error", e)
            SupabaseAuthResult(false, "Network error: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    private fun parseErrorMessage(body: String, default: String): String {
        return try {
            val json = JSONObject(body)
            json.optString("error_description").takeIf { it.isNotEmpty() }
                ?: json.optString("message").takeIf { it.isNotEmpty() }
                ?: json.optString("msg").takeIf { it.isNotEmpty() }
                ?: default
        } catch (e: Exception) {
            default
        }
    }

    // Comment: Get the currently active Supabase access token for authenticated API requests
    fun getAccessToken(): String? {
        val sessionStr = getSession() ?: return null
        return try {
            val json = JSONObject(sessionStr)
            json.optString("access_token")
        } catch (e: Exception) {
            null
        }
    }

    // Comment: Get the current user UUID from active session object
    fun getCurrentUserUuid(): String? {
        val sessionStr = getSession() ?: return null
        return try {
            val json = JSONObject(sessionStr)
            val userObj = json.optJSONObject("user")
            userObj?.optString("id")
        } catch (e: Exception) {
            null
        }
    }

    // Comment: Perform authenticated or anonymous HTTP request to Supabase PostgREST tables.
    // Falls back to using the SUPABASE_PUBLIC_KEY as the Bearer token when getAccessToken() is null to support anonymous queries (e.g. checking member count before signup).
    private suspend fun performRequest(
        method: String,
        table: String,
        query: String = "",
        bodyJson: String? = null,
        prefer: String = "return=representation"
    ): String? = withContext(Dispatchers.IO) {
        val token = getAccessToken() ?: SUPABASE_PUBLIC_KEY
        val url = if (query.isNotEmpty()) "$SUPABASE_URL/rest/v1/$table?$query" else "$SUPABASE_URL/rest/v1/$table"

        val builder = Request.Builder()
            .url(url)
            .addHeader("apikey", SUPABASE_PUBLIC_KEY)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Prefer", prefer)

        when (method.uppercase()) {
            "GET" -> builder.get()
            "POST" -> bodyJson?.let { builder.post(it.toRequestBody(jsonMediaType)) }
            "PATCH" -> bodyJson?.let { builder.patch(it.toRequestBody(jsonMediaType)) }
            "DELETE" -> builder.delete()
        }

        try {
            client.newCall(builder.build()).execute().use { response ->
                val responseStr = response.body?.string()
                Log.d("SupabaseDb", "Request $method $table $query Response Code: ${response.code}")
                if (response.isSuccessful) {
                    responseStr
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseDb", "Error during REST request $method on $table", e)
            null
        }
    }

    // Comment: Serialization helper for Member objects to JSONObject
    fun memberToJson(member: Member, includeId: Boolean = true): JSONObject {
        return JSONObject().apply {
            if (includeId && member.id > 0) put("id", member.id)
            put("name", member.name)
            put("email", member.email)
            put("avatar_url", member.avatarUrl ?: JSONObject.NULL)
            put("total_savings", member.totalSavings)
            put("role", member.role)
            put("status", member.status)
            put("received_admin_notification", member.receivedAdminNotification)
            put("membership_no", member.membershipNo)
        }
    }

    // Comment: Parsing helper to map JSONObject to Member
    fun jsonToMember(json: JSONObject): Member {
        return Member(
            id = json.optInt("id", 0),
            name = json.optString("name", ""),
            email = json.optString("email", ""),
            avatarUrl = json.optString("avatar_url", "").takeIf { it.isNotEmpty() && it != "null" },
            totalSavings = json.optDouble("total_savings", 0.0),
            role = json.optString("role", "Member"),
            status = json.optString("status", "Active"),
            receivedAdminNotification = json.optBoolean("received_admin_notification", false),
            membershipNo = json.optString("membership_no", "")
        )
    }

    // Comment: Serialization helper for Savings objects to JSONObject
    fun savingsToJson(savings: Savings, includeId: Boolean = true): JSONObject {
        return JSONObject().apply {
            if (includeId && savings.id > 0) put("id", savings.id)
            put("member_id", savings.memberId)
            put("member_name", savings.memberName)
            put("amount", savings.amount)
            put("date_text", savings.dateText)
            put("timestamp", savings.timestamp)
            // Comment: Only send the match key when one is set so legacy partial updates never blank an existing remote key
            if (savings.syncKey.isNotBlank()) put("sync_key", savings.syncKey)
        }
    }

    // Comment: Parsing helper to map JSONObject to Savings
    fun jsonToSavings(json: JSONObject): Savings {
        return Savings(
            id = json.optInt("id", 0),
            memberId = json.optInt("member_id", 0),
            memberName = json.optString("member_name", ""),
            amount = json.optDouble("amount", 0.0),
            dateText = json.optString("date_text", ""),
            timestamp = json.optLong("timestamp", 0L),
            syncKey = json.optString("sync_key", "")
        )
    }

    // Comment: Serialization helper for AppSettings objects to JSONObject
    fun settingsToJson(settings: AppSettings, includeId: Boolean = true): JSONObject {
        return JSONObject().apply {
            if (includeId && settings.id > 0) put("id", settings.id)
            put("personal_goal", settings.personalGoal)
            put("profile_name", settings.profileName)
            put("membership_no", settings.membershipNo)
            put("is_dark_mode", settings.isDarkMode)
            put("notification_day", settings.notificationDay)
            put("notification_time", settings.notificationTime)
            put("notification_text", settings.notificationText)
            put("enable_notifications", settings.enableNotifications)
            put("profile_image_url", settings.profileImageUrl ?: JSONObject.NULL)
        }
    }

    // Comment: Parsing helper to map JSONObject to AppSettings
    fun jsonToSettings(json: JSONObject): AppSettings {
        return AppSettings(
            id = json.optInt("id", 1),
            personalGoal = json.optDouble("personal_goal", 500.0),
            profileName = json.optString("profile_name", "John Doe"),
            membershipNo = json.optString("membership_no", ""),
            isDarkMode = json.optBoolean("is_dark_mode", false),
            notificationDay = json.optString("notification_day", "Thursday"),
            notificationTime = json.optString("notification_time", "09:00"),
            notificationText = json.optString("notification_text", ""),
            enableNotifications = json.optBoolean("enable_notifications", true),
            profileImageUrl = json.optString("profile_image_url", "").takeIf { it.isNotEmpty() && it != "null" }
        )
    }

    // Comment: Serialization helper for ChangeRequest objects to JSONObject
    fun changeRequestToJson(request: ChangeRequest, includeId: Boolean = true): JSONObject {
        return JSONObject().apply {
            if (includeId && request.id > 0) put("id", request.id)
            put("savings_id", request.savingsId)
            put("member_id", request.memberId)
            put("member_name", request.memberName)
            put("request_type", request.requestType)
            put("original_amount", request.originalAmount)
            put("new_amount", request.newAmount ?: JSONObject.NULL)
            put("original_date_text", request.originalDateText)
            put("new_date_text", request.newDateText ?: JSONObject.NULL)
            put("status", request.status)
            put("timestamp", request.timestamp)
        }
    }

    // Comment: Parsing helper to map JSONObject to ChangeRequest
    fun jsonToChangeRequest(json: JSONObject): ChangeRequest {
        return ChangeRequest(
            id = json.optInt("id", 0),
            savingsId = json.optInt("savings_id", 0),
            memberId = json.optInt("member_id", 0),
            memberName = json.optString("member_name", ""),
            requestType = json.optString("request_type", ""),
            originalAmount = json.optDouble("original_amount", 0.0),
            newAmount = json.optDouble("new_amount", 0.0).takeIf { !json.isNull("new_amount") },
            originalDateText = json.optString("original_date_text", ""),
            newDateText = json.optString("new_date_text", "").takeIf { !json.isNull("new_date_text") },
            status = json.optString("status", "Pending"),
            timestamp = json.optLong("timestamp", 0L)
        )
    }

    // Comment: Fetch all Members from remote database table
    suspend fun dbFetchMembers(): List<Member> {
        val jsonStr = performRequest("GET", "members", "select=*") ?: return emptyList()
        val list = mutableListOf<Member>()
        try {
            val arr = org.json.JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                list.add(jsonToMember(arr.getJSONObject(i)))
            }
        } catch (e: Exception) {
            Log.e("SupabaseDb", "Error parsing members response", e)
        }
        return list
    }

    // Comment: Create a new Member row inside remote database table
    suspend fun dbInsertMember(member: Member): Member? {
        val body = memberToJson(member, includeId = false).toString()
        val jsonStr = performRequest("POST", "members", "", body) ?: return null
        return try {
            val arr = org.json.JSONArray(jsonStr)
            if (arr.length() > 0) jsonToMember(arr.getJSONObject(0)) else null
        } catch (e: Exception) {
            null
        }
    }

    // Comment: Upload avatar image bytes to Supabase Storage bucket and return the public URL if successful
    suspend fun uploadAvatar(fileName: String, fileBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val token = getAccessToken() ?: SUPABASE_PUBLIC_KEY
        val url = "$SUPABASE_URL/storage/v1/object/avatars/$fileName"

        val mediaType = "image/png".toMediaType()
        val requestBody = fileBytes.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("apikey", SUPABASE_PUBLIC_KEY)
            .addHeader("Authorization", "Bearer $token")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val responseStr = response.body?.string()
                Log.d("SupabaseStorage", "Upload avatar response code: ${response.code}")
                if (response.isSuccessful) {
                    "$SUPABASE_URL/storage/v1/object/public/avatars/$fileName"
                } else {
                    Log.e("SupabaseStorage", "Upload failed with response code ${response.code}: $responseStr")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseStorage", "Error uploading avatar to Supabase", e)
            null
        }
    }

    // Comment: Update an existing Member row in remote database table
    suspend fun dbUpdateMember(member: Member): Boolean {
        // Comment: Only update safe writable columns to prevent RLS security policy or constraint rejections (avoid sending password, email, role, or status)
        val bodyObj = JSONObject().apply {
            put("name", member.name)
            put("avatar_url", member.avatarUrl ?: JSONObject.NULL)
            put("total_savings", member.totalSavings)
            put("membership_no", member.membershipNo)
            put("received_admin_notification", member.receivedAdminNotification)
        }
        // Comment: URL-encode the email so values with reserved characters (+ , space) do not break the filter
        val encodedEmail = java.net.URLEncoder.encode(member.email, "UTF-8")
        val jsonStr = performRequest("PATCH", "members", "email=eq.$encodedEmail", bodyObj.toString())
        return jsonStr != null
    }

    // Comment: Delete an existing Member row from remote database table
    suspend fun dbDeleteMember(member: Member): Boolean {
        // Comment: URL-encode the email so values with reserved characters (+ , space) do not break the filter
        val encodedEmail = java.net.URLEncoder.encode(member.email, "UTF-8")
        val jsonStr = performRequest("DELETE", "members", "email=eq.$encodedEmail")
        return jsonStr != null
    }

    // Comment: Fetch all active (non-soft-deleted) Savings contributions from remote database table
    suspend fun dbFetchSavings(): List<Savings> {
        val jsonStr = performRequest("GET", "savings", "select=*&deleted=eq.false") ?: return emptyList()
        val list = mutableListOf<Savings>()
        try {
            val arr = org.json.JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                list.add(jsonToSavings(arr.getJSONObject(i)))
            }
        } catch (e: Exception) {
            Log.e("SupabaseDb", "Error parsing savings response", e)
        }
        return list
    }

    // Comment: Fetch the IDs of remotely soft-deleted savings so local copies of those rows can be removed instead of re-pushed
    suspend fun dbFetchDeletedSavingsIds(): Set<Int> {
        val jsonStr = performRequest("GET", "savings", "select=id&deleted=eq.true") ?: return emptySet()
        val ids = mutableSetOf<Int>()
        try {
            val arr = org.json.JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val id = arr.getJSONObject(i).optInt("id", 0)
                if (id > 0) ids.add(id)
            }
        } catch (e: Exception) {
            Log.e("SupabaseDb", "Error parsing deleted savings ids", e)
        }
        return ids
    }

    // Comment: Fetch the sync keys of remotely soft-deleted savings so local rows can be matched by their stable key
    suspend fun dbFetchDeletedSavingsKeys(): Set<String> {
        val jsonStr = performRequest("GET", "savings", "select=sync_key&deleted=eq.true") ?: return emptySet()
        val keys = mutableSetOf<String>()
        try {
            val arr = org.json.JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val key = arr.getJSONObject(i).optString("sync_key", "")
                if (key.isNotBlank()) keys.add(key)
            }
        } catch (e: Exception) {
            Log.e("SupabaseDb", "Error parsing deleted savings sync keys", e)
        }
        return keys
    }

    // Comment: Create a new Savings contribution row inside remote database table
    suspend fun dbInsertSavings(savings: Savings): Savings? {
        val body = savingsToJson(savings, includeId = false).toString()
        val jsonStr = performRequest("POST", "savings", "", body) ?: return null
        return try {
            val arr = org.json.JSONArray(jsonStr)
            if (arr.length() > 0) jsonToSavings(arr.getJSONObject(0)) else null
        } catch (e: Exception) {
            null
        }
    }

    // Comment: Upsert a Savings row keyed by its stable sync_key so the same logical entry
    // is never duplicated across two independent numeric ID sequences
    suspend fun dbUpsertSavings(savings: Savings): Savings? {
        val body = savingsToJson(savings, includeId = false).toString()
        val jsonStr = performRequest(
            "POST",
            "savings",
            "on_conflict=sync_key",
            body,
            "resolution=merge-duplicates,return=representation"
        ) ?: return null
        return try {
            val arr = org.json.JSONArray(jsonStr)
            if (arr.length() > 0) jsonToSavings(arr.getJSONObject(0)) else null
        } catch (e: Exception) {
            null
        }
    }

    // Comment: Assign a sync key to a legacy remote savings row that predates the sync_key column
    suspend fun dbPatchSavingsSyncKey(id: Int, syncKey: String): Boolean {
        val jsonBody = """{"sync_key":"$syncKey"}"""
        val jsonStr = performRequest("PATCH", "savings", "id=eq.$id", jsonBody)
        return jsonStr != null
    }

    // Comment: Update an existing Savings contribution row in remote database table,
    // targeting by its stable sync_key when available and falling back to the numeric id for legacy rows
    suspend fun dbUpdateSavings(savings: Savings): Boolean {
        val body = savingsToJson(savings, includeId = false).toString()
        val filter = if (savings.syncKey.isNotBlank()) "sync_key=eq.${savings.syncKey}" else "id=eq.${savings.id}"
        val jsonStr = performRequest("PATCH", "savings", filter, body)
        return jsonStr != null
    }

    // Comment: Soft-delete the remote savings row (PATCH deleted=true) instead of a hard DELETE so the deletion propagates to other devices instead of being resurrected by their next push.
    // deleted_at is formatted with SimpleDateFormat (not java.time) because minSdk is 24 and java.time is unavailable below API 26 without desugaring.
    suspend fun dbDeleteSavings(savings: Savings): Boolean {
        val isoNow = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())
        val jsonBody = """{"deleted":true,"deleted_at":"$isoNow"}"""
        // Comment: Target by sync_key when available so the soft delete still lands when the numeric ids have drifted
        val filter = if (savings.syncKey.isNotBlank()) "sync_key=eq.${savings.syncKey}" else "id=eq.${savings.id}"
        val jsonStr = performRequest("PATCH", "savings", filter, jsonBody)
        return jsonStr != null
    }

    // Comment: Fetch AppSettings row from remote database table
    suspend fun dbFetchSettings(): AppSettings? {
        val jsonStr = performRequest("GET", "app_settings", "select=*") ?: return null
        return try {
            val arr = org.json.JSONArray(jsonStr)
            if (arr.length() > 0) jsonToSettings(arr.getJSONObject(0)) else null
        } catch (e: Exception) {
            null
        }
    }

    // Comment: Upsert (insert or update) AppSettings in remote database table
    suspend fun dbUpsertSettings(settings: AppSettings): Boolean {
        val body = settingsToJson(settings, includeId = false).toString()
        val existing = dbFetchSettings()
        return if (existing != null) {
            performRequest("PATCH", "app_settings", "id=eq.${existing.id}", body) != null
        } else {
            performRequest("POST", "app_settings", "", body) != null
        }
    }

    // Comment: Fetch all ChangeRequests from remote database table
    suspend fun dbFetchChangeRequests(): List<ChangeRequest> {
        val jsonStr = performRequest("GET", "change_requests", "select=*") ?: return emptyList()
        val list = mutableListOf<ChangeRequest>()
        try {
            val arr = org.json.JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                list.add(jsonToChangeRequest(arr.getJSONObject(i)))
            }
        } catch (e: Exception) {
            Log.e("SupabaseDb", "Error parsing change requests response", e)
        }
        return list
    }

    // Comment: Create a new ChangeRequest row inside remote database table
    suspend fun dbInsertChangeRequest(request: ChangeRequest): ChangeRequest? {
        val body = changeRequestToJson(request, includeId = false).toString()
        val jsonStr = performRequest("POST", "change_requests", "", body) ?: return null
        return try {
            val arr = org.json.JSONArray(jsonStr)
            if (arr.length() > 0) jsonToChangeRequest(arr.getJSONObject(0)) else null
        } catch (e: Exception) {
            null
        }
    }

    // Comment: Update an existing ChangeRequest row in remote database table
    suspend fun dbUpdateChangeRequest(request: ChangeRequest): Boolean {
        val body = changeRequestToJson(request, includeId = false).toString()
        val jsonStr = performRequest("PATCH", "change_requests", "id=eq.${request.id}", body)
        return jsonStr != null
    }

    // Comment: Delete an existing ChangeRequest row from remote database table
    suspend fun dbDeleteChangeRequest(request: ChangeRequest): Boolean {
        val jsonStr = performRequest("DELETE", "change_requests", "id=eq.${request.id}")
        return jsonStr != null
    }
}
