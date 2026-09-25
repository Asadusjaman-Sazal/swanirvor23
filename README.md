# Swanirvor 23

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Supabase](https://img.shields.io/badge/Backend-Supabase-3ECF8E?style=for-the-badge&logo=supabase&logoColor=white)](https://supabase.com)

**Swanirvor 23** is a highly polished, robust, offline-first financial ledger and collective savings tracking application for Android. Built with a modern Jetpack Compose (Material 3) frontend and backed by Supabase Serverless Infrastructure, the application is designed to orchestrate complex shared financial accounting, synchronize data in real-time, and run advanced periodic savings reports securely.

---

## 🚀 Key Features

### 🔄 Bidirectional Database Sync & Offline-First Core
* **Offline Resilience**: Runs on a local SQLite database utilizing **Android Room** with KSP2 compilation. Full CRUD capability is supported offline.
* **5-Second Real-Time Synchronization**: A background synchronization loop automatically negotiates local manual changes with Supabase tables every 5 seconds.
* **Conflict Resolution**: Custom mapping resolves local-to-remote ID conflicts, cleanly propagates foreign keys, and pre-applies Approved deletion/edit requests to ensure local and remote databases never diverge.

### 🛡️ Enterprise-Grade Authentication & Security
* **Multi-Method Login**: Supports traditional email/password registration along with native **Google Sign-In (OAuth)** deep linking.
* **Google Account Chooser**: Appends custom encoded query parameters to the OAuth flow to force the standard Google Account Chooser screen on every login.
* **Secure Password Recovery Flow**:
  * Users can request recovery emails directly from the Sign In interface.
  * When clicked, deep links redirect the user directly to the app.
  * A **Choose New Password** popup dialog overlays the Sign In screen securely.
  * The user is **never logged in automatically** on link click. This prevents account hijacking.
  * Successful authentication and dashboard entry occur **only after** a new password (minimum 6 characters) has been successfully verified, saved, and updated in both Supabase and Room.

### 👥 Role-Based Dashboards & Change Request Pipeline
* **Dynamic Roles**: The first user to register receives the **Admin** role, and subsequent users are classified as **Members**.
* **Admin Control Center**: Admins can approve or reject critical changes (Savings Edits, Member Removal, Role Updates, Account Suspensions) through a custom dashboard.
* **Clean visual rhythm**: List items feature adaptive "PENDING" status chips styled with precise typography sizes to handle large member names gracefully.
* **Instant Synced Profiles**: Built-in integration with Supabase Storage allows members to upload customized profile avatars with instant public URL propagation.

### 📅 Locale-Independent Weekly Financial Cycles
* Uses explicit, custom day offsets in `Calendar` mathematics to calculate a strict **Friday-to-Thursday weekly savings cycle**. This completely eliminates offset errors on devices set to non-US region locales (where the first day of the week is Monday or Saturday).

### 🔔 Smart System Alerts & Background Notifications
* **Scheduled Reminders**: Integrates system-level alerts matching user-configured days and times using Android's local `AlarmManager` and a `BroadcastReceiver`.
* **Instant Notification Sync**: Emits local system drawer notifications instantly whenever a change request is created.
* **Interactive Navigation**: Clicking on system notifications directs the user straight to the relevant Admin Panel > Change Requests section.
* **Red Dot Indicator**: Features a dynamic red badge on the top application header that is visible to Admin users as long as there are outstanding change requests.

### 🎨 Fluid Material 3 Visuals & Splash Screen
* **Adaptive Splash Screen**: A customized, animated startup sequence utilizing a vector Canvas-drawing logo painter that natively supports custom adaptive "Squircle" brand assets.
* **Visual Polish**: Includes custom-clipping app components, smooth transition sequences, and Material 3 elements built around dynamic color scheme templates.
* **Smooth Lifecycle Management**: Processes lifecycle overrides to prevent Compose warm-relaunch drawing hangs, and enforces complete system process cleanup on back gesture exits.

---

## 🛠️ Tech Stack & Architecture

- **UI Framework**: Jetpack Compose (Material Design 3)
- **Programming Language**: Kotlin (with Coroutines and asynchronous StateFlows)
- **Architecture**: MVVM (Model-View-ViewModel) + Clean Data Access Layer
- **Local Persistence**: SQLite Database via Room ORM
- **Backend-as-a-Service**: Supabase (Auth, PostgreSQL DB, Storage Buckets)
- **JSON Serialization**: Kotlinx Serialization
- **Local Scheduler**: Android AlarmManager / BroadcastReceiver API
- **Build System**: Gradle 9.3.1 (Kotlin DSL) with AGP 8.8.0

---

## ⚙️ Project Setup & Configuration

### 1. Prerequisites
* **Android SDK**: Compile / Target SDK 35, Min SDK 24.
* **JDK**: Version 11 or higher.
* **Gradle**: 9.3.1.

### 2. Environment Variables (`.env`)
Create a `.env` file at the root of your project using the structure from `.env.example`:
```ini
SUPABASE_URL="https://your-supabase-project.supabase.co"
SUPABASE_ANON_KEY="eyJhbGciOiJIUzI1NiIsInR5..."
```

*Note: Android handles these variables securely via BuildConfig during compiling.*

### 3. Deep Linking Registration
The app utilizes custom deep link schemes to catch Supabase Auth redirects. Ensure your application's manifest includes the corresponding intent filters:
```xml
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="swanirvor23" android:host="login-callback" />
</intent-filter>
```

---

## 🗄️ Database Setup (Supabase SQL)

To configure your Supabase backend to match the app's structure:
1. Initialize the `members`, `savings`, `settings`, and `change_requests` tables.
2. Grant authenticating users `SELECT` and `INSERT` capabilities on tables.
3. Configure Row-Level Security (RLS) to allow authenticated members to query other savings records for collective dashboard metrics.
4. Establish an `avatars` bucket in Supabase Storage with public access.

---

## 📱 Version Tracking
The build pipeline parses `/versionHistory.txt` at build time to dynamically update Android's App Info `versionName`. Consult `/versionHistory.txt` to view the comprehensive changelog of all previous releases.
