package com.example.util

import android.graphics.Rect as AndroidRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

enum class CropDragMode { Move, TopLeft, TopRight, BottomLeft, BottomRight }

/**
 * Resize the square frame with the opposite corner pinned. The side follows whichever drag axis
 * moved more, stays square, and is clamped inside the displayed image at or above the 512-px
 * output minimum.
 */
fun resizeCropFrame(
    start: Rect,
    mode: CropDragMode,
    total: Offset,
    image: Rect,
    minSide: Float
): Rect {
    val pinnedX: Float
    val pinnedY: Float
    val signX: Float
    val signY: Float
    when (mode) {
        CropDragMode.TopLeft -> {
            pinnedX = start.right
            pinnedY = start.bottom
            signX = -1f
            signY = -1f
        }
        CropDragMode.TopRight -> {
            pinnedX = start.left
            pinnedY = start.bottom
            signX = 1f
            signY = -1f
        }
        CropDragMode.BottomLeft -> {
            pinnedX = start.right
            pinnedY = start.top
            signX = -1f
            signY = 1f
        }
        CropDragMode.BottomRight -> {
            pinnedX = start.left
            pinnedY = start.top
            signX = 1f
            signY = 1f
        }
        CropDragMode.Move -> return start
    }

    val horizontalSize = start.width + signX * total.x
    val verticalSize = start.height + signY * total.y
    val candidate = if (abs(total.x) >= abs(total.y)) horizontalSize else verticalSize

    val maxSize = when (mode) {
        CropDragMode.TopLeft -> min(pinnedX - image.left, pinnedY - image.top)
        CropDragMode.TopRight -> min(image.right - pinnedX, pinnedY - image.top)
        CropDragMode.BottomLeft -> min(pinnedX - image.left, image.bottom - pinnedY)
        CropDragMode.BottomRight -> min(image.right - pinnedX, image.bottom - pinnedY)
        CropDragMode.Move -> start.width
    }
    val upper = maxSize.coerceAtLeast(1f)
    val lower = minSide.coerceAtMost(upper)
    val side = candidate.coerceIn(lower, upper)

    return when (mode) {
        CropDragMode.TopLeft -> Rect(pinnedX - side, pinnedY - side, pinnedX, pinnedY)
        CropDragMode.TopRight -> Rect(pinnedX, pinnedY - side, pinnedX + side, pinnedY)
        CropDragMode.BottomLeft -> Rect(pinnedX - side, pinnedY, pinnedX, pinnedY + side)
        CropDragMode.BottomRight -> Rect(pinnedX, pinnedY, pinnedX + side, pinnedY + side)
        CropDragMode.Move -> start
    }
}

/** Move the frame without resizing, keeping it wholly inside the displayed image. */
fun moveCropFrame(frame: Rect, delta: Offset, image: Rect): Rect {
    val maxLeft = (image.right - frame.width).coerceAtLeast(image.left)
    val maxTop = (image.bottom - frame.height).coerceAtLeast(image.top)
    val left = (frame.left + delta.x).coerceIn(image.left, maxLeft)
    val top = (frame.top + delta.y).coerceIn(image.top, maxTop)
    return Rect(left, top, left + frame.width, top + frame.height)
}

/**
 * Convert the on-screen frame back into source pixels of the rotated image. The result is
 * clamped to the image bounds and is at least one pixel wide and high.
 */
fun mapFrameToSource(
    frame: Rect,
    imageOrigin: Offset,
    displayedWidth: Float,
    sourceWidth: Int,
    sourceHeight: Int,
    quarterTurns: Int
): ProfileImageCrop {
    require(displayedWidth > 0f) { "displayedWidth must be positive" }
    require(sourceWidth > 0 && sourceHeight > 0) { "source dimensions must be positive" }

    val scale = displayedWidth / sourceWidth.toFloat()
    val left = ((frame.left - imageOrigin.x) / scale).roundToInt().coerceIn(0, sourceWidth - 1)
    val top = ((frame.top - imageOrigin.y) / scale).roundToInt().coerceIn(0, sourceHeight - 1)
    val right = ((frame.right - imageOrigin.x) / scale).roundToInt().coerceIn(left + 1, sourceWidth)
    val bottom = ((frame.bottom - imageOrigin.y) / scale).roundToInt().coerceIn(top + 1, sourceHeight)

    return ProfileImageCrop(
        rect = AndroidRect(left, top, right, bottom),
        quarterTurns = quarterTurns
    )
}
