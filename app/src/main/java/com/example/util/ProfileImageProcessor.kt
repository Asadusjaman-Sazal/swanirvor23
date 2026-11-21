package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The crop page's result: a rectangle in the rotated source image, plus the quarter turns it displayed. */
data class ProfileImageCrop(
    val rect: Rect,
    val quarterTurns: Int
)

sealed class ProfileImageResult {
    data class Success(val bytes: ByteArray) : ProfileImageResult()
    object Unreadable : ProfileImageResult()
}

/**
 * Decodes a picked image, applies EXIF orientation and the user's quarter turns, crops, scales to
 * 512 x 512, flattens transparency onto white and walks the JPEG quality ladder until the bytes fit.
 */
object ProfileImageProcessor {
    const val OUTPUT_SIZE = 512
    const val MAX_BYTES = 50 * 1024
    private const val DISPLAY_MAX = 1600
    private const val WORK_MAX = 2048

    private data class CropStep(val scale: Float, val quality: Int)
    private data class SizeStep(val size: Int, val quality: Int)

    // Comment: Crop ladder from the same 512 bitmap. A 12 MP source therefore never allocates at full
    // size, and every accepted result is a 512-square JPEG with no alpha and no copied metadata.
    private val CROP_LADDER = listOf(
        CropStep(1.00f, 85),
        CropStep(1.00f, 70),
        CropStep(0.875f, 60),
        CropStep(0.75f, 50),
        CropStep(0.625f, 40),
        CropStep(0.50f, 35)
    )

    // Comment: Whole-image ladder used by the centre-crop path and by the one-time re-compress of an
    // older stored picture that is still larger than 50 KB.
    private val WHOLE_IMAGE_LADDER = listOf(
        SizeStep(512, 85),
        SizeStep(512, 70),
        SizeStep(448, 60),
        SizeStep(384, 50),
        SizeStep(320, 40),
        SizeStep(256, 35)
    )

