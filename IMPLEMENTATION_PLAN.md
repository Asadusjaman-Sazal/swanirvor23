# Swanirvor-23 Implementation Plan

A single, ordered plan to fix battery drain, security, data-integrity, performance, and code-quality issues. It consolidates the original plan and the additional issues found during the full-project code review. No fixes from either document have been removed — overlapping items have been merged into one section.

> **Path note**: all file paths are normalized to this repository's layout (`app/src/main/java/com/example/...`). The previous draft referenced `/workspace/...`, which does not match this checkout.

## How to use this plan

1. Work through the phases **in order**. Later phases build on earlier ones.
2. Each phase ends with a **Testing method** and, where marked, a **Manual verification (REQUIRED)** checklist.
3. **Rule: do not start the next phase until the manual verification checklist for the current critical phase passes.** Phases marked **CRITICAL** gate the rest of the work.
4. Fix nothing that is not listed without adding it to this plan first.

**Critical phases**: Phase 1 (battery/lifecycle), Phase 2 (secrets/security), Phase 4 (data integrity), Phase 8 (final release gate).

---

## Root Cause Summary

The severe battery drainage (80% → 34% overnight) is caused by:

1. **Infinite background sync loop** in `SavingsViewModel.kt` that runs every 5 seconds with `while(true)`.
2. **Improper lifecycle management**: sync starts in both `onCreate()` and `onResume()` but only stops in `onPause()`.
3. **Background work continuing**: no foreground-service requirement, so work continues while the app appears closed.
4. **Process killing interference**: `Process.killProcess()` in `MainScreen.kt` disrupts the Android lifecycle.

Additional root causes surfaced by the review: plaintext password/token storage, hardcoded credentials, destructive migrations, and a fragile local-vs-remote ID mapping in sync.

---

## Phase 1 — CRITICAL: Stop Battery Drain (Polling Loop & Lifecycle)

### 1.1 Remove the Infinite Sync Loop from the ViewModel
**File**: `app/src/main/java/com/example/ui/viewmodel/SavingsViewModel.kt`

**Changes**:
1. Delete the entire `startPeriodicSync()` function.
2. Delete the `stopPeriodicSync()` function.
3. Delete the `syncJob` property.

**Code to remove**:
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

### 1.2 Remove Sync Calls from MainActivity
**File**: `app/src/main/java/com/example/MainActivity.kt`

> **Revision (decided during implementation)**: keep `private var savingsViewModel: SavingsViewModel? = null` and its `savingsViewModel = viewModel` assignment. It is still used by `onNewIntent()` to handle deep links (Google OAuth and password-recovery callbacks). Removing it would break deep-link login and would not compile.

**Changes**:
1. Remove `viewModel.startPeriodicSync()` from `onCreate()`/`setContent`.
2. Remove `savingsViewModel?.startPeriodicSync()` from `onResume()`.
3. Delete the entire `onPause()` method (it only called `stopPeriodicSync()`).
4. Keep `private var savingsViewModel: SavingsViewModel? = null` — only the sync usage is removed (see revision note above).

### 1.3 Replace `Process.killProcess()` with a Clean Exit
**File**: `app/src/main/java/com/example/ui/view/MainScreen.kt`

**Location**: the exit-confirmation dialog.

**Change**:
```kotlin
// BEFORE:
android.os.Process.killProcess(android.os.Process.myPid())

// AFTER:
finishAffinity()
```

Keep `finishAndRemoveTask()`/`finishAffinity()` (a normal activity finish) and delete the `postDelayed { killProcess }` block entirely — killing the process bypasses lifecycle cleanup and is what disrupts cold/warm startup state.

### 1.4 Replace Polling with Event-Driven Sync (Keep Manual Sync)
Retain the `triggerManualSync()` function in `SavingsViewModel.kt` — it is user-initiated and correct. "Keep manual sync only" means **remove the fixed 5-second poller**, not remove all automatic sync.

