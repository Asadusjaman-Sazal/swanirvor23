package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.SupabaseClient
import com.example.util.ProfileImageProcessor
import com.example.util.ProfileImageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the private local copy of the signed-in user's profile picture and the public avatars
 * bucket object named {user_id}.jpg.
 */
object ProfilePictureRepository {
    const val MAX_BYTES = 50 * 1024
    const val UNREADABLE_MESSAGE = "Picture could not be read."

    // Comment: Key the local file by the Supabase user UUID, so a second account signing in on the
    // same phone never sees the previous user's picture. "local" is only a fallback for an
    // unauthenticated session, which the UI never shows.
    fun localFile(context: Context, userId: String?): File {
        val safeId = userId?.takeIf { it.isNotBlank() } ?: "local"
        return File(context.filesDir, "profile_picture_$safeId.jpg")
    }

    fun existingLocalFile(context: Context, userId: String?): File? =
        localFile(context, userId).takeIf { it.exists() && it.length() > 0 }

    fun isOverLimit(file: File): Boolean = file.length() > MAX_BYTES

    suspend fun writeLocal(file: File, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteLocal(file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            !file.exists() || file.delete()
        } catch (e: Exception) {
            false
        }
    }

    suspend fun upload(userId: String, bytes: ByteArray): String? =
        SupabaseClient.uploadProfilePicture(userId, bytes)

    suspend fun deleteRemote(userId: String): Boolean =
        SupabaseClient.deleteProfilePicture(userId)

    /** Re-compress an existing local file that predates the 50 KB rule, once, on its next save. */
    suspend fun recompressWholeImage(context: Context, userId: String?): ByteArray? {
        val file = existingLocalFile(context, userId) ?: return null
        if (!isOverLimit(file)) return null
        return when (val result = ProfileImageProcessor.processWholeImage(context, Uri.fromFile(file))) {
            is ProfileImageResult.Success -> result.bytes
            ProfileImageResult.Unreadable -> null
        }
    }
}
