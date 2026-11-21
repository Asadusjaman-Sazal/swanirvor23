package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ProfileImageProcessorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun rightHalfCropProducesBlue512JpegUnder50KbWithoutAlphaOrExif() = runBlocking {
        val source = Bitmap.createBitmap(1000, 500, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        canvas.drawColor(Color.RED)
        canvas.drawRect(500f, 0f, 1000f, 500f, Paint().apply { color = Color.BLUE })
        val file = File(context.cacheDir, "red-left-blue-right.jpg")
        file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 95, it) }

        val result = ProfileImageProcessor.processCropped(
            context = context,
            uri = Uri.fromFile(file),
            crop = ProfileImageCrop(Rect(500, 0, 1000, 500), quarterTurns = 0)
        )

        assertTrue(result is ProfileImageResult.Success)
        val bytes = (result as ProfileImageResult.Success).bytes
        assertTrue("output must be <= 50 KB but was ${bytes.size}", bytes.size <= 50 * 1024)

        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(512, decoded.width)
        assertEquals(512, decoded.height)

        val center = decoded.getPixel(256, 256)
        assertTrue("expected blue, got r=${Color.red(center)} b=${Color.blue(center)}", Color.blue(center) > Color.red(center))
        assertEquals(255, Color.alpha(center))

        val exif = ExifInterface(ByteArrayInputStream(bytes))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
    }

    @Test
    fun leftHalfCropProducesRedPixels() = runBlocking {
        val source = Bitmap.createBitmap(1000, 500, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        canvas.drawColor(Color.RED)
        canvas.drawRect(500f, 0f, 1000f, 500f, Paint().apply { color = Color.BLUE })
        val file = File(context.cacheDir, "red-left-blue-right-2.jpg")
        file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 95, it) }

        val result = ProfileImageProcessor.processCropped(
            context = context,
            uri = Uri.fromFile(file),
            crop = ProfileImageCrop(Rect(0, 0, 500, 500), quarterTurns = 0)
        )

        assertTrue(result is ProfileImageResult.Success)
        val bytes = (result as ProfileImageResult.Success).bytes
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val center = decoded.getPixel(256, 256)
        assertTrue(Color.red(center) > Color.blue(center))
    }

    @Test
    fun corruptSourceIsUnreadableInsteadOfCrashing() = runBlocking {
        val file = File(context.cacheDir, "not-an-image.jpg")
        file.writeText("this is not an image")

        val result = ProfileImageProcessor.processCropped(
            context = context,
            uri = Uri.fromFile(file),
            crop = ProfileImageCrop(Rect(0, 0, 10, 10), quarterTurns = 0)
        )

        assertEquals(ProfileImageResult.Unreadable, result)
    }
}
