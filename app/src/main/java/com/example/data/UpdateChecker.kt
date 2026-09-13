package com.example.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reads the app's published GitHub releases and downloads a newer APK.
 *
 * The repository must be public and carry a published release whose tag matches the app version
 * (for example v1.0.24), because the app queries the GitHub API without a token — bundling a
 * personal access token in the APK would expose it to anyone who extracts the file.
 */
object UpdateChecker {

    // Comment: Repository that hosts the released APKs
    private const val REPO_OWNER = "Asadusjaman-Sazal"
    private const val REPO_NAME = "swanirvor23"
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    // Comment: Mime type the system package installer requires for an APK intent
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    // Comment: Bound every call so a stalled update check can never hang the Settings screen.
    // The read timeout is generous because an APK can be a multi-megabyte download on mobile data.
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * A published release resolved from the GitHub Releases API.
     *
     * @param version the tag with any leading "v" removed, ready for version comparison.
     * @param apkUrl the first attached APK asset, or null when the release has no APK yet.
     */
    data class AppRelease(
        val version: String,
        val releaseNotes: String,
        val apkUrl: String?,
        val releasePageUrl: String
    )

    /**
     * Fetches the newest published (non-draft, non-prerelease) release of the app.
     */
    suspend fun fetchLatestRelease(): Result<AppRelease> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                // Comment: GitHub answers 404 both for a missing release and for a private repo, so the
                // message tells the maintainer exactly what to check.
                if (response.code == 404) {
                    return@withContext Result.failure(
                        IllegalStateException("No published release was found. Publish a release tagged with the new version.")
                    )
                }
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Update check failed (HTTP ${response.code}).")
                    )
                }

                val json = JSONObject(response.body?.string().orEmpty())
                val tagName = json.optString("tag_name", "").trim()
                if (tagName.isBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("The latest release has no version tag.")
                    )
                }

                Result.success(
                    AppRelease(
                        version = tagName.removePrefix("v").removePrefix("V"),
                        releaseNotes = json.optString("body", "").trim(),
                        apkUrl = firstApkAssetUrl(json.optJSONArray("assets")),
                        releasePageUrl = json.optString("html_url", "")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("UpdateChecker", "Failed to fetch the latest release", e)
            Result.failure(
                IllegalStateException("Couldn't reach the update server. Check your connection.", e)
            )
        }
    }

    /**
     * Streams the release APK into [targetFile], reporting 0-100 progress (or -1 when the server
     * sends no content length) from the download loop itself — no polling timer is involved.
     */
    suspend fun downloadApk(
        apkUrl: String,
        targetFile: File,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(apkUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Download failed (HTTP ${response.code}).")
                    )
                }
                val body = response.body
                    ?: return@withContext Result.failure(IllegalStateException("The download returned no data."))

                val totalBytes = body.contentLength()
                targetFile.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    targetFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead = 0L
                        while (true) {
                            // Comment: Bail out between chunks so "Cancel" stops the transfer promptly
                            ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            bytesRead += read
                            onProgress(if (totalBytes > 0) ((bytesRead * 100) / totalBytes).toInt() else -1)
                        }
                    }
                }
                Result.success(targetFile)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            targetFile.delete()
            throw e
        } catch (e: Exception) {
            Log.e("UpdateChecker", "Failed to download the update APK", e)
            // Comment: Never leave a truncated APK on disk for the installer to reject
            targetFile.delete()
            Result.failure(
                IllegalStateException("Download failed. Please check your connection and try again.", e)
            )
        }
    }

    // Comment: Pick the first .apk asset so a release that also ships source archives still resolves
    // the installable file.
    private fun firstApkAssetUrl(assets: JSONArray?): String? {
        if (assets == null) return null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name", "")
            val downloadUrl = asset.optString("browser_download_url", "")
            if (name.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotBlank()) {
                return downloadUrl
            }
        }
        return null
    }

    // Comment: Exposed for the installer intent's data type so it lives next to the URL parsing
    fun apkMimeType(): String = APK_MIME_TYPE
}
