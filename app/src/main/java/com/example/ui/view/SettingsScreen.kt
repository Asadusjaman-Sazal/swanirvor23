package com.example.ui.view

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
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
import java.text.SimpleDateFormat
import java.util.*

/**
 * Settings Screen Composable (Settings tab)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SavingsViewModel) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val currentMemberEmail by viewModel.currentMemberEmail.collectAsStateWithLifecycle()
    // Comment: Prefer the authenticated account email so this field cannot be changed through profile settings.
    val registeredEmail = currentMemberEmail ?: com.example.data.SupabaseClient.getSessionEmail().orEmpty()

    var isDarkMode by remember { mutableStateOf(appSettings.isDarkMode) }
    var savingsGoalText by remember { mutableStateOf(appSettings.personalGoal.toInt().toString()) }
    var profileNameText by remember { mutableStateOf(appSettings.profileName) }
    var membershipNoText by remember { mutableStateOf(appSettings.membershipNo) }
    // Comment: The stored mobile number carries the +88 country code; the input box shows only the digits after the immutable prefix
    var mobileNoText by remember { mutableStateOf(appSettings.mobileNo.removePrefix("+88")) }

    // Comment: Track the single open Settings accordion section (null = all closed); only one can be open at a time
    var settingsOpenSection by remember { mutableStateOf<String?>(null) }

    // Comment: Toggle the given Settings section, closing it again if it is already open
    fun toggleSettingsSection(section: String) {
        settingsOpenSection = if (settingsOpenSection == section) null else section
    }
    var notificationsEnabled by remember { mutableStateOf(appSettings.enableNotifications) }
    var notificationDay by remember { mutableStateOf(appSettings.notificationDay) }
    var notificationTime by remember { mutableStateOf(appSettings.notificationTime) }
    var notificationText by remember { mutableStateOf(appSettings.notificationText) }
    var showNotificationDayDropdown by remember { mutableStateOf(false) }

    // Launcher to select a profile picture from device media gallery
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val localPath = saveUriToInternalStorage(context, uri)
            if (localPath != null) {
                viewModel.updateProfileInfo(profileNameText, membershipNoText, localPath, mobileNo = mobileNoText)
                Toast.makeText(context, "Profile picture updated successfully!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to save profile picture.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Synchronize states on DB flow update
    LaunchedEffect(appSettings) {
        isDarkMode = appSettings.isDarkMode
        savingsGoalText = appSettings.personalGoal.toInt().toString()
        profileNameText = appSettings.profileName
        membershipNoText = appSettings.membershipNo
        mobileNoText = appSettings.mobileNo.removePrefix("+88")
        // Comment: Synchronize automated notification states with remote app settings
        notificationsEnabled = appSettings.enableNotifications
        notificationDay = appSettings.notificationDay
        notificationTime = appSettings.notificationTime
        notificationText = appSettings.notificationText
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // --- Theme SECTION (formerly Appearance) ---
        // Made into a collapsible button like the Admin panel
        AccordionCard(
            title = "Theme",
            icon = Icons.Default.Palette,
            isOpen = settingsOpenSection == "theme",
            onToggle = { toggleSettingsSection("theme") },
            containerColor = if (isDarkMode) Color(0xFF131B2E) else Color.White
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Removed the redundant "Theme" subtitle as requested by user, keeping only the descriptive guide
                Text(
                    text = "Select your preferred interface mode",
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )

                // Theme selector buttons layout
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceContainer,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(4.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.setDarkMode(false)
                            Toast.makeText(context, "Light theme activated!", Toast.LENGTH_SHORT).show()
                        },
                        // Set the selected Light button container to white only in light mode, transparent otherwise
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!isDarkMode) Color.White else Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = if (!isDarkMode) 2.dp else 0.dp)
                    ) {
                        Text("Light", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            viewModel.setDarkMode(true)
                            Toast.makeText(context, "Dark theme activated!", Toast.LENGTH_SHORT).show()
                        },
                        // Set selected Dark button container to a beautiful dark navy to make light text completely readable in dark mode
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDarkMode) Color(0xFF213145) else Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = if (isDarkMode) 2.dp else 0.dp)
                    ) {
                        Text("Dark", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // --- Weekly Savings Goal SECTION (formerly Savings) ---
        // Made into a collapsible button like the Admin panel
        AccordionCard(
            title = "Weekly Savings Goal",
            icon = Icons.Default.Savings,
            isOpen = settingsOpenSection == "savings",
            onToggle = { toggleSettingsSection("savings") },
            containerColor = if (isDarkMode) Color(0xFF131B2E) else Color.White
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Removed the "Personal Savings Goal" subtitle as requested by user, keeping the descriptive guide
                Text(
                    text = "Set your weekly target amount",
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )

                // Layout containing personal savings goal input and its save button to the right
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = savingsGoalText,
                        onValueChange = {
                            savingsGoalText = it
                        },
                        leadingIcon = { Text("৳", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("personal_savings_goal_input"),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Button(
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            val amt = savingsGoalText.toDoubleOrNull()
                            if (amt != null) {
                                viewModel.setPersonalGoal(amt)
                                Toast.makeText(context, "Personal goal saved successfully!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Please enter a valid amount!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.testTag("personal_savings_goal_save_button")
                    ) {
                        Text("Save")
                    }
                }
            }
        }

        // --- Automated Notification SECTION ---
        // Comment: Added the Automated Notification settings block directly before the Profile Information section as requested.
        AccordionCard(
            title = "Automated Notification",
            icon = Icons.Default.NotificationsActive,
            isOpen = settingsOpenSection == "notification",
            onToggle = { toggleSettingsSection("notification") },
            containerColor = if (isDarkMode) Color(0xFF131B2E) else Color.White
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Enable Notifications", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = notificationsEnabled,
                        onCheckedChange = { notificationsEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF0C9488)
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Comment: Give the day field more width so weekday names stay on a single line; the time field is correspondingly narrower
                    Column(modifier = Modifier.weight(1.5f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Notification Day",
                            style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                        ExposedDropdownMenuBox(
                            expanded = showNotificationDayDropdown,
                            onExpandedChange = { showNotificationDayDropdown = !showNotificationDayDropdown }
                        ) {
                            OutlinedTextField(
                                value = notificationDay,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showNotificationDayDropdown) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(type = MenuAnchorType.PrimaryNotEditable, enabled = true),
                                shape = RoundedCornerShape(8.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = showNotificationDayDropdown,
                                onDismissRequest = { showNotificationDayDropdown = false }
                            ) {
                                listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday").forEach { day ->
                                    DropdownMenuItem(
                                        text = { Text(day) },
                                        onClick = {
                                            notificationDay = day
                                            showNotificationDayDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Notification Time",
                            style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                        OutlinedTextField(
                            value = notificationTime,
                            onValueChange = { notificationTime = it },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Notification Text",
                        style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                    OutlinedTextField(
                        value = notificationText,
                        onValueChange = { notificationText = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        maxLines = 3
                    )
                }

                Button(
                    onClick = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        viewModel.updateNotificationSettings(
                            enabled = notificationsEnabled,
                            day = notificationDay,
                            time = notificationTime,
                            text = notificationText
                        )
                        Toast.makeText(context, "Notification Settings Saved!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Save, contentDescription = "Save settings", modifier = Modifier.size(18.dp))
                        Text("Save Settings")
                    }
                }
            }
        }

        // --- Profile Information SECTION ---
        // Made into a collapsible button like the Admin panel, keeping all inner contents exactly as they are
        AccordionCard(
            title = "Profile Information",
            icon = Icons.Default.Person,
            isOpen = settingsOpenSection == "profile",
            onToggle = { toggleSettingsSection("profile") },
            containerColor = if (isDarkMode) Color(0xFF131B2E) else Color.White
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Profile Picture Option (displayed before Profile Name)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Profile Picture",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        MemberAvatar(
                            name = appSettings.profileName,
                            avatarUrl = appSettings.profileImageUrl,
                            size = 64.dp
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Row wrapping the image action buttons (Choose Image and Delete button)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        imagePickerLauncher.launch("image/*")
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("upload_profile_picture_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudUpload,
                                        contentDescription = "Upload Picture",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Choose Image", style = MaterialTheme.typography.labelLarge)
                                }

                                // Delete button shown only when a profile picture is set
                                if (appSettings.profileImageUrl != null) {
                                    // Comment: Clear/Delete button to clear the currently uploaded profile picture, positioned to the right of Choose Image
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.updateProfileInfo(profileNameText, membershipNoText, null, mobileNo = mobileNoText)
                                            Toast.makeText(context, "Profile picture cleared!", Toast.LENGTH_SHORT).show()
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        ),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.testTag("delete_profile_picture_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete Picture",
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Clear", style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }
                }

                // Profile Name uses the full available width; all profile fields save together below.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "Profile Name", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                    OutlinedTextField(
                        value = profileNameText,
                        onValueChange = { profileNameText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("profile_name_input"),
                        shape = RoundedCornerShape(8.dp)
                    )
                }

                // Membership number uses the full available width; it shares the profile save action.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "Membership No.", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                    OutlinedTextField(
                        value = membershipNoText,
                        onValueChange = { membershipNoText = it },
                        placeholder = { Text("Enter your membership number") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("membership_no_input"),
                        shape = RoundedCornerShape(8.dp)
                    )
                }

                // Mobile number uses the full available width while retaining the immutable +88 prefix.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "Mobile No.", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                    OutlinedTextField(
                        value = mobileNoText,
                        onValueChange = {
                            // Comment: Keep only digits, drop a pasted leading 88 country code so it is not doubled,
                            // and cap at 11 digits so the full number with the +88 prefix never exceeds 14 characters
                            mobileNoText = it.filter(Char::isDigit).let { digits ->
                                val cleaned = if (digits.startsWith("88") && digits.length > 11) digits.removePrefix("88") else digits
                                cleaned.take(11)
                            }
                        },
                        prefix = { Text("+88", fontWeight = FontWeight.Bold) },
                        placeholder = { Text("1XXXXXXXXX") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mobile_no_input"),
                        shape = RoundedCornerShape(8.dp)
                    )
                }

                // Email is sourced from the authenticated account and remains read-only in profile settings.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "Email", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                    OutlinedTextField(
                        value = registeredEmail,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("email_input"),
                        shape = RoundedCornerShape(8.dp)
                    )
                }

                // Comment: Save all editable Profile Information fields together with one centered action.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            val fullMobileNo = if (mobileNoText.isBlank()) "" else "+88$mobileNoText"
                            viewModel.updateProfileInfo(profileNameText, membershipNoText, mobileNo = fullMobileNo)
                            Toast.makeText(context, "Profile information saved successfully!", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp),
                        modifier = Modifier.testTag("profile_info_save_button")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Save profile information", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save")
                    }
                }
            }
        }

        // Comment: Secure Sign Out button allowing users to safely log out and terminate their session
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Account Session",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = "You are currently signed in as an active member. Tap below to securely end your session.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = {
                        viewModel.logout()
                        Toast.makeText(context, "Logged out successfully!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("logout_button")
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Sign Out")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sign Out of Swanirvor-23", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