The replacement model is event-driven (finite, no `while(true)` loop):
- sync once on cold start / session restore,
- sync once when the app returns to the foreground (`onResume`),
- sync once after a meaningful local write (debounced),
- sync on token refresh,
- manual sync via the existing button.

Do **not** reintroduce any fixed-interval loop, even if it stops in the background — polling every 5 seconds while the screen is on is the battery problem itself.

### Phase 1 — Verification

**Manual verification (REQUIRED before proceeding):**
- [ ] Build and launch the app; with logcat filtered on `SupabaseSync`, confirm there are **no** repeating sync logs every 5 seconds while the screen is idle.
- [ ] Press back → choose **Exit**: the app closes normally and relaunches cleanly (no force-kill, no white/blank warm-start screen).
- [ ] Background the app for ~1 minute and confirm (via logcat) that no sync runs while backgrounded.

**Testing method:**
- Run the overnight battery test described in Phase 8.1 after this phase is complete.
- Use Android Studio's **Energy Profiler** for a 15-minute idle session; expect no sustained network wakeups (contrast with the pre-fix profile).

---

## Phase 2 — CRITICAL: Eliminate Plaintext Secrets & Hardcoded Credentials

### 2.1 Remove Plaintext Password Storage
**Question**: "If plaintext passwords are removed, how would the app retrieve them?"

**Answer**: the app should **never** retrieve or store passwords locally.

**Current (INSECURE) flow**:
- Registration: user enters password → stored in Room DB **and** Supabase Auth ❌
- Login: read password from Room DB → send to Supabase ❌

**Correct (SECURE) flow**:
- Registration: user enters password → sent directly to Supabase Auth only ✓
- Login: user enters password → sent directly to Supabase Auth ✓
- Session: store the Supabase access token (not the password) ✓

**Why this works**:
- Supabase Auth hashes, stores, and verifies passwords on its servers.
- The app only needs to verify a session token exists, never the password.
- On login the user re-enters the password, which goes straight to Supabase; Supabase returns a token on success.
- On restart, the app checks for a token (logged in), never a password.

**File**: `app/src/main/java/com/example/data/model/Entities.kt`

**Change**: remove the `password` field from the `Member` entity:
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

**Migration**: bump the Room database version and add a real migration (see Phase 4.2). Do **not** rely on instructing users to clear app data.

### 2.2 Remove Password Assignment in the ViewModel
**File**: `app/src/main/java/com/example/ui/viewmodel/SavingsViewModel.kt`

**Action**: find every `Member(...)` construction that passes `password = ...` (including `updatePassword`, `login`, `signUp`, `handleGoogleLoginCallback`, `handleRecoveryCallback`) and remove that parameter.

### 2.3 Remove Password from Supabase Serialization + Drop the Remote Column
**File**: `app/src/main/java/com/example/data/SupabaseClient.kt`

Removing the local field is not enough — the remote side must be handled too:

1. In `memberToJson()` remove: `put("password", member.password)`.
2. In `jsonToMember()` remove: `password = json.optString("password", "password")`.
3. Drop the column on the Supabase `members` table so the plaintext problem does not simply move server-side:
```sql
ALTER TABLE members DROP COLUMN IF EXISTS password;
```

### 2.4 Enforce the Password Policy at Every Entry Point
**Files**: `app/src/main/java/com/example/ui/viewmodel/SavingsViewModel.kt`, `app/src/main/java/com/example/ui/view/MainScreen.kt`

Replace the single `length < 6` check with a minimum of 8 characters **plus** at least one letter and one number, applied everywhere a password is created/updated:

1. `updatePassword()` in the ViewModel.
2. `signUp()` in the ViewModel (currently has **no** client-side validation).
3. The reset-password dialog in `MainScreen.kt` (update the "At least 6 characters" placeholder text and the `length < 6` guard).

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

### 2.5 Remove Hardcoded Credentials (One Config Source)
The Supabase URL and publishable key are hardcoded in **three** places. Consolidate them into a single configuration source.

