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
import androidx.compose.ui.window.DialogProperties
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
import com.example.util.formatToDdMmYyyy
import com.example.util.isCurrentMonth
import java.text.SimpleDateFormat
import java.util.*

/**
 * Members Dashboard Composable (Members tab)
 */
@Composable
fun MembersScreen(viewModel: SavingsViewModel) {
    val searchQuery by viewModel.memberSearchQuery.collectAsStateWithLifecycle()
    val filteredMembers by viewModel.filteredMembers.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val isDarkMode = appSettings.isDarkMode

    // Collect all savings to filter and show history for selected member
    val allSavings by viewModel.allSavings.collectAsStateWithLifecycle()

    // Comment: Grand Total is computed from each member's already-derived totalSavings, so no new stored field or schema change is needed
    val grandTotal = filteredMembers.sumOf { it.totalSavings }

    // State to hold the currently selected member for showing their savings history
    var selectedMemberForHistory by remember { mutableStateOf<Member?>(null) }
    var selectedMemberForImage by remember { mutableStateOf<Member?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.updateMemberSearchQuery(it) },
            placeholder = { Text("Search members by name...") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.outline
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("member_search_input"),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedBorderColor = Color(0xFF0C9488)
            ),
            singleLine = true
        )

        // Members List scrollable
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (filteredMembers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No members found",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.outline
                        )
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    filteredMembers.forEach { member ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // Set selected member to show their savings history dialog
                                    selectedMemberForHistory = member
                                }
                                .testTag("member_row_${member.id}"),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDarkMode) Color(0xFF131B2E) else Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    MemberAvatar(
                                        name = member.name,
                                        avatarUrl = member.avatarUrl,
                                        modifier = Modifier
                                            .clickable { selectedMemberForImage = member }
                                            .testTag("member_avatar_${member.id}")
                                    )
                                    Column {
                                        Text(
                                            text = member.name,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        )
                                        Text(
                                            text = "Membership No.: ${member.membershipNo.ifBlank { "Not set" }}",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        )
                                    }
                                }

                                Column(
                                    horizontalAlignment = Alignment.End
                                ) {
                                    Text(
                                        text = "Total Savings",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                    Text(
                                        text = String.format(Locale.US, "%,.0f৳", member.totalSavings),
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Grand Total section: sits right under the last member's Total Savings
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("grand_total_section"),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)), // Soft light green to set it apart
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Color(0xFFDCFCE7))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Grand Total=",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF166534)
                    )
                )
                Text(
                    text = String.format(Locale.US, "%,.0f৳", grandTotal),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF166534)
                    )
                )
            }
        }
    }

    // Render the details and savings history dialog if a member is clicked
    selectedMemberForHistory?.let { member ->
        val memberSavings = allSavings
            .filter { it.memberId == member.id }
            .sortedByDescending { it.timestamp }

        MemberSavingsHistoryDialog(
            member = member,
            savingsList = memberSavings,
            onDismiss = { selectedMemberForHistory = null }
        )
    }

    selectedMemberForImage?.let { member ->
        MemberProfileImageDialog(
            member = member,
            onDismiss = { selectedMemberForImage = null }
        )
    }
}

/**
 * Dialog displaying a member's complete savings history with modern Material Design 3 visuals.
 * Provides full transparency and visibility as requested by the user.
 */
@Composable
fun MemberSavingsHistoryDialog(
    member: Member,
    savingsList: List<Savings>,
    onDismiss: () -> Unit
) {
    var showLargeProfileImage by remember { mutableStateOf(false) }

    // Calculate total savings for the current month
    val currentMonthSavingsTotal = savingsList
        .filter { isCurrentMonth(it.dateText) }
        .sumOf { it.amount }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxSize(0.95f)
                    .testTag("member_history_dialog"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                // Header section with avatar, info, and close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        MemberAvatar(
                            name = member.name,
                            avatarUrl = member.avatarUrl,
                            size = 48.dp,
                            modifier = Modifier
                                .clickable { showLargeProfileImage = true }
                                .testTag("history_profile_image")
                        )
                        Column {
                            Text(
                                text = member.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            )
                            Text(
                                text = "Membership No.: ${member.membershipNo.ifBlank { "Not set" }}",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.outline)
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(24.dp).testTag("close_history_dialog")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Financial Summary Cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Total Accumulated Savings Summary Card
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)), // Soft light green
                        border = BorderStroke(1.dp, Color(0xFFDCFCE7))
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = "Total Savings",
                                style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF15803D))
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            // Display formatted total savings as an integer value per user preference
                            Text(
                                text = String.format(Locale.US, "%,.0f৳", member.totalSavings),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = Color(0xFF166534))
                            )
                        }
                    }

                    // This Month Summary Card
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F9FF)), // Soft light blue
                        border = BorderStroke(1.dp, Color(0xFFE0F2FE))
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = "This Month's Savings",
                                style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF0369A1))
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            // Display formatted monthly savings as an integer value per user preference
                            Text(
                                text = String.format(Locale.US, "%,.0f৳", currentMonthSavingsTotal),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = Color(0xFF075985))
                            )
                        }
                    }
                }

                Text(
                    text = "Savings History",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                )

                // Scrollable deposit history ledger list; the 10% larger height gives the history dialog more vertical room.
                if (savingsList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(132.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No savings history found.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.outline)
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 264.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        savingsList.forEach { saving ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Visual check indicator
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFFE6F4EA)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "Paid Indicator",
                                                tint = Color(0xFF137333),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Column {
                                            Text(
                                                text = "Weekly Deposit",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                            )
                                            Text(
                                                text = formatToDdMmYyyy(saving.dateText),
                                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.outline)
                                            )
                                        }
                                    }
                                    // Display formatted row deposit amount as an integer per user preference
                                    Text(
                                        text = String.format(Locale.US, "+%,.0f৳", saving.amount),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = Color(0xFF0C9488))
                                    )
                                }
                            }
                        }
                    }
                }

                    Spacer(modifier = Modifier.weight(1f))

                    // Close Action Button stays at the bottom of the 95% dialog window.
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("dismiss_history_dialog"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0C9488)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Close", color = Color.White, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }

    if (showLargeProfileImage) {
        MemberProfileImageDialog(
            member = member,
            onDismiss = { showLargeProfileImage = false }
        )
    }
}

@Composable
private fun MemberProfileImageDialog(
    member: Member,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (member.avatarUrl.isNullOrEmpty()) {
                    MemberAvatar(name = member.name, avatarUrl = null, size = 240.dp)
                } else {
                    AsyncImage(
                        model = member.avatarUrl,
                        contentDescription = "Large avatar of ${member.name}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        contentScale = ContentScale.Crop
                    )
                }
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Avatar rendering component which loads network image via Coil or falls back to initials
 */
@Composable
fun MemberAvatar(
    name: String,
    avatarUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    if (!avatarUrl.isNullOrEmpty()) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = "Avatar of $name",
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        val initials = name.split(" ")
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .take(2)
            .joinToString("")

        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    }
}
