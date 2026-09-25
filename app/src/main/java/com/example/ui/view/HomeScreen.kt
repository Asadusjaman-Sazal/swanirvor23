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
import com.example.util.calculateNextThursday
import com.example.util.formatToDdMmYyyy
import com.example.util.getCurrentCycleRange
import com.example.util.getPreviousCycleRange
import com.example.util.getElapsedCycleCount
import com.example.util.isCurrentMonth
import com.example.util.parseDateTextToMillis
import com.example.util.SavingsOrdering
import java.text.SimpleDateFormat
import java.util.*

/**
 * Personal Dashboard Composable (Home tab)
 */
@Composable
fun HomeScreen(viewModel: SavingsViewModel) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val savingsList by viewModel.currentUserSavings.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val members by viewModel.allMembers.collectAsStateWithLifecycle()

    // Fetch the current user's profile member to sync total savings and goal correctly
    val currentMemberEmail by viewModel.currentMemberEmail.collectAsStateWithLifecycle()
    val currentMember = members.find { member ->
        if (currentMemberEmail != null) {
            member.email.trim().equals(currentMemberEmail?.trim(), ignoreCase = true)
        } else {
            member.id == viewModel.currentUserMemberId
        }
    }

    // Calculate dynamic total savings based on database data
    val calculatedTotal = savingsList.sumOf { it.amount }
    val displayTotal = if (calculatedTotal > 0) calculatedTotal else (currentMember?.totalSavings ?: 0.0)

    // Calculate total savings for the current month
    val currentMonthSavings = savingsList.filter { isCurrentMonth(it.dateText) }.sumOf { it.amount }

    // Check the selected contribution date in the last cycle (previous week Friday to Thursday)
    val (lastCycleStart, lastCycleEnd) = getPreviousCycleRange()
    val userPaidLastCycle = savingsList.any { s ->
        parseDateTextToMillis(s.dateText) in lastCycleStart..lastCycleEnd
    }

    // Active Cycle range (this cycle's Friday 00:00 to Thursday 23:59)
    val (activeCycleStartsMillis, activeCycleEndsMillis) = remember {
        getCurrentCycleRange()
    }
    val userPaidCurrentActiveCycle = savingsList.any { s ->
        parseDateTextToMillis(s.dateText) in activeCycleStartsMillis..activeCycleEndsMillis
    }

    // Comment: Derive Personal Dashboard totals from the elapsed savings cycles and the central Weekly Savings
    // Goal an admin sets once for the whole society, so every member projects the same savings.
    // Cycle count uses the same Fri→Thu cycle the Admin Panel "This Week's Payment" uses (community started on the cycle opening Fri, 27 Mar 2026).
    val weeklyGoal by viewModel.weeklyGoal.collectAsStateWithLifecycle()
    val elapsedCycles = getElapsedCycleCount()
    // Comment: Total Due = (cycles elapsed × weekly goal) − total savings already deposited.
    val totalDue = maxOf(0.0, (elapsedCycles * weeklyGoal) - displayTotal)
    // Comment: Total Projected Savings = cycles elapsed × weekly goal (the full amount expected by now).
    val totalProjected = elapsedCycles * weeklyGoal

    var editingSavings by remember { mutableStateOf<Savings?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<Savings?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Welcome Header with profile picture
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            val avatarUrl = currentMember?.avatarUrl ?: appSettings.profileImageUrl
            MemberAvatar(
                name = appSettings.profileName,
                avatarUrl = avatarUrl,
                size = 44.dp
            )
            Column {
                Text(
                    text = "Welcome back,",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                )
                Text(
                    text = "Adv. ${appSettings.profileName}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }

        // ALERT! Card for missed payment (raised only when the user has no entry in the Savings History whose date falls in the previous cycle, so an ongoing current cycle never triggers it)
        if (!userPaidLastCycle) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("alert_card"),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning alert",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Column {
                        Text(
                            text = "ALERT!",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "You have missed a weekly payment. Please deposit ${weeklyGoal.toInt()}৳.",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
                            )
                        )
                    }
                }
            }
        }

        // Bento Card: Total Saved with premium left Gold boundary
        // Note: Left yellow outline removed per user request for a cleaner look
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("total_savings_card"),
            colors = CardDefaults.cardColors(
                containerColor = if (appSettings.isDarkMode) Color(0xFF131B2E) else Color.White
            ),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TOTAL SAVINGS",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                letterSpacing = 1.2.sp
                            )
                        )
                        Icon(
                            imageVector = Icons.Default.AccountBalance,
                            contentDescription = "Savings Balance",
                            tint = Color(0xFFFED488),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Text(
                        text = String.format(Locale.US, "%,.0f৳", displayTotal),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 32.sp
                        )
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                            contentDescription = "Trend Up",
                            tint = Color(0xFF0C9488),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "+ ${currentMonthSavings.toInt()}৳ this month",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = Color(0xFF0C9488),
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }
            }
        }

        // Bento Card: Total Due (cycles elapsed × weekly goal − total savings), shown under Total Savings
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("total_due_card"),
            colors = CardDefaults.cardColors(
                containerColor = if (appSettings.isDarkMode) Color(0xFF2A1A1A) else Color(0xFFFEF2F2)
            ),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFFFECACA))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TOTAL DUE",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                letterSpacing = 1.2.sp
                            )
                        )
                        Icon(
                            imageVector = Icons.Default.AccountBalanceWallet,
                            contentDescription = "Total Due",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Text(
                        text = String.format(Locale.US, "%,.0f৳", totalDue),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEF4444),
                            fontSize = 32.sp
                        )
                    )

                    Text(
                        text = "$elapsedCycles ${if (elapsedCycles == 1) "week" else "weeks"} × ${weeklyGoal.toInt()}৳ − ${displayTotal.toInt()}৳",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }
        }

        // Bento Card: Total Projected Savings (cycles elapsed × weekly goal), shown under Total Due
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("total_projected_card"),
            colors = CardDefaults.cardColors(
                containerColor = if (appSettings.isDarkMode) Color(0xFF13211B) else Color(0xFFF0FDF4)
            ),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFFBBF7D0))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TOTAL PROJECTED SAVINGS",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                letterSpacing = 1.2.sp
                            )
                        )
                        Icon(
                            imageVector = Icons.Default.Savings,
                            contentDescription = "Total Projected Savings",
                            tint = Color(0xFF16A34A),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Text(
                        text = String.format(Locale.US, "%,.0f৳", totalProjected),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF16A34A),
                            fontSize = 32.sp
                        )
                    )

                    Text(
                        text = "$elapsedCycles ${if (elapsedCycles == 1) "week" else "weeks"} × ${weeklyGoal.toInt()}৳",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }
        }

        // Bento Card: Upcoming Deadline / Congratulations Card
        // If the user has made any contribution within the current Active Cycle, show a Congratulations card.
        // Otherwise, show the default Upcoming Deadline card.
        if (userPaidCurrentActiveCycle) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("congratulations_card"),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFE8F5E9) // Light green background
                ),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFC8E6C9))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Success Icon",
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Congratulations",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E7D32)
                            )
                        )
                    }
                    Text(
                        text = "You have successfully deposited this week's payment.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = Color(0xFF2E7D32)
                        )
                    )
                }
            }
        } else {
            val nextThursdayInfo = calculateNextThursday()
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (appSettings.isDarkMode) Color(0xFF131B2E) else Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarToday,
                            contentDescription = "Calendar Deadline",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "NEXT DUE IN ${nextThursdayInfo.daysRemaining} ${if (nextThursdayInfo.daysRemaining == 1) "DAY" else "DAYS"}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }

                    Column {
                        Text(
                            text = nextThursdayInfo.formattedDateText,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        Text(
                            text = String.format(Locale.US, "%,.0f৳", weeklyGoal),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.outline
                            )
                        )
                    }
                }
            }
        }

        // Recent Contributions Section
        // Comment: Rows read newest-first by the contribution date (not insertion time) so back-dated or synced deposits stay in chronological order.
        val sortedSavings = remember(savingsList) { SavingsOrdering.newestFirst(savingsList) }
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Savings History header; the non-functional "View All" action was removed.
            Text(
                text = "Savings History",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )

            if (savingsList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No history available. Add contributions in Admin Panel.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.outline
                        )
                    )
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (appSettings.isDarkMode) Color(0xFF131B2E) else Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        sortedSavings.forEachIndexed { index, item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { }
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(
                                                MaterialTheme.colorScheme.surfaceContainer,
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Payments,
                                            contentDescription = "Payment icon",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = String.format(Locale.US, "%.0f৳", item.amount),
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        )
                                        Text(
                                            text = formatToDdMmYyyy(item.dateText),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        )
                                    }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(
                                        onClick = { editingSavings = item },
                                        modifier = Modifier.testTag("edit_savings_${item.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Edit Savings",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { showDeleteConfirm = item },
                                        modifier = Modifier.testTag("delete_savings_${item.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete Savings",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }

                            if (index < sortedSavings.size - 1) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Edit Savings dialog
    editingSavings?.let { item ->
        var amountText by remember { mutableStateOf(item.amount.toInt().toString()) }
        var dateText by remember { mutableStateOf(formatToDdMmYyyy(item.dateText)) }

        AlertDialog(
            onDismissRequest = { editingSavings = null },
            title = { Text("Edit Savings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("Amount (৳)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = { dateText = it },
                        label = { Text("Date") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        val amt = amountText.toDoubleOrNull() ?: item.amount
                        viewModel.requestEditSavings(item, amt, dateText)
                        editingSavings = null
                        Toast.makeText(context, "Edit request submitted to Admin!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Submit Request")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingSavings = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete confirmation dialog
    showDeleteConfirm?.let { item ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Request Deletion?") },
            text = { Text("Would you like to request deletion of this savings entry of ${item.amount.toInt()}৳ on ${item.dateText}?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.requestDeleteSavings(item)
                        showDeleteConfirm = null
                        Toast.makeText(context, "Delete request submitted to Admin!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Submit Request")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