**a) `SupabaseClient.kt`** — replace the constants:
```kotlin
// BEFORE:
private const val SUPABASE_URL = "https://ykbwzodadijtiizobehk.supabase.co"
private const val SUPABASE_PUBLIC_KEY = "sb_publishable_0TnekhtKdN_MUcTiZ66sZA_qGjqqFyh"

// AFTER:
private val SUPABASE_URL = com.example.BuildConfig.SUPABASE_URL
private val SUPABASE_PUBLIC_KEY = com.example.BuildConfig.SUPABASE_PUBLIC_KEY
```

**b) `AuthScreen.kt`** — the Google OAuth button hardcodes the full authorize URL:
```kotlin
val url = "https://ykbwzodadijtiizobehk.supabase.co/auth/v1/authorize?..."
```
Build it from `BuildConfig.SUPABASE_URL` instead.

**c) `src/supabaseClient.js`** — a leftover web file containing the same keys. Delete it (unused by the Android app).

**Config mechanism** — pick **one** of these and finish it end-to-end (do not leave the secrets plugin and hardcoded strings both in place):
- **Secrets plugin (preferred, already configured)**: add `SUPABASE_URL` and `SUPABASE_ANON_KEY` to `.env.example` and read them via the generated `BuildConfig`. Note the current `.env.example` only contains `GEMINI_API_KEY`, and the README documents Supabase env vars that are not actually wired up.
- **Explicit BuildConfig fields** in `app/build.gradle.kts`:
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

### 2.6 Encrypt the Session Store (Access/Refresh Tokens)
**File**: `app/src/main/java/com/example/data/SupabaseClient.kt`

The session JSON is stored in plaintext `SharedPreferences`. A rooted device or a cloud backup can extract the access + refresh tokens and impersonate the user. Use `EncryptedSharedPreferences` (or the Android Keystore):

```kotlin
// add dependency: androidx.security:security-crypto:1.1.0-alpha06
val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()
sharedPreferences = EncryptedSharedPreferences.create(
    context,
    "SupabaseAuthPrefs",
    masterKey,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)
```

### 2.7 Disable or Restrict Android Auto-Backup
**Files**: `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml`

The manifest has `android:allowBackup="true"` and both backup-rule files are the empty sample templates, so the **entire** Room DB (currently containing plaintext passwords) and the auth `SharedPreferences` (session tokens) are uploaded to Google Drive cloud backup.

**Fix (either)**:
```xml
<application android:allowBackup="false" ...>
```
…or add explicit exclusions in both XML files:
```xml
<exclude domain="database" path="." />
<exclude domain="sharedpref" path="SupabaseAuthPrefs.xml" />
```

### 2.8 Stop Logging Tokens and PII
**File**: `app/src/main/java/com/example/data/SupabaseClient.kt`

`signUp()`, `signInWithPassword()`, `fetchUserAndSaveSession()`, and `performRequest()` log the **full HTTP response bodies** (`Log.d(..., "Body: $bodyStr")`). These contain access tokens, refresh tokens, emails, and PII that end up in logcat and any log-collection tooling.

**Fix**: remove body logging from auth endpoints (log status codes only), and in `performRequest()` drop or redact `Body: $responseStr` (especially in release builds).

### 2.9 URL-Encode Query Filter Values
**File**: `app/src/main/java/com/example/data/SupabaseClient.kt`

`dbUpdateMember()` and `dbDeleteMember()` build `email=eq.${member.email}` with no URL encoding. Emails containing `+`, spaces, or other reserved characters break the query or match the wrong row:

```kotlin
val encoded = java.net.URLEncoder.encode(member.email, "UTF-8")
performRequest("PATCH", "members", "email=eq.$encoded", body)
```

### Phase 2 — Verification

