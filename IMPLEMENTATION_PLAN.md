# Comprehensive Implementation Plan: Fix Battery Drainage, Security & Performance Issues

## Root Cause Analysis
The severe battery drainage (80% → 34% overnight) is caused by:
1. **Infinite background sync loop** in `SavingsViewModel.kt` (lines 93-128) that runs every 5 seconds with `while(true)` 
2. **Improper lifecycle management**: Sync starts in both `onCreate()` and `onResume()` but only stops in `onPause()`
3. **No foreground service requirement**: Background work continues even when app appears closed
4. **Process killing interference**: `Process.killProcess()` in MainScreen.kt disrupts Android lifecycle

---

## PHASE 1: CRITICAL - Fix Battery Drainage (Immediate)

### Phase 1.1: Remove Infinite Sync Loop from ViewModel
**File**: `/workspace/app/src/main/java/com/example/ui/viewmodel/SavingsViewModel.kt`

**Changes**:
1. Delete the entire `startPeriodicSync()` function (lines 87-130)
2. Delete the `stopPeriodicSync()` function (lines 132-136)
3. Delete the `syncJob` property (line 85)

**Exact code to remove** (lines 85-136):
```kotlin
private var syncJob: kotlinx.coroutines.Job? = null

fun startPeriodicSync() {
    if (syncJob != null && syncJob?.isActive == true) return
    android.util.Log.d("SupabaseSync", "Starting lifecycle-aware foreground periodic sync loop...")
    syncJob = viewModelScope.launch {
        kotlinx.coroutines.delay(5000)
        while (true) {
            val email = com.example.data.SupabaseClient.getSessionEmail()
            if (!email.isNullOrBlank() && !isManualSyncing.value) {
                try {
                    android.util.Log.d("SupabaseSync", "Periodic active-foreground sync triggering...")
                    val newlyPulled = repository.syncWithSupabase()
                    // ... notification logic ...
                } catch (e: Exception) {
                    android.util.Log.w("SupabaseSync", "Periodic active-foreground sync skipped/failed: ${e.localizedMessage}")
                }
            }
            kotlinx.coroutines.delay(5000)
        }
    }
}

fun stopPeriodicSync() {
    android.util.Log.d("SupabaseSync", "Stopping periodic sync loop (inactive/backgrounded)...")
    syncJob?.cancel()
    syncJob = null
}
```

### Phase 1.2: Update MainActivity to Remove Sync Calls
**File**: `/workspace/app/src/main/java/com/example/MainActivity.kt`

**Changes**:
1. Remove line 51: `viewModel.startPeriodicSync()`
2. Remove line 96 in `onResume()`: `savingsViewModel?.startPeriodicSync()`
3. Remove lines 99-102: Delete entire `onPause()` method
4. Remove line 42: `private var savingsViewModel: SavingsViewModel? = null`

### Phase 1.3: Replace Process.killProcess() with Proper Exit
**File**: `/workspace/app/src/main/java/com/example/ui/view/MainScreen.kt`

**Location**: Search for `Process.killProcess` (around line 250)

**Change**:
```kotlin
// BEFORE:
android.os.Process.killProcess(android.os.Process.myPid())

// AFTER:
finishAffinity()
```

### Phase 1.4: Keep Manual Sync Only
Retain the `triggerManualSync()` function in `SavingsViewModel.kt` (lines 281-317) as it's user-initiated.

---

## PHASE 2: HIGH PRIORITY - Security Fixes

### Phase 2.1: Remove Plaintext Password Storage
**Your Question**: "If plaintext passwords are removed, how would the app retrieve them?"

**Answer**: The app should **NEVER** retrieve or store passwords locally. 

**Current (INSECURE) Flow**:
- Registration: User enters password → Store in Room DB + Supabase Auth ❌
- Login: Read password from Room DB → Send to Supabase ❌

**Correct (SECURE) Flow**:
- Registration: User enters password → Send directly to Supabase Auth ONLY ✓
- Login: User enters password → Send directly to Supabase Auth ✓
- Session: Store Supabase access token (NOT password) in SharedPreferences ✓

**Why This Works**:
- Supabase Auth handles password hashing, storage, and verification securely on their servers
- Your app only needs to verify the session token exists, not the password
- When a user logs in, they enter their password which goes directly to Supabase
- Supabase returns an access token if credentials are valid
- Your app stores the token, not the password
- On app restart, check if token exists (user is logged in), not password

**File**: `/workspace/app/src/main/java/com/example/data/model/Entities.kt`

