package com.example.ui.view

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.util.CropDragMode
import com.example.util.ProfileImageCrop
import com.example.util.ProfileImageProcessor
import com.example.util.mapFrameToSource
import com.example.util.moveCropFrame
import com.example.util.resizeCropFrame
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Crop page: black, no back arrow, Cancel / Rotate Right / Save. The frame is a circle inside a
 * square; the square's corners are the resize handles and its rect maps back to source pixels.
 */
@Composable
fun SelectImageScreen(
    context: Context,
    uri: Uri,
    onCancel: () -> Unit,
    onSave: (ProfileImageCrop) -> Unit
) {
    var quarterTurns by remember { mutableIntStateOf(0) }
    var orientedSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var displayBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<Rect?>(null) }
    var pendingCrop by remember { mutableStateOf<ProfileImageCrop?>(null) }

    LaunchedEffect(uri) {
        orientedSize = ProfileImageProcessor.readOrientedDimensions(context, uri)
        if (orientedSize == null) loadFailed = true
    }

    LaunchedEffect(uri, quarterTurns) {
        displayBitmap = ProfileImageProcessor.decodeDisplayBitmap(context, uri, quarterTurns)
        if (displayBitmap == null) loadFailed = true
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                // Comment: No back arrow; Cancel is the way out. Large white title, no elevation.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Select Image",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(16.dp)
                ) {
                    val canvasW = constraints.maxWidth.toFloat()
                    val canvasH = constraints.maxHeight.toFloat()
                    val size = orientedSize

                    if (size == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (loadFailed) {
                                Text(
                                    text = "Picture could not be read.",
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center
                                )
                            } else {
                                CircularProgressIndicator(color = Color.White)
                            }
                        }
                    } else if (canvasW > 0f && canvasH > 0f) {
                        val sourceW = if (quarterTurns % 2 == 0) size.first else size.second
                        val sourceH = if (quarterTurns % 2 == 0) size.second else size.first
                        if (loadFailed && displayBitmap == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Picture could not be read.",
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else if (sourceW <= 0 || sourceH <= 0) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Picture could not be read.",
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            val imageScale = min(canvasW / sourceW.toFloat(), canvasH / sourceH.toFloat())
                            val displayedW = sourceW * imageScale
                            val displayedH = sourceH * imageScale
                            val imageLeft = (canvasW - displayedW) / 2f
                            val imageTop = (canvasH - displayedH) / 2f
                            val imageRect = Rect(imageLeft, imageTop, imageLeft + displayedW, imageTop + displayedH)

                            val density = LocalDensity.current
                            val marginPx = with(density) { 24.dp.toPx() }
                            val minSide = min(512f * imageScale, min(displayedW, displayedH))
                            val maxSide = (min(displayedW, displayedH) - 2f * marginPx).coerceAtLeast(1f)

                            LaunchedEffect(
                                imageLeft, imageTop, displayedW, displayedH,
                                sourceW, sourceH, quarterTurns
                            ) {
                                // Comment: Rotation and layout change restart the frame at the largest square
                                // that fits with 24 dp breathing room; a small source can never shrink.
                                val side = maxOf(minSide, maxSide).coerceAtMost(min(displayedW, displayedH))
                                val cx = imageLeft + displayedW / 2f
                                val cy = imageTop + displayedH / 2f
                                frame = Rect(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
                            }

                            LaunchedEffect(
                                frame, imageLeft, imageTop, displayedW, sourceW, sourceH, quarterTurns
                            ) {
                                val f = frame
                                pendingCrop = if (f == null || displayedW <= 0f) {
                                    null
                                } else {
                                    // Comment: Convert the on-screen frame back to source pixels of the rotated
                                    // image, then clamp and keep at least one pixel.
                                    mapFrameToSource(
                                        frame = f,
                                        imageOrigin = Offset(imageLeft, imageTop),
                                        displayedWidth = displayedW,
                                        sourceWidth = sourceW,
                                        sourceHeight = sourceH,
                                        quarterTurns = quarterTurns
                                    )
                                }
                            }

                            var dragMode by remember { mutableStateOf(CropDragMode.Move) }
                            var dragStartFrame by remember { mutableStateOf<Rect?>(null) }
                            var dragTotal by remember { mutableStateOf(Offset.Zero) }

                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(imageRect, displayedW, displayedH, sourceW, sourceH, minSide) {
                                        val cornerTouch = with(density) { 22.dp.toPx() }
                                        detectDragGestures(
                                            onDragStart = { position ->
                                                val f = frame
                                                if (f != null) {
                                                    fun near(corner: Offset): Boolean =
                                                        abs(position.x - corner.x) <= cornerTouch &&
                                                            abs(position.y - corner.y) <= cornerTouch

                                                    dragMode = when {
                                                        near(Offset(f.left, f.top)) -> CropDragMode.TopLeft
                                                        near(Offset(f.right, f.top)) -> CropDragMode.TopRight
                                                        near(Offset(f.left, f.bottom)) -> CropDragMode.BottomLeft
                                                        near(Offset(f.right, f.bottom)) -> CropDragMode.BottomRight
                                                        else -> CropDragMode.Move
                                                    }
                                                    dragStartFrame = f
                                                    dragTotal = Offset.Zero
                                                }
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                val f = frame
                                                if (f != null) {
                                                    if (dragMode == CropDragMode.Move) {
                                                        frame = moveCropFrame(f, dragAmount, imageRect)
                                                    } else {
                                                        dragTotal += dragAmount
                                                        val start = dragStartFrame
                                                        if (start != null) {
                                                            frame = resizeCropFrame(
                                                                start = start,
                                                                mode = dragMode,
                                                                total = dragTotal,
                                                                image = imageRect,
                                                                minSide = minSide
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                    }
                            ) {
                                displayBitmap?.let { bitmap ->
                                    drawImage(
                                        image = bitmap.asImageBitmap(),
                                        srcOffset = IntOffset.Zero,
                                        srcSize = IntSize(bitmap.width, bitmap.height),
                                        dstOffset = IntOffset(imageLeft.roundToInt(), imageTop.roundToInt()),
                                        dstSize = IntSize(displayedW.roundToInt(), displayedH.roundToInt()),
                                        filterQuality = FilterQuality.Medium
                                    )
                                }

                                val f = frame
                                if (f != null) {
                                    val radius = f.width / 2f
                                    val circle = Rect(
                                        f.center.x - radius,
                                        f.center.y - radius,
                                        f.center.x + radius,
                                        f.center.y + radius
                                    )

                                    // Comment: Dim everything inside the displayed image but outside the circle.
                                    val imagePath = Path().apply { addRect(imageRect) }
                                    val circlePath = Path().apply { addOval(circle) }
                                    val dimPath = Path()
                                    dimPath.op(imagePath, circlePath, PathOperation.Difference)
                                    drawPath(dimPath, Color.Black.copy(alpha = 0.55f))

                                    drawCircle(
                                        color = Color.White,
                                        radius = radius,
                                        center = f.center,
                                        style = Stroke(width = 2.dp.toPx())
                                    )

                                    // Comment: 14 dp white handles at the square's corners, over 44 dp touch areas.
                                    val dotRadius = 7.dp.toPx()
                                    listOf(
                                        Offset(f.left, f.top),
                                        Offset(f.right, f.top),
                                        Offset(f.left, f.bottom),
                                        Offset(f.right, f.bottom)
                                    ).forEach { drawCircle(Color.White, dotRadius, it) }
                                }
                            }

                            if (displayBitmap == null && !loadFailed) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }
                    }
                }

                // Comment: Bottom row sits inside the system-bar insets, with Cancel left, Rotate Right
                // centred and Save right.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onCancel) {
                        Text("Cancel", color = Color.White)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = { quarterTurns = (quarterTurns + 1) % 4 },
                        enabled = displayBitmap != null
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.RotateRight,
                            contentDescription = "Rotate Right",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { pendingCrop?.let(onSave) },
                        enabled = pendingCrop != null && displayBitmap != null && !loadFailed
                    ) {
                        Text(
                            text = "Save",
                            color = if (pendingCrop != null && displayBitmap != null && !loadFailed) {
                                Color.White
                            } else {
                                Color.White.copy(alpha = 0.4f)
                            }
                        )
                    }
                }
            }
        }
    }
}