**Manual verification (REQUIRED before proceeding):**
- [ ] Inspect the Room database (Database Inspector or `adb shell` + `run-as` + sqlite): `members` has **no** `password` column.
- [ ] `grep -ri "ykbwzodadijtiizobehk\|sb_publishable\|password =" app/src` returns only the allowed config reference.
- [ ] Register with a weak password (e.g. `abcdef`, `12345678`, `short`) and confirm it is rejected with the policy message.
- [ ] During login, logcat shows **no** `access_token`/`refresh_token`/email in logged bodies.
- [ ] Confirm a cloud/`adb backup` no longer includes the database and `SupabaseAuthPrefs` (or backup is disabled).

**Testing method:**
- Unit tests: `jsonToMember`/`memberToJson` round-trip contains no `password` key; the shared password-validator rejects weak and accepts strong passwords.
- Add a CI grep step that fails the build if a hardcoded Supabase key reappears outside the allowed config file.

---

## Phase 3 — Token Management

### 3.1 Add Token Expiration Check
**File**: `app/src/main/java/com/example/data/SupabaseClient.kt`

Add after `getSession()`:
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

### 3.2 Implement Token Refresh
**File**: `app/src/main/java/com/example/data/SupabaseClient.kt`

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

### 3.3 Validate the Session Before API Calls
**File**: `app/src/main/java/com/example/data/repository/SavingsRepository.kt`

Update `syncWithSupabase()` to check token validity at the start (refresh first if `isSessionExpiringSoon()`).

### Phase 3 — Verification

**Testing method:**
- Unit tests for `isSessionExpiringSoon()` and `getRefreshToken()` using fixture JSON (near-expiry, far-expiry, missing field).
- Manual: force a short-lived token, confirm it auto-refreshes before expiry and logs the user out (clears session) when refresh fails.

---

## Phase 4 — CRITICAL: Data Integrity & Sync Correctness

### 4.1 Guard `syncWithSupabase()` Against Concurrent Runs
**File**: `app/src/main/java/com/example/data/repository/SavingsRepository.kt`

`syncWithSupabase()` is invoked from startup, `login()`, `handleGoogleLoginCallback()`, `updatePassword()`, and the manual-sync button. Several can overlap because only the manual path sets `isManualSyncing`. Overlapping syncs mutate the same tables and cause duplicate rows / lost updates.

```kotlin
private val syncMutex = kotlinx.coroutines.sync.Mutex()
suspend fun syncWithSupabase(): List<ChangeRequest> = syncMutex.withLock { ... }
```

### 4.2 Replace Destructive Migration with a Real Migration
**File**: `app/src/main/java/com/example/data/local/AppDatabase.kt`

`fallbackToDestructiveMigration()` wipes **all** local data on every schema version change. Add an explicit `Migration(6, 7)` (e.g., drop the `password` column from `members`) and remove `fallbackToDestructiveMigration()` so upgrading does not delete every member/savings row.

### 4.3 Make Multi-Step Writes Transactional
**File**: `app/src/main/java/com/example/data/repository/SavingsRepository.kt`

`insertMember()`, `insertSavings()`, and `insertChangeRequest()` do: insert locally → insert remotely → delete local row → re-insert with the remote ID. A crash between the delete and re-insert leaves a missing row or a stale total. Wrap each local multi-step mutation in a Room `@Transaction` (or `database.withTransaction { }`).

### 4.4 Harden the Local-vs-Remote ID Matching
**File**: `app/src/main/java/com/example/data/repository/SavingsRepository.kt`

The sync keys savings by `id`, but local IDs (Room auto-increment, per-device) and remote IDs (Supabase SERIAL) are different ID spaces. The fallback "duplicate by details" match (`memberId + amount + dateText + timestamp`) is fragile: a legitimate edit makes a row look brand-new, and two genuinely different rows with the same details are treated as one. The soft-delete fix (4.6) fixes resurrection but not this collision problem.

**Recommended direction**: give savings/members a stable natural/unique key (e.g., `(member_id, date_text)` or a client-generated UUID) and upsert on that key instead of matching numeric IDs across two independent sequences.

### 4.5 Verify RLS Allows the Writes the App Performs
**File**: `supabaseSql.txt` (and the Supabase project settings)

