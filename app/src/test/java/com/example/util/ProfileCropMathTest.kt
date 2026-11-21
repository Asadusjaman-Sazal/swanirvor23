package com.example.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProfileCropMathTest {

    private val image = Rect(0f, 0f, 1000f, 1000f)

    @Test
    fun resizingTopLeftPinsBottomRightAndStaysSquare() {
        val start = Rect(100f, 100f, 300f, 300f)
        val resized = resizeCropFrame(
            start = start,
            mode = CropDragMode.TopLeft,
            total = Offset(50f, 0f),
            image = image,
            minSide = 50f
        )
        assertEquals(300f, resized.right, 0.001f)
        assertEquals(300f, resized.bottom, 0.001f)
        assertEquals(150f, resized.width, 0.001f)
        assertEquals(resized.width, resized.height, 0.001f)
    }

    @Test
    fun resizingTopRightPinsBottomLeftAndUsesTheLargerAxis() {
        val start = Rect(100f, 100f, 300f, 300f)
        val resized = resizeCropFrame(
            start = start,
            mode = CropDragMode.TopRight,
            total = Offset(10f, 50f),
            image = image,
            minSide = 50f
        )
        assertEquals(100f, resized.left, 0.001f)
        assertEquals(300f, resized.bottom, 0.001f)
        assertEquals(150f, resized.width, 0.001f)
    }

    @Test
    fun resizeClampsInsideTheDisplayedImage() {
        val start = Rect(100f, 100f, 300f, 300f)
        val resized = resizeCropFrame(
            start = start,
            mode = CropDragMode.TopLeft,
            total = Offset(5000f, 5000f),
            image = Rect(0f, 0f, 400f, 400f),
            minSide = 10f
        )
        assertTrue(resized.left >= 0f)
        assertTrue(resized.top >= 0f)
        assertTrue(resized.right <= 400f)
        assertTrue(resized.bottom <= 400f)
    }

    @Test
    fun minimumWidthIsThe512OutputWidthAtDisplayScale() {
        // 2000 px displayed for a 4000 px source: 512 source px is 256 displayed px.
        val start = Rect(400f, 400f, 900f, 900f)
        val resized = resizeCropFrame(
            start = start,
            mode = CropDragMode.TopLeft,
            total = Offset(2000f, 2000f),
            image = Rect(0f, 0f, 2000f, 2000f),
            minSide = 256f
        )
        assertEquals(256f, resized.width, 0.001f)
    }

    @Test
    fun aSmallSourceCannotShrinkAtAll() {
        val image = Rect(0f, 0f, 400f, 400f)
        val start = Rect(0f, 0f, 400f, 400f)
        val resized = resizeCropFrame(
            start = start,
            mode = CropDragMode.TopRight,
            total = Offset(-5000f, 0f),
            image = image,
            minSide = 400f
        )
        assertEquals(400f, resized.width, 0.001f)
        assertEquals(0f, resized.top, 0.001f)
        assertEquals(400f, resized.bottom, 0.001f)
    }

    @Test
    fun moveKeepsTheFrameInsideTheImage() {
        val frame = Rect(100f, 100f, 300f, 300f)
        val moved = moveCropFrame(frame, Offset(5000f, -5000f), image)
        assertEquals(1000f, moved.right, 0.001f)
        assertEquals(0f, moved.top, 0.001f)
        assertEquals(200f, moved.width, 0.001f)
        assertEquals(200f, moved.height, 0.001f)
    }

    @Test
    fun fullFrameMapsToTheFullSource() {
        val crop = mapFrameToSource(
            frame = Rect(100f, 100f, 600f, 600f),
            imageOrigin = Offset(100f, 100f),
            displayedWidth = 500f,
            sourceWidth = 1000,
            sourceHeight = 1000,
            quarterTurns = 0
        )
        assertEquals(0, crop.rect.left)
        assertEquals(0, crop.rect.top)
        assertEquals(1000, crop.rect.right)
        assertEquals(1000, crop.rect.bottom)
    }

    @Test
    fun rightHalfFrameMapsToTheRightHalfOfTheRotatedSource() {
        val crop = mapFrameToSource(
            frame = Rect(350f, 100f, 600f, 350f),
            imageOrigin = Offset(100f, 100f),
            displayedWidth = 500f,
            sourceWidth = 1000,
            sourceHeight = 500,
            quarterTurns = 1
        )
        assertEquals(500, crop.rect.left)
        assertEquals(0, crop.rect.top)
        assertEquals(1000, crop.rect.right)
        assertEquals(500, crop.rect.bottom)
        assertEquals(1, crop.quarterTurns)
    }

    @Test
    fun mappingClampsToTheSourceAndKeepsOnePixel() {
        val crop = mapFrameToSource(
            frame = Rect(-500f, -500f, -400f, -400f),
            imageOrigin = Offset(0f, 0f),
            displayedWidth = 500f,
            sourceWidth = 1000,
            sourceHeight = 1000,
            quarterTurns = 0
        )
        assertEquals(0, crop.rect.left)
        assertEquals(0, crop.rect.top)
        assertTrue(crop.rect.width() >= 1)
        assertTrue(crop.rect.height() >= 1)
    }
}
