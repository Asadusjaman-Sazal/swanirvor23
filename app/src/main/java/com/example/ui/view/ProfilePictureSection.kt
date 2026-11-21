package com.example.ui.view

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val uploadError by viewModel.profilePictureUploadError.collectAsStateWithLifecycle()

    // Comment: The main screen owns the failure toast so it is visible from any tab; this section
    // keeps the persistent message and Retry action next to the local preview.
    // Startup may have run before this session existed. Opening the profile is another chance to
    // flush a persisted pending upload; it is a no-op when nothing is pending.
    LaunchedEffect(userId) {
        viewModel.retryPendingProfilePictureUpload()
    }

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
    // Gallery uses the device's gallery/photos picker directly. AndroidX PickVisualMedia falls back
    // to ACTION_OPEN_DOCUMENT (DocumentsUI / File Manager) on devices without the system photo
    // picker, which is the behaviour this row must avoid. The photo picker is kept only as a
    // fallback for a device with no gallery handler at all; Files deliberately uses OpenDocument.
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) cropUri = uri
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { cropUri = it }
        }
    }
    val filesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) cropUri = uri
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
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

        val error = uploadError
        if (error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .testTag("profile_picture_upload_error")
            )
            TextButton(
                onClick = { viewModel.retryProfilePictureUpload() },
                modifier = Modifier.testTag("retry_profile_picture_upload")
            ) {
                Text("Retry upload")
            }
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
                            // Comment: ACTION_PICK with the MediaStore image collection opens the
                            // gallery/photos app rather than the document picker. The read grant
                            // survives long enough for the crop page to decode the selection.
                            val intent = Intent(
                                Intent.ACTION_PICK,
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            ).apply {
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            try {
                                galleryLauncher.launch(intent)
                            } catch (e: ActivityNotFoundException) {
                                // Comment: No gallery app on this device; use the system photo picker.
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
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
                    } else if (message.isNotBlank()) {
                        // Comment: This is a local-save confirmation, not a claim that other members
                        // can already see the new picture; the upload failure has its own banner.
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
}
