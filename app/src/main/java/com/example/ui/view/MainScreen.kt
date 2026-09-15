package com.example.ui.view

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.painterResource
import android.graphics.drawable.BitmapDrawable
import android.util.DisplayMetrics
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import com.example.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.content.Intent
import java.io.File
import java.io.FileOutputStream
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import coil.compose.AsyncImage
import com.example.data.model.AppSettings
import com.example.data.model.Member
import com.example.data.model.Savings
import com.example.ui.viewmodel.SavingsViewModel
import com.example.util.PasswordValidator
import java.text.SimpleDateFormat
import java.util.*

// Helper function to save a selected URI to app internal storage
fun saveUriToInternalStorage(context: android.content.Context, uri: Uri): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val file = File(context.filesDir, "profile_picture_${System.currentTimeMillis()}.png")
        val outputStream = FileOutputStream(file)
        inputStream.use { input ->
            outputStream.use { output ->
                input.copyTo(output)
            }
        }
        file.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

enum class AppTab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home),
    Members("Members", Icons.Default.Group),
    Bank("Bank", Icons.Default.AccountBalance),
    Admin("Admin", Icons.Default.AdminPanelSettings),
    Settings("Settings", Icons.Default.Settings)
}

/**
 * Main application entry point that enforces authentication.
 * Shows the gorgeous login/signup screen if no active session exists.
 */