The app performs `PATCH` and `DELETE` on `members`, `savings`, and `change_requests` via PostgREST, but the README only mentions granting `SELECT`/`INSERT`. If RLS does not allow update/delete for authenticated users, those calls silently return `null` (logged and ignored) — the same class of bug behind "deleted savings reappearing". Confirm the RLS policies allow `UPDATE`/`DELETE` for the intended roles before relying on the soft-delete design below.

### 4.6 Fix "Deleted Savings Reappearing" with Soft Deletes
**Problem**: when Admin A adds a savings entry for Member D and then deletes it, the deleted data reappears on other users' devices.

**Root cause**: the sync treats remote data as source of truth but does not handle deletions. When Admin A deletes:
1. The row is deleted from Admin A's local DB and Supabase.
2. Users B and C still have it in their local DBs.
3. During sync, their local DBs push the "old" savings back to Supabase.
4. The next sync pulls it back to every device.

**Solution**: implement soft deletes with a `deleted` flag.

**Step 1** — add deletion tracking to the Supabase `savings` table:
```sql
ALTER TABLE savings ADD COLUMN deleted BOOLEAN DEFAULT FALSE;
ALTER TABLE savings ADD COLUMN deleted_at TIMESTAMPTZ;
CREATE INDEX idx_savings_deleted ON savings(deleted);
```

**Step 2** — update `SupabaseClient.dbDeleteSavings()` to PATCH (soft delete):
```kotlin
// Change from DELETE to PATCH (soft delete)
suspend fun dbDeleteSavings(savings: Savings): Boolean {
    val jsonBody = """{"deleted":true,"deleted_at":"${java.time.Instant.now()}"}"""
    val result = performRequest("PATCH", "savings", "id=eq.${savings.id}", jsonBody)
    return result != null
}
```

**Step 3** — update `dbFetchSavings()` to filter out deleted rows:
```kotlin
suspend fun dbFetchSavings(): List<Savings> {
    val jsonStr = performRequest("GET", "savings", "select=*&deleted=is.null") ?: return emptyList()
    // ... rest unchanged
}
```

**Step 4** — update local sync to skip soft-deleted records:
```kotlin
val filteredRemoteSavings = remoteSavings.filter {
    it.memberId in activeMemberIds && !it.deleted
}
```

This ensures deletions propagate correctly across all 20 users' devices.

### Phase 4 — Verification

**Manual verification (REQUIRED before proceeding):**
- [ ] Two devices (or device + emulator) signed in: Admin A deletes a savings entry; after the other device syncs, it stays deleted (no resurrection).
- [ ] Add a savings entry offline on device B, then sync; it appears on device A **exactly once** (no duplicates).
- [ ] Change a member's name; the new name propagates to their savings and change-request rows on the other device.
- [ ] Install the previous build, then upgrade to this build with the bumped schema; local data survives (not wiped).

**Testing method:**
- Unit-test the sync ID-mapping/duplicate-detection logic with fake DAOs and a fake remote client.
- Robolectric Room migration test for `Migration(6, 7)`.

---

## Phase 5 — Performance Optimizations

### 5.1 Cache Database Queries in Sync
**File**: `app/src/main/java/com/example/data/repository/SavingsRepository.kt`

In `syncWithSupabase()`, cache DAO calls at the start and reuse them instead of calling the DAOs repeatedly:
```kotlin
val allLocalMembers = memberDao.getAllMembersDirect()
val allLocalSavings = savingsDao.getAllSavingsDirect()
val allLocalChangeRequests = changeRequestDao.getAllChangeRequestsDirect()
```

### 5.2 Add Network Timeouts
**File**: `app/src/main/java/com/example/data/SupabaseClient.kt`

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

### 5.3 Keep the Force-Redraw Workaround (Add a TODO)
**File**: `app/src/main/java/com/example/MainActivity.kt`

Keep the force-redraw code in `onResume()` (it is an intentional workaround for the warm-relaunch rendering hang), but add a TODO comment for future investigation.

### Phase 5 — Verification