**Change**: Remove the `password` field from `Member` entity (line 22):
```kotlin
@Entity(tableName = "members")
data class Member(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val email: String,
    val avatarUrl: String? = null,
    val totalSavings: Double = 0.0,
    val role: String = "Member",
    val status: String = "Active",
    val receivedAdminNotification: Boolean = false,
    // REMOVE THIS LINE: val password: String = "password",
    val membershipNo: String = ""
)
```

**Migration**: Increment Room database version and add migration, OR instruct users to clear app data.

### Phase 2.2: Remove Password Assignment in ViewModel
**File**: `/workspace/app/src/main/java/com/example/ui/viewmodel/SavingsViewModel.kt`

**Action**: Search for all places where `Member(...)` is created with `password =` parameter and remove that parameter.

### Phase 2.3: Increase Password Requirements
**File**: `/workspace/app/src/main/java/com/example/ui/viewmodel/SavingsViewModel.kt`

**Location**: Find password validation (around line 715)

**Change**:
```kotlin
// BEFORE:
if (password.length < 6) {
    onResult(false, "Password must be at least 6 characters long.")
}

// AFTER:
if (password.length < 8) {
    onResult(false, "Password must be at least 8 characters long.")
    return@launch
}
if (!password.any { it.isDigit() } || !password.any { it.isLetter() }) {
    onResult(false, "Password must contain at least one letter and one number.")
    return@launch
}
```

### Phase 2.4: Move Supabase Credentials to BuildConfig
**File**: `/workspace/app/build.gradle.kts`

**Add** inside the `android { }` block:
```kotlin
buildFeatures {
    buildConfig = true
}

buildTypes {
    debug {
        buildConfigField("String", "SUPABASE_URL", "\"https://ykbwzodadijtiizobehk.supabase.co\"")
        buildConfigField("String", "SUPABASE_PUBLIC_KEY", "\"sb_publishable_0TnekhtKdN_MUcTiZ66sZA_qGjqqFyh\"")
    }
    release {
        buildConfigField("String", "SUPABASE_URL", "\"https://ykbwzodadijtiizobehk.supabase.co\"")
        buildConfigField("String", "SUPABASE_PUBLIC_KEY", "\"sb_publishable_0TnekhtKdN_MUcTiZ66sZA_qGjqqFyh\"")
        isMinifyEnabled = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
}
```

**File**: `/workspace/app/src/main/java/com/example/data/SupabaseClient.kt`

**Change** (lines 23-24):
```kotlin
// BEFORE:
private const val SUPABASE_URL = "https://ykbwzodadijtiizobehk.supabase.co"
private const val SUPABASE_PUBLIC_KEY = "sb_publishable_0TnekhtKdN_MUcTiZ66sZA_qGjqqFyh"

// AFTER:
private val SUPABASE_URL = com.example.BuildConfig.SUPABASE_URL
private val SUPABASE_PUBLIC_KEY = com.example.BuildConfig.SUPABASE_PUBLIC_KEY
```

---

## PHASE 3: MEDIUM PRIORITY - Token Management

### Phase 3.1: Add Token Expiration Check
**File**: `/workspace/app/src/main/java/com/example/data/SupabaseClient.kt`

**Add** after `getSession()` function:
```kotlin
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

fun getRefreshToken(): String? {
    val sessionStr = getSession() ?: return null
    return try {
        val json = JSONObject(sessionStr)
        json.optString("refresh_token")
    } catch (e: Exception) {
        null
    }
}
```

### Phase 3.2: Implement Token Refresh Function
**File**: `/workspace/app/src/main/java/com/example/data/SupabaseClient.kt`

**Add** new function:
```kotlin
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
            if (response.isSuccessful) {
                val jsonResponse = JSONObject(bodyStr)
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
```

### Phase 3.3: Add Session Validation Before API Calls
**File**: `/workspace/app/src/main/java/com/example/data/repository/SavingsRepository.kt`

**Update** `syncWithSupabase()` to check token validity at the start.

---

## PHASE 4: PERFORMANCE OPTIMIZATIONS

### Phase 4.1: Cache Database Queries in Sync
**File**: `/workspace/app/src/main/java/com/example/data/repository/SavingsRepository.kt`

**In `syncWithSupabase()`**, cache DAO calls at the start:
```kotlin
val allLocalMembers = memberDao.getAllMembersDirect()
val allLocalSavings = savingsDao.getAllSavingsDirect()
val allLocalChangeRequests = changeRequestDao.getAllChangeRequestsDirect()
```
Then use these cached values throughout the method instead of calling DAOs repeatedly.

### Phase 4.2: Add Network Timeouts
**File**: `/workspace/app/src/main/java/com/example/data/SupabaseClient.kt`

**Change** (line 26):
```kotlin
// BEFORE:
private val client = OkHttpClient()

// AFTER:
private val client = OkHttpClient.Builder()
    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
    .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()
```

