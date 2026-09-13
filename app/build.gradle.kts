plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.secrets)
}

// Comment: Dynamically parse the "Current Version" from versionHistory.txt at build time
fun getParsedVersionName(): String {
    val versionFile = rootProject.file("versionHistory.txt")
    if (versionFile.exists()) {
        var version = "1.0.0"
        versionFile.forEachLine { line ->
            if (line.trim().startsWith("Current Version:")) {
                version = line.substringAfter("Current Version:").trim()
            }
        }
        return version
    }
    return "1.0.0"
}

// Comment: Derive a monotonic versionCode from the same "Current Version" line so version bumps
// in versionHistory.txt never require a separate manual versionCode edit.
fun getParsedVersionCode(): Int {
    val versionFile = rootProject.file("versionHistory.txt")
    if (versionFile.exists()) {
        var version = "1.0.0"
        versionFile.forEachLine { line ->
            if (line.trim().startsWith("Current Version:")) {
                version = line.substringAfter("Current Version:").trim()
            }
        }
        val parts = version.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size >= 3) {
            return parts[0] * 10000 + parts[1] * 100 + parts[2]
        }
    }
    return 1
}

// Comment: Read a signing secret from an environment variable first, then from the .env file
// (the project's convention for local secrets, also consumed by the Secrets Gradle Plugin below).
fun getSecret(name: String): String {
    val fromEnv = System.getenv(name)?.takeIf { it.isNotBlank() }
    if (fromEnv != null) {
        return fromEnv
    }
    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
        var value = ""
        envFile.forEachLine { line ->
            val trimmed = line.trim()
            if (value.isEmpty() && trimmed.startsWith("$name=")) {
                value = trimmed.substringAfter('=').trim().removeSurrounding("\"")
            }
        }
        return value
    }
    return ""
}

android {
    namespace = "com.example"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.legumsoft.swanirvor23"
        minSdk = 24
        targetSdk = 35
        versionCode = getParsedVersionCode()
        versionName = getParsedVersionName()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePath = getSecret("KEYSTORE_PATH").ifBlank { "${rootDir}/swanirvor-23-release-key.jks" }
            storeFile = file(keystorePath)
            // Comment: Read signing secrets from an env var or the .env file (the project's secrets
            // convention); fall back to empty strings so a missing secret fails the signing step
            // cleanly instead of producing a null store password.
            storePassword = getSecret("STORE_PASSWORD")
            keyAlias = getSecret("KEY_ALIAS").ifBlank { "release" }
            keyPassword = getSecret("KEY_PASSWORD")
        }
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isCrunchPngs = false
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("debugConfig")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
    propertiesFileName = ".env"
    defaultPropertiesFileName = ".env.example"
    // Comment: Keep signing-key secrets out of the generated BuildConfig fields so keystore
    // passwords are never embedded in the distributed APK.
    ignoreList.add("KEYSTORE_PATH")
    ignoreList.add("STORE_PASSWORD")
    ignoreList.add("KEY_PASSWORD")
    ignoreList.add("KEY_ALIAS")
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    // implementation(libs.accompanist.permissions)
    implementation(libs.androidx.activity.compose)
    // implementation(libs.androidx.camera.camera2)
    // implementation(libs.androidx.camera.core)
    // implementation(libs.androidx.camera.lifecycle)
    // implementation(libs.androidx.camera.view)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)
    // implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    // Comment: WorkManager owns the daily background app-update check (CoroutineWorker lives in the
    // plain work-runtime artifact since 2.9, so the empty work-runtime-ktx artifact is not needed).
    implementation(libs.androidx.work.runtime)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    "ksp"(libs.androidx.room.compiler)
}

// Comment: Configure the Kotlin compiler options dynamically for all compilation tasks
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}
