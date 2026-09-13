package com.example.ui.view

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.UpdateChecker
import com.example.ui.viewmodel.UpdateUiState
import com.example.ui.viewmodel.UpdateViewModel
import java.io.File

/**
 * Settings > App Update section: checks the latest GitHub release of this app and, when a newer
 * version exists, downloads and installs the release APK without leaving the app.
 */
@Composable
fun AppUpdateSection(
    isOpen: Boolean,
    onToggle: () -> Unit,
    containerColor: Color
) {
    val context = LocalContext.current
    val updateViewModel: UpdateViewModel = viewModel()
    val uiState by updateViewModel.uiState.collectAsStateWithLifecycle()

    // Comment: Hold the release we want to download while the user grants the "install unknown apps"
    // permission, so the download resumes automatically when they come back.
    var releaseAwaitingPermission by remember { mutableStateOf<UpdateChecker.AppRelease?>(null) }
    val installPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val release = releaseAwaitingPermission
        releaseAwaitingPermission = null
        if (release != null) {
            // Comment: The Settings screen can report a cancelled result even after the user granted
            // access, so re-read the real permission state instead of trusting the result code.
            if (canInstallPackages(context)) {
                updateViewModel.downloadUpdate(release)
            } else {
                Toast.makeText(
                    context,
                    "Allow \"Install unknown apps\" for Swanirvor-23, then tap Update Now again.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    AccordionCard(
        title = "App Update",
        icon = Icons.Default.SystemUpdate,
        isOpen = isOpen,
        onToggle = onToggle,
        containerColor = containerColor
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Installed version ${updateViewModel.currentVersion}",
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            )

            Button(
                onClick = { updateViewModel.checkForUpdates() },
                enabled = uiState !is UpdateUiState.Checking && uiState !is UpdateUiState.Downloading,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("check_for_update_button")
            ) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Check for Update")
            }

            when (val state = uiState) {
                is UpdateUiState.Idle -> Unit

                is UpdateUiState.Checking -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = "Checking for updates…",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                }

                is UpdateUiState.UpToDate -> UpdateStatusRow(
                    icon = Icons.Default.CheckCircle,
                    tint = Color(0xFF0C9488),
                    message = "You're on the latest version."
                )

                is UpdateUiState.Failed -> UpdateStatusRow(
                    icon = Icons.Default.ErrorOutline,
                    tint = MaterialTheme.colorScheme.error,
                    message = state.message
                )

                is UpdateUiState.UpdateAvailable -> UpdateAvailableContent(
                    release = state.release,
                    onInstall = {
                        if (canInstallPackages(context)) {
                            updateViewModel.downloadUpdate(state.release)
                        } else {
                            // Comment: Android 8+ requires the user to explicitly allow this app to
                            // install packages before the installer intent is accepted.
                            releaseAwaitingPermission = state.release
                            launchInstallPermissionSettings(context, installPermissionLauncher)
                        }
                    },
                    onOpenReleasePage = { openInBrowser(context, state.release.releasePageUrl) }
                )

                is UpdateUiState.Downloading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Downloading update…",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        if (state.progress >= 0) {
                            LinearProgressIndicator(
                                progress = { state.progress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "${state.progress}%",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        } else {
                            // Comment: Some servers omit the content length, so fall back to an indeterminate bar
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        TextButton(onClick = { updateViewModel.cancelDownload() }) {
                            Text("Cancel")
                        }
                    }
                }

                is UpdateUiState.Downloaded -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Version ${state.version} downloaded",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                        Text(
                            text = "Tap Install to finish. Android will ask you to confirm the update.",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                        Button(
                            onClick = {
                                if (!startApkInstall(context, state.apkFile)) {
                                    Toast.makeText(
                                        context,
                                        "Couldn't open the installer. Please try again.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("install_update_button")
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Install")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders the "new version available" details with the install and release-page actions.
 */
@Composable
private fun UpdateAvailableContent(
    release: UpdateChecker.AppRelease,
    onInstall: () -> Unit,
    onOpenReleasePage: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Version ${release.version} is available",
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        )

        if (release.releaseNotes.isNotBlank()) {
            // Comment: Bound the release notes so a long changelog cannot push the rest of the
            // Settings page out of reach on small screens.
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = release.releaseNotes,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .heightIn(max = 140.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!release.apkUrl.isNullOrBlank()) {
                Button(
                    onClick = onInstall,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("download_update_button")
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Update Now")
                }
            }
            if (release.releasePageUrl.isNotBlank()) {
                TextButton(onClick = onOpenReleasePage) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("What's new")
                }
            }
        }
    }
}

/**
 * Compact single-line result row used for the up-to-date and error states.
 */
@Composable
private fun UpdateStatusRow(icon: ImageVector, tint: Color, message: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
        )
    }
}

/**
 * Reports whether this app may launch the system package installer.
 * Before Android 8 no per-app consent exists, so the check is always true.
 */
private fun canInstallPackages(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        context.packageManager.canRequestPackageInstalls()
}

/**
 * Opens the per-app "Install unknown apps" screen so the user can allow update installs.
 */
private fun launchInstallPermissionSettings(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    val intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}")
    )
    try {
        launcher.launch(intent)
    } catch (e: Exception) {
        // Comment: Very old or heavily skinned devices may not expose this screen; fall back to
        // opening app details so the user can still reach the permission manually.
        Log.e("AppUpdateSection", "Failed to open the unknown-sources settings screen", e)
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )
            )
        } catch (fallbackError: Exception) {
            Log.e("AppUpdateSection", "Failed to open app details settings", fallbackError)
        }
    }
}

/**
 * Launches the system package installer for a downloaded APK through the app's FileProvider.
 *
 * @return false when no installer accepted the intent, so the caller can surface a message.
 */
private fun startApkInstall(context: Context, apkFile: File): Boolean {
    return try {
        if (!apkFile.exists()) return false
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, UpdateChecker.apkMimeType())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.e("AppUpdateSection", "Failed to launch the package installer", e)
        false
    }
}

/**
 * Opens a URL in the user's browser, used for the public release page.
 */
private fun openInBrowser(context: Context, url: String) {
    if (url.isBlank()) return
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        Log.e("AppUpdateSection", "Failed to open the release page", e)
        Toast.makeText(context, "No app can open this link.", Toast.LENGTH_SHORT).show()
    }
}
