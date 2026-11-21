package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.ProfilePictureRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProfilePictureRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        runBlocking { clearFixtures() }
    }

    @After
    fun tearDown() {
        runBlocking { clearFixtures() }
    }

    @Test
    fun pendingMarkerIsPersistedAndScopedToTheUser() = runBlocking {
        assertFalse(ProfilePictureRepository.hasPendingUpload(context, USER_A))

        assertTrue(ProfilePictureRepository.markUploadPending(context, USER_A))
        assertTrue(ProfilePictureRepository.hasPendingUpload(context, USER_A))
        assertFalse(ProfilePictureRepository.hasPendingUpload(context, USER_B))

        assertTrue(ProfilePictureRepository.clearUploadPending(context, USER_A))
        assertFalse(ProfilePictureRepository.hasPendingUpload(context, USER_A))
    }

    @Test
    fun localJpegRemainsReadableForRetry() = runBlocking {
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x7F)
        val file = ProfilePictureRepository.localFile(context, USER_A)

        assertTrue(ProfilePictureRepository.writeLocal(file, bytes))
        val readBack = requireNotNull(ProfilePictureRepository.readLocal(file))
        assertArrayEquals(bytes, readBack)

        assertTrue(ProfilePictureRepository.deleteLocal(file))
    }

    private suspend fun clearFixtures() {
        ProfilePictureRepository.clearUploadPending(context, USER_A)
        ProfilePictureRepository.clearUploadPending(context, USER_B)
        ProfilePictureRepository.localFile(context, USER_A).delete()
        ProfilePictureRepository.localFile(context, USER_B).delete()
    }

    private companion object {
        const val USER_A = "upload-test-user-a"
        const val USER_B = "upload-test-user-b"
    }
}
