package com.example.ui.view

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Comment: First letter of the first two words, or "?" for an empty name.
fun profileInitials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2)
    if (words.isEmpty()) return "?"
    return words.mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
}

@Composable
fun InitialsProfileCircle(
    name: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFFBDBDBD), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = profileInitials(name),
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.White
        )
    }
}

// Comment: Decode the local 512 JPEG for display. Coil's path cache would keep showing the old bytes
// after a stable-path overwrite, so the revision acts as the reload key.
@Composable
fun rememberLocalProfileBitmap(path: String?, revision: Int): Bitmap? {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path, revision) {
        bitmap = if (path == null) {
            null
        } else {
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
        }
    }
    return bitmap
}

/**
 * Renders the user's own picture, preferring the local file so it works offline and immediately
 * after a save, then the public URL, then the initials placeholder.
 */
@Composable
fun ProfilePictureContent(
    name: String,
    localPath: String?,
    remoteUrl: String?,
    revision: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val localBitmap = rememberLocalProfileBitmap(localPath, revision)
    when {
        localBitmap != null -> Image(
            bitmap = localBitmap.asImageBitmap(),
            contentDescription = "Profile picture",
            contentScale = contentScale,
            modifier = modifier
        )

        !remoteUrl.isNullOrBlank() -> SubcomposeAsyncImage(
            model = remoteUrl,
            contentDescription = "Profile picture",
            contentScale = contentScale,
            modifier = modifier
        ) {
            val state = painter.state
            if (state is AsyncImagePainter.State.Error || state is AsyncImagePainter.State.Loading) {
                InitialsProfileCircle(name = name, modifier = Modifier.fillMaxSize())
            } else {
                SubcomposeAsyncImageContent()
            }
        }

        else -> InitialsProfileCircle(name = name, modifier = modifier)
    }
}