    /** Post-EXIF pixel dimensions, used by the crop screen to map its frame back to source pixels. */
    suspend fun readOrientedDimensions(context: Context, uri: Uri): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            val orientation = readOrientation(context, uri)
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                orientation == ExifInterface.ORIENTATION_TRANSVERSE
            ) {
                bounds.outHeight to bounds.outWidth
            } else {
                bounds.outWidth to bounds.outHeight
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Downsampled bitmap for the crop page. The final crop is always re-decoded from the source URI. */
    suspend fun decodeDisplayBitmap(context: Context, uri: Uri, quarterTurns: Int): Bitmap? = withContext(Dispatchers.Default) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, DISPLAY_MAX)
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return@withContext null

            val oriented = applyOrientation(decoded, readOrientation(context, uri))
            if (oriented !== decoded) decoded.recycle()
            val rotated = applyQuarterTurns(oriented, quarterTurns)
            if (rotated !== oriented) oriented.recycle()
            rotated
        } catch (e: Exception) {
            null
        }
    }

    /** Crops the frame's source rectangle after EXIF + quarter turns, then runs the crop ladder. */
    suspend fun processCropped(context: Context, uri: Uri, crop: ProfileImageCrop): ProfileImageResult =
        withContext(Dispatchers.Default) {
            var decoded: Bitmap? = null
            var oriented: Bitmap? = null
            var rotated: Bitmap? = null
            var cropped: Bitmap? = null
            var scaled: Bitmap? = null
            var flattened: Bitmap? = null
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext ProfileImageResult.Unreadable

                val options = BitmapFactory.Options().apply {
                    inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, WORK_MAX)
                }
                decoded = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                } ?: return@withContext ProfileImageResult.Unreadable

                oriented = applyOrientation(decoded, readOrientation(context, uri))
                rotated = applyQuarterTurns(oriented, crop.quarterTurns)

                val safeRect = Rect(
                    crop.rect.left.coerceIn(0, max(0, rotated.width - 1)),
                    crop.rect.top.coerceIn(0, max(0, rotated.height - 1)),
                    crop.rect.right.coerceIn(1, rotated.width),
                    crop.rect.bottom.coerceIn(1, rotated.height)
                )
                if (safeRect.width() < 1 || safeRect.height() < 1) return@withContext ProfileImageResult.Unreadable

                cropped = Bitmap.createBitmap(rotated, safeRect.left, safeRect.top, safeRect.width(), safeRect.height())
                scaled = Bitmap.createScaledBitmap(cropped, OUTPUT_SIZE, OUTPUT_SIZE, true)
                flattened = flattenOnWhite(scaled, OUTPUT_SIZE, OUTPUT_SIZE)

                encodeLadder(flattened)?.let { ProfileImageResult.Success(it) } ?: ProfileImageResult.Unreadable
            } catch (e: Exception) {
                ProfileImageResult.Unreadable
            } finally {
                listOf(decoded, oriented, rotated, cropped, scaled, flattened).forEach { bitmap ->
                    if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
                }
            }
        }

    /** Decode -> EXIF -> centre-crop 512 -> white -> whole-image ladder. */
    suspend fun processWholeImage(context: Context, uri: Uri): ProfileImageResult = withContext(Dispatchers.Default) {
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        var cropped: Bitmap? = null
        var scaled: Bitmap? = null
        var flattened: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext ProfileImageResult.Unreadable

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, WORK_MAX)
            }
            decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return@withContext ProfileImageResult.Unreadable

            oriented = applyOrientation(decoded, readOrientation(context, uri))
            val side = min(oriented.width, oriented.height)
            if (side < 1) return@withContext ProfileImageResult.Unreadable
            cropped = Bitmap.createBitmap(oriented, (oriented.width - side) / 2, (oriented.height - side) / 2, side, side)
            scaled = Bitmap.createScaledBitmap(cropped, OUTPUT_SIZE, OUTPUT_SIZE, true)
            flattened = flattenOnWhite(scaled, OUTPUT_SIZE, OUTPUT_SIZE)

            encodeWholeLadder(flattened)?.let { ProfileImageResult.Success(it) } ?: ProfileImageResult.Unreadable
        } catch (e: Exception) {
            ProfileImageResult.Unreadable
        } finally {
            listOf(decoded, oriented, cropped, scaled, flattened).forEach { bitmap ->
                if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun encodeLadder(source: Bitmap): ByteArray? {
        for (step in CROP_LADDER) {
            val bitmap = if (step.scale >= 1f) {
                source
            } else {
                val side = (OUTPUT_SIZE * step.scale).roundToInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(source, side, side, true)
            }
            try {
                val bytes = compress(bitmap, step.quality)
                if (bytes != null && bytes.size <= MAX_BYTES) return bytes
            } finally {
                if (bitmap !== source && !bitmap.isRecycled) bitmap.recycle()
            }
        }
        return null
    }

    private fun encodeWholeLadder(source: Bitmap): ByteArray? {
        for (step in WHOLE_IMAGE_LADDER) {
            val bitmap = if (step.size == OUTPUT_SIZE) {
                source
            } else {
                Bitmap.createScaledBitmap(source, step.size, step.size, true)
            }
            try {
                val bytes = compress(bitmap, step.quality)
                if (bytes != null && bytes.size <= MAX_BYTES) return bytes
            } finally {
                if (bitmap !== source && !bitmap.isRecycled) bitmap.recycle()
            }
        }
        return null
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray? = try {
        ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            output.toByteArray()
        }
    } catch (e: Exception) {
        null
    }

    // Comment: JPEG has no alpha. Drawing onto an opaque white RGB-style canvas keeps transparent
    // PNG/GIF sources from turning black around the edges.
    private fun flattenOnWhite(source: Bitmap, width: Int, height: Int): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return result
    }

    private fun readOrientation(context: Context, uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        val longest = max(width, height)
        while (longest / sample > maxDimension) {
            sample *= 2
        }
        return sample
    }

    private fun applyOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun applyQuarterTurns(source: Bitmap, quarterTurns: Int): Bitmap {
        val turns = ((quarterTurns % 4) + 4) % 4
        if (turns == 0) return source
        val matrix = Matrix().apply { postRotate(90f * turns) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}