**Testing method:**
- Log timestamps around a sync and confirm the number of DAO reads per sync drops to one set.
- Compare a manual-sync duration before/after caching (expect a measurable improvement on large datasets).

---

## Phase 6 — Notifications & Session Lifecycle

### 6.1 Fix Exact-Alarm Handling (Android 12+)
**Files**: `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/example/ui/notification/NotificationHelper.kt`

`NotificationScheduler.scheduleNotification()` uses `AlarmManager.setAndAllowWhileIdle()`, an **exact** alarm. The manifest does not declare `SCHEDULE_EXACT_ALARM` (nor `USE_EXACT_ALARM`), so on Android 12+ this throws `SecurityException` and silently falls back to `set()`, making weekly reminders inexact. The scheduler also runs from a `LaunchedEffect(appSettings)` on every settings write, rescheduling frequently.

**Fix**: either declare `SCHEDULE_EXACT_ALARM` (and request/justify it) or deliberately use the inexact `set()` / `setInexactRepeating()` path. Move scheduling to a single source of truth rather than a per-settings-change effect.

### 6.2 Complete `logout()` Cleanup
**File**: `app/src/main/java/com/example/ui/viewmodel/SavingsViewModel.kt`

`logout()` clears the session and `_currentUserId`, but does not:
- stop the sync job (it keeps running until `onPause()`),
- clear `_googleLoginLoading` / `_googleLoginError` / `_showResetPasswordDialog`,
- clear the local cache.

Add those so a logged-out user cannot keep a stale sync loop or stale dialogs/errors.

### Phase 6 — Verification

**Manual verification:**
- [ ] Set a reminder 2 minutes ahead; confirm the notification fires.
- [ ] Reboot the device; confirm the alarm is rescheduled.
- [ ] On Android 13+, confirm the `POST_NOTIFICATIONS` prompt appears and reminders work once granted.
- [ ] Log out, then sign in again; no stale dialogs/errors and no background sync between logout and login.

**Testing method:**
- Unit-test `mapDayOfWeek()`, `parseTime()`, and `calculateNextTriggerTime()`.

---

## Phase 7 — Code Quality, Build & Automated Tests

### 7.1 Extract Constants
**Create** `app/src/main/java/com/example/util/Constants.kt`:
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

### 7.2 Keep Intentional Comments
Comments are intentional per the project's policy. Only remove truly redundant ones.

### 7.3 Remove Unused Dependencies
**File**: `app/build.gradle.kts`

`retrofit`, `converter-moshi`, `moshi-kotlin`, `moshi-kotlin-codegen`, `logging-interceptor`, and `firebase-ai` are declared but the app uses plain `OkHttp` + `org.json`. Remove them (or wire them up) to shrink the APK and reduce the attack surface. (`firebase-ai` + `GEMINI_API_KEY` in `metadata.json` appear to be dead scaffolding.)

### 7.4 Version & Release Hygiene
**Files**: `app/build.gradle.kts`, `.github/workflows/supabase-keep-alive.yml`

- `versionCode` is hardcoded to `1` while `versionName` increments; Play Console rejects uploads with a non-increasing `versionCode`. Tie it to version history or increment it per release.
- The release `signingConfig` reads `System.getenv(...)` with no fallback; a missing env var yields a null store password and a failed or mis-signed release build.
- The keep-alive workflow's `curl` lacks `--fail`, so a down Supabase instance does not fail the job.

### 7.5 Split the Monolithic `MainScreen.kt`
**File**: `app/src/main/java/com/example/ui/view/MainScreen.kt` (4112 lines)

One file holds the splash, exit dialog, top bar, all four tabs, admin panel, settings, PDF export, CSV export, and helper functions — the biggest maintainability risk in the project. Extract `HomeScreen`, `MembersScreen`, `AdminScreen`, `SettingsScreen`, `PdfExport`, `CsvExport`, and the date helpers into their own files under `ui/view/` and `ui/util/`.

### 7.6 Add Automated Tests (Not Just Manual Checklists)
**Files**: `app/src/test/...`