### Phase 4.3: Keep Force Redraw (Per Your Comment Policy)
**File**: `/workspace/app/src/main/java/com/example/MainActivity.kt`

Keep the force redraw code in `onResume()` but add TODO comment for future investigation.

---

## PHASE 5: CODE QUALITY (Respecting Your Comment Policy)

### Phase 5.1: Extract Constants
**Create** `/workspace/app/src/main/java/com/example/util/Constants.kt`:
```kotlin
package com.example.util

object Constants {
    const val DEEP_LINK_SCHEME = "swanirvor23"
    const val MIN_PASSWORD_LENGTH = 8
    val SEED_EMAILS = setOf(
        "sarah.j@example.com", "m.reyes@example.com", "elena.r@example.com",
        "d.chen@example.com", "amanda@example.com", "jane.d@example.com",
        "r.smith@example.com", "ev.lin@example.com", "john.doe@example.com",
        "test@email.com"
    )
}
```

### Phase 5.2: Keep Intentional Comments
Per your request, comments are intentional. Only remove truly redundant ones.

---

## PHASE 6: TESTING & VALIDATION

### Phase 6.1: Battery Drain Test
1. Install modified app
2. Log in, close app
3. Leave overnight (8+ hours)
4. **Expected**: < 5% drain (vs previous 46%)

### Phase 6.2: Functional Testing
- [ ] User can sign up with 8+ character password
- [ ] Manual sync works
- [ ] App exits cleanly
- [ ] Notifications work
- [ ] Deep link auth works

### Phase 6.3: Security Validation
- [ ] Password field removed from Room DB
- [ ] Supabase keys in BuildConfig
- [ ] No plaintext passwords stored

---

## ADDITIONAL ISSUE: Deleted Savings Reappearing

**Problem**: When Admin A adds a savings entry for Member D, then deletes it, the deleted data reappears on other users' devices.

**Root Cause**: The sync logic treats remote data as source of truth but doesn't properly handle deletions. When Admin A deletes:
1. Deleted from Admin A's local DB and Supabase
2. Users B and C still have it in their local DBs
3. During sync, their local DBs push the "old" savings back to Supabase
4. Next sync pulls it back to all devices

**Solution**: Implement soft deletes with a `deleted` flag.

### Fix Steps:

**Step 1**: Add deletion tracking to Supabase `savings` table:
```sql
ALTER TABLE savings ADD COLUMN deleted BOOLEAN DEFAULT FALSE;
ALTER TABLE savings ADD COLUMN deleted_at TIMESTAMPTZ;
CREATE INDEX idx_savings_deleted ON savings(deleted);
```

**Step 2**: Update `SupabaseClient.kt` `dbDeleteSavings()`:
```kotlin
// Change from DELETE to PATCH (soft delete)
suspend fun dbDeleteSavings(savings: Savings): Boolean {
    val jsonBody = """{"deleted":true,"deleted_at":"${java.time.Instant.now()}"}"""
    val result = performRequest("PATCH", "savings", "id=eq.${savings.id}", jsonBody)
    return result != null
}
```

**Step 3**: Update `dbFetchSavings()` to filter deleted:
```kotlin
suspend fun dbFetchSavings(): List<Savings> {
    val jsonStr = performRequest("GET", "savings", "select=*&deleted=is.null") ?: return emptyList()
    // ... rest unchanged
}
```

**Step 4**: Update local sync to skip soft-deleted records:
```kotlin
val filteredRemoteSavings = remoteSavings.filter { 
    it.memberId in activeMemberIds && !it.deleted 
}
```

This ensures deletions propagate correctly across all 20 users' devices.

---

## IMPLEMENTATION ORDER

1. **Phase 1** (CRITICAL): Remove infinite sync loop - 30 min
2. **Phase 2** (HIGH): Security fixes - 45 min
3. **Phase 3** (MEDIUM): Token management - 60 min
4. **Phase 4** (MEDIUM): Performance - 45 min
5. **Phase 5** (LOW): Code quality - 30 min
6. **Phase 6** (REQUIRED): Testing - 120+ min

**Total**: ~5-6 hours + testing

---

## FILES TO MODIFY

1. `app/src/main/java/com/example/ui/viewmodel/SavingsViewModel.kt`
2. `app/src/main/java/com/example/MainActivity.kt`
3. `app/src/main/java/com/example/ui/view/MainScreen.kt`
4. `app/src/main/java/com/example/data/model/Entities.kt`
5. `app/src/main/java/com/example/data/SupabaseClient.kt`
6. `app/src/main/java/com/example/data/repository/SavingsRepository.kt`
7. `app/build.gradle.kts`
8. `app/src/main/java/com/example/util/Constants.kt` (NEW)
