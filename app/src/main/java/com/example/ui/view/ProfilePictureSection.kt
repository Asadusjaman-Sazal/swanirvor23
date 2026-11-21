package com.example.ui.view

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.repository.ProfilePictureRepository
import com.example.ui.viewmodel.SavingsViewModel

/**
 * The profile screen's picture is the button: there is no overlay edit/delete control on it.
 * This composable owns the viewer -> source sheet -> picker -> crop flow.
 */
@Composable
fun ProfilePictureSection(
    viewModel: SavingsViewModel,
    name: String,
    profileImageUrl: String?,
    revision: Int
) {
    val context = LocalContext.current
    val userId = com.example.data.SupabaseClient.getCurrentUserUuid()

    // Comment: The local file is the source of truth for the signed-in user; the public URL is the
    // shared copy other members see. Recompute on every save/delete so a stable path still refreshes.
    val localPath = remember(profileImageUrl, revision, userId) {
        ProfilePictureRepository.existingLocalFile(context, userId)?.absolutePath
    }
    val remoteUrl = profileImageUrl?.takeIf { it.startsWith("http") }
    val hasPicture = localPath != null || remoteUrl != null

    var showViewer by remember { mutableStateOf(false) }
    var showSourceSheet by remember { mutableStateOf(false) }
    var cropUri by remember { mutableStateOf<Uri?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    // Comment: The system pickers return null when dismissed; null leaves the current picture untouched.
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) cropUri = uri
    }
    val filesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) cropUri = uri
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(220.dp)
                .clip(CircleShape)
                .clickable { showViewer = true }
                .testTag("profile_picture_button")
        ) {
            ProfilePictureContent(
                name = name,
                localPath = localPath,
                remoteUrl = remoteUrl,
                revision = revision,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (showViewer) {
        ProfilePictureViewerScreen(
            name = name,
            localPath = localPath,
            remoteUrl = remoteUrl,
            revision = revision,
            hasPicture = hasPicture,
            isProcessing = isProcessing,
            onClose = { showViewer = false },
            onEdit = { showSourceSheet = true },
            onDelete = {
                // Comment: Show the busy overlay while a slow background upload is allowed to finish
                // before the object is removed, then close the viewer.
                isProcessing = true
                viewModel.deleteProfilePicture { success, message ->
                    isProcessing = false
                    showViewer = false
                    if (!success) {
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
            },
            sourceSheet = {
                if (showSourceSheet) {
                    // Comment: Back dismisses the sheet first; only a second back closes the viewer.
                    BackHandler { showSourceSheet = false }
                    ProfilePictureSourceSheet(
                        onDismiss = { showSourceSheet = false },
                        onGallery = {
                            showSourceSheet = false
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onFiles = {
                            showSourceSheet = false
                            filesLauncher.launch(arrayOf("image/*"))
                        }
                    )
                }
            }
        )
    }

    cropUri?.let { uri ->
        SelectImageScreen(
            context = context,
            uri = uri,
            onCancel = { cropUri = null },
            onSave = { crop ->
                cropUri = null
                isProcessing = true
                viewModel.saveProfilePicture(uri, crop) { success, message ->
                    isProcessing = false
                    if (!success) {
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }
}