@Composable
fun MainScreen(viewModel: SavingsViewModel) {
    val currentUserId by viewModel.currentUserId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showExitDialog by remember { mutableStateOf(false) }

    // Comment: Show confirmation dialog when user attempts to exit the application via back gesture or button
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text(text = "Exit?") },
            text = { Text(text = "Are you sure you want to close and exit the application?") },
            confirmButton = {
                Button(
                    onClick = {
                        showExitDialog = false
                        // Comment: Clear the task stack AND terminate the process on exit. finishAndRemoveTask() alone leaves the process cached, so the next launch is a warm relaunch that stays on a white screen until the user touches the display. Killing the process makes the next launch a true cold start that draws its first frame correctly; the short delay lets the finish/task removal complete first.
                        val activity = context as? android.app.Activity
                        activity?.finishAndRemoveTask()
                        activity?.window?.decorView?.postDelayed({
                            try {
                                android.os.Process.killProcess(android.os.Process.myPid())
                            } catch (e: Exception) {
                                // Fallback: process kill failed, but finishAndRemoveTask() above already cleared the task
                            }
                        }, 150)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(text = "Exit")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text(text = "Cancel")
                }
            }
        )
    }

    // Comment: Handle back button and back gesture to intercept app closing and show the exit dialog
    androidx.activity.compose.BackHandler(enabled = true) {
        showExitDialog = true
    }

    // Comment: Protect private pages with supabase.auth.getSession() — if no session, redirect to login/sigin in page
    androidx.compose.runtime.LaunchedEffect(currentUserId) {
        if (currentUserId != null) {
            val session = com.example.data.SupabaseClient.getSession()
            if (session == null) {
                viewModel.logout()
            }
        }
    }

    if (currentUserId == null) {
        // Enforce user sign-in/sign-up
        AuthScreen(viewModel = viewModel)
    } else {
        // Display the fully featured app content
        MainAppContent(viewModel = viewModel)
    }

    // Comment: Render the Reset Password Dialog when the user logs in via a secure password reset link
    val showResetPasswordDialog by viewModel.showResetPasswordDialog.collectAsStateWithLifecycle()
    if (showResetPasswordDialog) {
        var newPasswordInput by remember { mutableStateOf("") }
        var isPasswordVisible by remember { mutableStateOf(false) }
        var isSaving by remember { mutableStateOf(false) }
        var errorMsg by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { viewModel.setResetPasswordDialogVisible(false) },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Lock Icon",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(text = "Choose New Password")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "You have successfully authenticated via a secure recovery link. Please choose a strong, new password below to update your account.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = newPasswordInput,
                        onValueChange = {
                            newPasswordInput = it
                            errorMsg = null
                        },
                        label = { Text("New Password") },
                        placeholder = { Text("At least 8 characters with letters and numbers") },
                        visualTransformation = if (isPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Password Icon",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                Icon(
                                    imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (isPasswordVisible) "Hide password" else "Show password"
                                )
                            }
                        },
                        singleLine = true,
                        isError = errorMsg != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("new_password_field")
                    )

                    if (errorMsg != null) {
                        Text(
                            text = errorMsg ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        // Comment: Validate through the shared password policy so reset, signup, and change password all enforce the same rules.
                        val passwordError = PasswordValidator.validate(newPasswordInput)
                        if (passwordError != null) {
                            errorMsg = passwordError
                            return@Button
                        }
                        isSaving = true
                        viewModel.updatePassword(newPasswordInput) { success, msg ->
                            isSaving = false
                            if (success) {
                                viewModel.setResetPasswordDialogVisible(false)
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            } else {
                                errorMsg = msg
                            }
                        }
                    },
                    enabled = !isSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.testTag("save_new_password_button")
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .size(18.dp)
                                .padding(end = 8.dp)
                        )
                    }
                    Text("Save Password")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.setResetPasswordDialogVisible(false) },
                    enabled = !isSaving
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Main application screen composing top app bar, bottom navigation, and active tab content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContent(viewModel: SavingsViewModel) {
    var activeTab by remember { mutableStateOf(AppTab.Home) }

    // Comment: Settings accordion a notification tap asked to open (e.g. App Update), handed down to SettingsScreen
    var requestedSettingsSection by remember { mutableStateOf<String?>(null) }
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Comment: Request POST_NOTIFICATIONS permission dynamically on Android 13+ (API 33+) to allow showing status bar reminders
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { _ -> }

        androidx.compose.runtime.LaunchedEffect(Unit) {
            val isGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!isGranted) {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Retrieve user savings to check payment status in the active cycle
    val currentUserSavings by viewModel.currentUserSavings.collectAsStateWithLifecycle()

    // Comment: Collect change requests globally to control the admin top bar notification red dot
    val changeRequests by viewModel.allChangeRequests.collectAsStateWithLifecycle()

    // Comment: Collect the list of members to determine if the current user has been sent a notification by an admin
    val members by viewModel.allMembers.collectAsStateWithLifecycle()
    val currentUserId by viewModel.currentUserId.collectAsStateWithLifecycle()
    val currentMemberEmail by viewModel.currentMemberEmail.collectAsStateWithLifecycle()

    // Comment: Collect the manual syncing state flow to disable the button and show a spinner during sync
    val isManualSyncing by viewModel.isManualSyncing.collectAsStateWithLifecycle()

    val currentMember = members.find { member ->
        if (currentMemberEmail != null) {
            member.email.trim().equals(currentMemberEmail?.trim(), ignoreCase = true)
        } else {
            member.id == (currentUserId ?: -1)
        }
    }

    // Comment: Check if the current user has an Admin role to show or hide the Admin Panel screen conditionally
    val isAdmin = currentMember?.role == "Admin"

    // Comment: Redirect the user to the Home screen if their Admin status is revoked while viewing the Admin tab
    LaunchedEffect(isAdmin) {
        if (!isAdmin && activeTab == AppTab.Admin) {
            activeTab = AppTab.Home
        }
    }

    // Comment: Close any open Admin Panel section when the user navigates away from the Admin page
    LaunchedEffect(activeTab) {
        if (activeTab != AppTab.Admin) {
            viewModel.closeAllAdminSections()
        }
    }

    // Comment: Collect pending navigation routes to route the admin to Admin Panel > Change Requests if notification is clicked
    val pendingNavigationRoute by viewModel.pendingNavigationRoute.collectAsStateWithLifecycle()
    LaunchedEffect(pendingNavigationRoute, isAdmin) {
        pendingNavigationRoute?.let { route ->
            if (route == "admin_change_requests") {
                if (isAdmin) {
                    activeTab = AppTab.Admin
                    viewModel.openAdminSection(SavingsViewModel.SECTION_CHANGE_REQUESTS)
                    // Comment: Clear the route only after navigation succeeds so a route received while the
                    // admin's role is still loading is not discarded before the Admin Panel can open.
                    viewModel.clearPendingNavigation()
                }
            }
            // Comment: An app-update notification opens Settings with the App Update section expanded,
            // so a discovered update can be installed without hunting through the Settings list
            if (route == "app_update") {
                requestedSettingsSection = "update"
                activeTab = AppTab.Settings
                viewModel.clearPendingNavigation()
            }
        }
    }

    // Comment: Drop the requested Settings section once the user leaves the Settings tab, so coming back
    // to Settings later does not keep forcing the same accordion open
    LaunchedEffect(activeTab) {
        if (activeTab != AppTab.Settings) {
            requestedSettingsSection = null
        }
    }

    // Comment: Check if the app broadcasts automatic notification or if a targeted notification was sent by admins
    val hasBroadcastNotification = appSettings.enableNotifications
    val hasAdminNotification = currentMember?.receivedAdminNotification == true
    val hasNotification = hasBroadcastNotification || hasAdminNotification

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (activeTab) {
                            AppTab.Home -> "Personal Dashboard"
                            AppTab.Members -> "Members Dashboard"
                            AppTab.Bank -> "Bank Deposits"
                            AppTab.Admin -> "Admin Panel"
                            AppTab.Settings -> "Settings"
                        },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    // Clickable App Logo component replacing the old Gavel Action
                    // Comment: Display the actual manually changed app logo via rememberAppLogoPainter() directly without any background gradient or internal padding constraints, enabling a full, edge-to-edge logo display.
                    Image(
                        painter = rememberAppLogoPainter(),
                        contentDescription = "Swanirvor-23 App Logo",
                        modifier = Modifier
                            .padding(start = 12.dp, end = 8.dp)
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                Toast.makeText(context, "Swanirvor-23: Community Savings Society", Toast.LENGTH_SHORT).show()
                            }
                    )
                },
                actions = {
                    // Comment: Add a manual sync button to the left side of notification button in the app header
                    IconButton(
                        onClick = {
                            viewModel.triggerManualSync { success, message ->
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = !isManualSyncing
                    ) {
                        if (isManualSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Manual Sync",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    IconButton(onClick = {
                        // Comment: As per user instructions, do not show any toast notifications or red badge on notification click
                    }) {
                        Box {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = "Notifications",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            // Comment: Dynamically display a red dot over the notification icon for Admins if there are pending change requests
                            val hasPendingRequests = changeRequests.any { it.status == "Pending" }
                            if (isAdmin && hasPendingRequests) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Color.Red, shape = CircleShape)
                                        .align(Alignment.TopEnd)
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            // Comment: Filter bottom navigation tabs dynamically based on whether the current user is an Admin
            val visibleTabs = AppTab.values().filter { tab ->
                tab != AppTab.Admin || isAdmin
            }
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                modifier = Modifier.navigationBarsPadding()
            ) {
                visibleTabs.forEach { tab ->
                    NavigationBarItem(
                        selected = activeTab == tab,
                        onClick = { activeTab = tab },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label
                            )
                        },
                        label = {
                            Text(text = tab.label, style = MaterialTheme.typography.labelMedium)
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        ),
                        modifier = Modifier.testTag("tab_${tab.name.lowercase(Locale.ROOT)}")
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Comment: Ensure only permitted tabs are rendered; render HomeScreen as fallback if user tries to access Admin while non-admin
            when (activeTab) {
                AppTab.Home -> HomeScreen(viewModel = viewModel)
                AppTab.Members -> MembersScreen(viewModel = viewModel)
                AppTab.Bank -> BankScreen(viewModel = viewModel, isAdmin = isAdmin)
                AppTab.Admin -> {
                    if (isAdmin) {
                        AdminScreen(viewModel = viewModel)
                    } else {
                        HomeScreen(viewModel = viewModel)
                    }
                }
                AppTab.Settings -> SettingsScreen(
                    viewModel = viewModel,
                    initialOpenSection = requestedSettingsSection
                )
            }
        }
    }
}

/**
 * Helper composable to load a raster density version of R.mipmap.ic_launcher (bypassing anydpi-v26 xml to prevent crashes)
 * and falls back to ic_favicon_placeholder on any unexpected issue.
 */
@Composable
private fun rememberAppLogoPainter(): Painter {
    val context = LocalContext.current
    return remember(context) {
        val densities = listOf(
            DisplayMetrics.DENSITY_XXXHIGH,
            DisplayMetrics.DENSITY_XXHIGH,
            DisplayMetrics.DENSITY_XHIGH,
            DisplayMetrics.DENSITY_HIGH,
            DisplayMetrics.DENSITY_MEDIUM
        )
        var bitmapPainter: Painter? = null
        for (density in densities) {
            try {
                val drawable = context.resources.getDrawableForDensity(
                    R.mipmap.ic_launcher,
                    density,
                    null
                )
                if (drawable is BitmapDrawable) {
                    bitmapPainter = BitmapPainter(drawable.bitmap.asImageBitmap())
                    break
                }
            } catch (e: Exception) {
                // Ignore and try lower density folders
            }
        }
        bitmapPainter
    } ?: painterResource(id = R.drawable.ic_favicon_placeholder)
}
