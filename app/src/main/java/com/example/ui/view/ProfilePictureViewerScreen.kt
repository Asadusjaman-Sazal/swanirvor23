package com.example.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing

/**
 * Full-screen viewer for the user's own profile picture. The picture is the only tap target on the
 * profile screen; this page carries the edit/delete actions and opens the source sheet.
 */
@Composable
fun ProfilePictureViewerScreen(
    name: String,
    localPath: String?,
    remoteUrl: String?,
    revision: Int,
    hasPicture: Boolean,
    isProcessing: Boolean,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    sourceSheet: @Composable () -> Unit = {}
) {
    Dialog(
        onDismissRequest = onClose,
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
                // Comment: Back arrow on the left; title and actions on the right, with the
                // actions 6 dp apart and 12 dp from the edge.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = "Profile Picture",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    if (hasPicture) {
                        HeaderActionCircle(
                            color = MaterialTheme.colorScheme.error,
                            icon = Icons.Default.Delete,
                            label = "Delete",
                            enabled = !isProcessing,
                            onClick = onDelete
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    HeaderActionCircle(
                        color = MaterialTheme.colorScheme.primary,
                        icon = Icons.Default.Edit,
                        label = "Edit",
                        enabled = !isProcessing,
                        onClick = onEdit
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (hasPicture) {
                        ProfilePictureContent(
                            name = name,
                            localPath = localPath,
                            remoteUrl = remoteUrl,
                            revision = revision,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        InitialsProfileCircle(
                            name = name,
                            modifier = Modifier.size(220.dp)
                        )
                    }

                    if (isProcessing) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.35f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }
                }
            }

            sourceSheet()
        }
    }
}

@Composable
private fun HeaderActionCircle(
    color: Color,
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    // Comment: 44 dp touch target around the 32 dp painted circle (20 dp icon + 6 dp padding).
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(if (enabled) color else color.copy(alpha = 0.4f), CircleShape)
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