The only tests are the template examples plus a screenshot smoke test. Add unit tests for:
- `parseDateTextToMillis` / weekly-cycle range math,
- `escapeCsv`,
- password validation (extracted into a shared function in 2.4),
- `syncWithSupabase` ID-mapping logic with fake DAOs/remote client,
- `jsonToMember` / `jsonToSavings` round-trips.

### Phase 7 — Verification

**Testing method:**
- Run `./gradlew test lint assembleDebug` and fix all failures.
- Confirm the removed dependencies no longer appear in the dependency tree (`./gradlew app:dependencies`).
- Compare APK size before/after dependency removal.

---

## Phase 8 — Final Validation & Release Gate (CRITICAL)

### 8.1 Battery Drain Test
1. Install the modified app.
2. Log in, close the app.
3. Leave overnight (8+ hours).
4. **Expected**: < 5% drain (vs the previous 46%).

### 8.2 Functional Testing
- [ ] User can sign up with an 8+ character password (with letter + number).
- [ ] Manual sync works.
- [ ] App exits cleanly.
- [ ] Notifications work.
- [ ] Deep link auth works.

### 8.3 Security Validation
- [ ] Password field removed from Room DB.
- [ ] Supabase keys in BuildConfig (single config source).
- [ ] No plaintext passwords stored anywhere (local or remote).
- [ ] Session tokens encrypted at rest and excluded from backup.

### Phase 8 — Verification

**Manual verification (REQUIRED before release):**
- [ ] Run through Phases 8.1–8.3 and record the results.
- [ ] Regression check on one real device + one emulator: sign up, sign in, add/edit/delete a savings entry, approve a change request, and export CSV/PDF.
- [ ] Confirm the release build is signed with the release keystore and `versionCode` increased.

**Testing method:**
- Execute the full unit + Robolectric suite from Phase 7.6.
- Capture a before/after battery measurement (pre-fix vs post-fix) for the changelog.

---

## Implementation Order & Effort

1. **Phase 1** (CRITICAL): remove infinite sync loop + `killProcess` — 30 min
2. **Phase 2** (CRITICAL): secrets — password removal/serialization, encrypted session, backup, log redaction, hardcoded creds, password policy — 90 min
3. **Phase 3** (MEDIUM): token refresh — 60 min
4. **Phase 4** (CRITICAL): sync mutex, migrations, transactions, ID handling, RLS, soft deletes — 90 min
5. **Phase 5** (MEDIUM): performance (caching, timeouts) — 45 min
6. **Phase 6** (MEDIUM): alarms & logout lifecycle — 45 min
7. **Phase 7** (LOW): constants, deps, versioning, monolith split, automated tests — 90 min
8. **Phase 8** (REQUIRED): manual + automated validation — 120+ min

**Total**: ~6–8 hours including verification.

---

## Files to Modify

1. `app/src/main/java/com/example/ui/viewmodel/SavingsViewModel.kt`
2. `app/src/main/java/com/example/MainActivity.kt`
3. `app/src/main/java/com/example/ui/view/MainScreen.kt`
4. `app/src/main/java/com/example/ui/view/AuthScreen.kt`
5. `app/src/main/java/com/example/data/model/Entities.kt`
6. `app/src/main/java/com/example/data/local/AppDatabase.kt`
7. `app/src/main/java/com/example/data/SupabaseClient.kt`
8. `app/src/main/java/com/example/data/repository/SavingsRepository.kt`
9. `app/src/main/java/com/example/ui/notification/NotificationHelper.kt`
10. `app/src/main/AndroidManifest.xml`
11. `app/src/main/res/xml/backup_rules.xml`
12. `app/src/main/res/xml/data_extraction_rules.xml`
13. `app/build.gradle.kts`
14. `.env.example`
15. `.github/workflows/supabase-keep-alive.yml`
16. `src/supabaseClient.js` (DELETE)
17. `app/src/main/java/com/example/util/Constants.kt` (NEW)
18. `app/src/test/...` (NEW tests)
