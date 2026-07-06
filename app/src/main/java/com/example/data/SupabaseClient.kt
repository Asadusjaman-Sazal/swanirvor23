package com.example.data

import android.util.Log
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
 * Uses the exact credentials specified in src/supabaseClient.js:
 * URL: https://ykbwzodadijtiizobehk.supabase.co
 * Key: sb_publishable_0TnekhtKdN_MUcTiZ66sZA_qGjqqFyh
 */
object SupabaseClient {
    private const val SUPABASE_URL = "https://ykbwzodadijtiizobehk.supabase.co"
    private const val SUPABASE_PUBLIC_KEY = "sb_publishable_0TnekhtKdN_MUcTiZ66sZA_qGjqqFyh"

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private var sharedPreferences: android.content.SharedPreferences? = null

    // Comment: Initialize the shared preferences session store with application context
    fun init(context: android.content.Context) {
        sharedPreferences = context.applicationContext.getSharedPreferences("SupabaseAuthPrefs", android.content.Context.MODE_PRIVATE)
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
                Log.d("SupabaseAuth", "SignUp Response Code: ${response.code}, Body: $bodyStr")
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
                Log.d("SupabaseAuth", "SignIn Response Code: ${response.code}, Body: $bodyStr")
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
                Log.d("SupabaseAuth", "FetchUser Response Code: ${response.code}, Body: $bodyStr")
                if (response.isSuccessful) {
                    val userObj = JSONObject(bodyStr)
                    val userEmail = userObj.optString("email") ?: ""

                    // Construct standard session JSON object
                    val sessionJson = JSONObject().apply {
                        put("access_token", accessToken)
                        put("refresh_token", refreshToken)
                        put("expires_in", expiresIn.toIntOrNull() ?: 3600)
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
                Log.d("SupabaseAuth", "Recover Response Code: ${response.code}, Body: $bodyStr")
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
                Log.d("SupabaseAuth", "Update Password Response Code: ${response.code}, Body: $bodyStr")
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
        bodyJson: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val token = getAccessToken() ?: SUPABASE_PUBLIC_KEY
        val url = if (query.isNotEmpty()) "$SUPABASE_URL/rest/v1/$table?$query" else "$SUPABASE_URL/rest/v1/$table"

        val builder = Request.Builder()
            .url(url)
            .addHeader("apikey", SUPABASE_PUBLIC_KEY)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Prefer", "return=representation")

        when (method.uppercase()) {
            "GET" -> builder.get()
            "POST" -> bodyJson?.let { builder.post(it.toRequestBody(jsonMediaType)) }
            "PATCH" -> bodyJson?.let { builder.patch(it.toRequestBody(jsonMediaType)) }
            "DELETE" -> builder.delete()
        }

        try {
            client.newCall(builder.build()).execute().use { response ->
                val responseStr = response.body?.string()
                Log.d("SupabaseDb", "Request $method $table $query Response Code: ${response.code}, Body: $responseStr")
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
            put("password", member.password)
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
            password = json.optString("password", "password"),
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
            timestamp = json.optLong("timestamp", 0L)
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
                Log.d("SupabaseStorage", "Upload avatar response code: ${response.code}, Body: $responseStr")
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
        val jsonStr = performRequest("PATCH", "members", "email=eq.${member.email}", bodyObj.toString())
        return jsonStr != null
    }

    // Comment: Delete an existing Member row from remote database table
    suspend fun dbDeleteMember(member: Member): Boolean {
        val jsonStr = performRequest("DELETE", "members", "email=eq.${member.email}")
        return jsonStr != null
    }

    // Comment: Fetch all Savings contributions from remote database table
    suspend fun dbFetchSavings(): List<Savings> {
        val jsonStr = performRequest("GET", "savings", "select=*") ?: return emptyList()
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

    // Comment: Update an existing Savings contribution row in remote database table
    suspend fun dbUpdateSavings(savings: Savings): Boolean {
        val body = savingsToJson(savings, includeId = false).toString()
        val jsonStr = performRequest("PATCH", "savings", "id=eq.${savings.id}", body)
        return jsonStr != null
    }

    // Comment: Delete an existing Savings contribution row from remote database table
    suspend fun dbDeleteSavings(savings: Savings): Boolean {
        val jsonStr = performRequest("DELETE", "savings", "id=eq.${savings.id}")
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
