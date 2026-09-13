package com.example.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reads the app's published GitHub releases and downloads a newer APK.
 *
 * The repository must be public and carry a published release whose tag matches the app version
 * (for example v1.0.24). The app cannot authenticate to GitHub — a token bundled in the APK would be
 * readable by anyone who extracts the file — and the anonymous REST API only allows 60 requests per
 * hour per source IP, which carrier NAT shares across many devices, so a check used to fail with
 * "Update check failed (HTTP 403)". This reads the repository's public release pages instead, which
 * carry no such quota: the "/releases/latest" redirect resolves the newest published tag and the
 * "/releases.atom" feed carries that release's notes.
 */
object UpdateChecker {

    // Comment: Repository that hosts the released APKs
    private const val REPO_OWNER = "Asadusjaman-Sazal"
    private const val REPO_NAME = "swanirvor23"

    // Comment: Public release pages instead of api.github.com — see the class comment for why
    private const val REPO_WEB_URL = "https://github.com/$REPO_OWNER/$REPO_NAME"
    private const val LATEST_RELEASE_URL = "$REPO_WEB_URL/releases/latest"
    private const val RELEASES_ATOM_URL = "$REPO_WEB_URL/releases.atom"

    // Comment: GitHub asks anonymous clients to identify themselves, and naming the app keeps this
    // traffic distinguishable from a browser's in their logs
    private const val USER_AGENT = "Swanirvor23-Android"

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
     * A published release resolved from the repository's public release pages.
     *
     * @param version the tag with any leading "v" removed, ready for version comparison.
     * @param apkUrl the APK download URL derived from the release tag; a release published without
     * that asset fails the download with HTTP 404 rather than resolving to null here.
     */
    data class AppRelease(
        val version: String,
        val releaseNotes: String,
        val apkUrl: String?,
        val releasePageUrl: String
    )

    /** The tag and page URL resolved from the "/releases/latest" redirect, before the notes lookup. */
    private data class LatestRelease(val tag: String, val pageUrl: String)

    /**
     * Fetches the newest published (non-draft, non-prerelease) release of the app.
     */
    suspend fun fetchLatestRelease(): Result<AppRelease> = withContext(Dispatchers.IO) {
        val resolved = try {
            resolveLatestRelease()
        } catch (e: Exception) {
            Log.e("UpdateChecker", "Failed to fetch the latest release", e)
            return@withContext Result.failure(
                IllegalStateException("Couldn't reach the update server. Check your connection.", e)
            )
        }

        val latest = resolved.getOrNull()
        if (latest == null) {
            val error = resolved.exceptionOrNull() ?: noPublishedRelease()
            Log.e("UpdateChecker", "Failed to fetch the latest release", error)
            return@withContext Result.failure(error)
        }

        // Comment: The notes come from a second, best-effort request: a feed hiccup must not fail a
        // check that has already resolved the version the user needs to compare against.
        val notes = try {
            fetchReleaseNotes(latest.tag)
        } catch (e: Exception) {
            Log.w("UpdateChecker", "Couldn't read the release notes", e)
            ""
        }

        Result.success(
            AppRelease(
                // Comment: The tag is kept verbatim for the download URL and the feed lookup, while the
                // version drops its leading "v" because that is what version comparison expects.
                version = latest.tag.removePrefix("v").removePrefix("V"),
                releaseNotes = notes,
                apkUrl = apkDownloadUrl(latest.tag),
                releasePageUrl = latest.pageUrl
            )
        )
    }

    // Comment: OkHttp follows the "/releases/latest" redirect itself, so the URL its response ends on
    // names the newest published tag. This is a HEAD request on purpose: the tag page is ~200 KB of
    // HTML and the final URL is all that is needed, so nothing large is ever downloaded.
    private fun resolveLatestRelease(): Result<LatestRelease> {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .head()
            .header("Accept", "text/html")
            .header("User-Agent", USER_AGENT)
            .build()

        return client.newCall(request).execute().use { response ->
            when {
                // Comment: GitHub answers 404 both for a missing release and for a private repo, so the
                // message tells the maintainer exactly what to check.
                response.code == 404 -> Result.failure(noPublishedRelease())
                !response.isSuccessful -> Result.failure(
                    IllegalStateException("Update check failed (HTTP ${response.code}).")
                )
                else -> releaseFromRedirectUrl(response.request.url.toString())
            }
        }
    }

    /**
     * Turns the URL the redirect landed on into a release. A repository without a published release
     * lands on the plain releases index rather than a tag page.
     */
    private fun releaseFromRedirectUrl(pageUrl: String): Result<LatestRelease> {
        val tag = GitHubReleaseParser.tagFromRedirectUrl(pageUrl)
            ?: return Result.failure(noPublishedRelease())
        return Result.success(LatestRelease(tag, pageUrl))
    }

    // Comment: The APK download URL is derived from the tag because the asset name is fixed by the
    // release workflow (it attaches "swanirvor23-<tag>.apk" — see .github/workflows/release.yml), so
    // the rate-limited API is not needed just to list assets. If that asset name ever changes, update
    // this method with it.
    private fun apkDownloadUrl(tag: String): String =
        "$REPO_WEB_URL/releases/download/$tag/swanirvor23-$tag.apk"

    // Comment: Reads the notes of [tag] from the public Atom feed (~2 KB) that pairs with the release
    // page. Feed lookups carry no API quota, and a missing entry simply means the release has no notes.
    private fun fetchReleaseNotes(tag: String): String {
        val request = Request.Builder()
            .url(RELEASES_ATOM_URL)
            .header("Accept", "application/atom+xml")
            .header("User-Agent", USER_AGENT)
            .build()

        val feed = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return ""
            response.body?.string().orEmpty()
        }
        return GitHubReleaseParser.notesFromAtomFeed(feed, tag)
    }

    private fun noPublishedRelease(): IllegalStateException =
        IllegalStateException("No published release was found. Publish a release tagged with the new version.")

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
                // Comment: The download URL is derived from the tag, so a 404 here means the release has
                // no APK under that name — point the user at the release page instead of a bare code.
                if (response.code == 404) {
                    return@withContext Result.failure(
                        IllegalStateException("This release has no APK attached. Use \"What's new\" to open the release page.")
                    )
                }
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

    // Comment: Exposed for the installer intent's data type so it lives next to the URL parsing
    fun apkMimeType(): String = APK_MIME_TYPE
}
