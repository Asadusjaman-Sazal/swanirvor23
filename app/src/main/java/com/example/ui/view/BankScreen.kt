package com.example.ui.view

import android.app.DatePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.BankDeposit
import com.example.data.model.Member
import com.example.ui.viewmodel.SavingsViewModel
import com.example.util.BankLedger
import com.example.util.formatToDdMmYyyy
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Bank Deposits screen: how much of the society's collected savings has been moved into the bank,
 * how much cash is still in hand, and the full deposit ledger.
 * Every member can read it; only admins can record, edit, or delete a deposit.
 */
@Composable
fun BankScreen(viewModel: SavingsViewModel, isAdmin: Boolean) {
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val isDarkMode = appSettings.isDarkMode

    val deposits by viewModel.allBankDeposits.collectAsStateWithLifecycle()
    val totalCollected by viewModel.totalCollected.collectAsStateWithLifecycle()
    val totalDeposited by viewModel.totalBankDeposited.collectAsStateWithLifecycle()
    val cashInHand by viewModel.cashInHand.collectAsStateWithLifecycle()
    val members by viewModel.allMembers.collectAsStateWithLifecycle()

    // Comment: Oldest first so the ledger serials stay stable as new deposits are appended at the end
    val ledger = remember(deposits) { BankLedger.orderedForLedger(deposits) }

    // Comment: Dialog state — the dialog is only ever opened to edit an existing row, so editingDeposit is always set
    var showEntryDialog by remember { mutableStateOf(false) }
    var editingDeposit by remember { mutableStateOf<BankDeposit?>(null) }
    var depositPendingDelete by remember { mutableStateOf<BankDeposit?>(null) }

    // Comment: A negative till means more was banked than collected, which must be surfaced rather than hidden
    val isCashNegative = cashInHand < 0.0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Comment: widthIn keeps the ledger readable on tablets instead of stretching it edge to edge
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Total deposited into the bank so far
            BankSummaryCard(
                label = "Total Deposited",
                valueText = String.format(Locale.US, "%,.0f৳", totalDeposited),
                icon = Icons.Default.AccountBalance,
                accent = Color(0xFF0C9488),
                contentDescription = "Total Deposited",
                modifier = Modifier.testTag("bank_total_deposited_section"),
                isDarkMode = isDarkMode
            )

            // Cash in hand = everything collected from members minus everything banked
            BankSummaryCard(
                label = "Cash in Hand",
                valueText = String.format(Locale.US, "%,.0f৳", cashInHand),
                icon = Icons.Default.Payments,
                accent = if (isCashNegative) MaterialTheme.colorScheme.error else Color(0xFF15803D),
                contentDescription = "Cash in Hand",
                modifier = Modifier.testTag("bank_cash_in_hand_section"),
                isDarkMode = isDarkMode
            )

            // Comment: Show the collected total so Cash in Hand is auditable instead of a bare figure
            Text(
                text = "Collected from members: " + String.format(Locale.US, "%,.0f৳", totalCollected),
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.outline)
            )

            if (isCashNegative) {
                Text(
                    text = "More has been banked than collected — check the ledger for a duplicate entry.",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.error)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Comment: Deposits can only be recorded from Admin Panel > Bank Deposition, so this screen lists them read-only
            Text(
                text = "Deposit History",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )

            if (ledger.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isAdmin) {
                            "No bank deposits recorded yet. Record one from Admin Panel > Bank Deposition."
                        } else {
                            "No bank deposits recorded yet."
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.outline)
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bank_deposit_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Comment: Each row is a ledger entry — index + 1 is its serial number
                    ledger.forEachIndexed { index, deposit ->
                        BankDepositRow(
                            serialText = BankLedger.formatSerial(index),
                            deposit = deposit,
                            isAdmin = isAdmin,
                            onEdit = {
                                editingDeposit = deposit
                                showEntryDialog = true
                            },
                            onDelete = { depositPendingDelete = deposit }
                        )
                    }
                }
            }
        }
    }

    if (showEntryDialog) {
        BankDepositDialog(
            members = members,
            existing = editingDeposit,
            onDismiss = {
                showEntryDialog = false
                editingDeposit = null
            },
            onSave = { member, amount, dateText ->
                val current = editingDeposit
                if (current == null) {
                    viewModel.addBankDeposit(member.id, member.name, amount, dateText)
                } else {
                    // Comment: Keep the row's identity (id + syncKey) so the edit updates the same ledger entry
                    viewModel.updateBankDeposit(
                        current.copy(
                            amount = amount,
                            dateText = dateText,
                            depositedById = member.id,
                            depositedByName = member.name
                        )
                    )
                }
                showEntryDialog = false
                editingDeposit = null
            }
        )
    }

    BankDepositDeleteDialog(
        deposit = depositPendingDelete,
        onDismiss = { depositPendingDelete = null },
        onConfirm = { deposit ->
            viewModel.deleteBankDeposit(deposit)
            depositPendingDelete = null
        }
    )
}

/**
 * Confirmation dialog shown before a bank deposit is removed, so an accidental tap cannot change Cash in Hand.
 */
@Composable
private fun BankDepositDeleteDialog(
    deposit: BankDeposit?,
    onDismiss: () -> Unit,
    onConfirm: (BankDeposit) -> Unit
) {
    if (deposit == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete deposit?") },
        text = {
            Text(
                "This removes the " + String.format(Locale.US, "%,.0f৳", deposit.amount) +
                    " deposit recorded on " + formatToDdMmYyyy(deposit.dateText) +
                    ". Cash in Hand will increase by that amount."
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(deposit) }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * One ledger row: serial number, depositor, date, and amount, with admin-only edit/delete actions.
 */
@Composable
private fun BankDepositRow(
    serialText: String,
    deposit: BankDeposit,
    isAdmin: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
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
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Comment: Plain 1. 2. 3. serial in the same black as the depositor name, replacing the old green "#001" badge
                Text(
                    text = serialText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.widthIn(min = 24.dp)
                )
                Column {
                    Text(
                        text = deposit.depositedByName.ifBlank { "Unknown depositor" },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        maxLines = 1
                    )
                    Text(
                        text = formatToDdMmYyyy(deposit.dateText),
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.outline)
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = String.format(Locale.US, "%,.0f৳", deposit.amount),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0C9488)
                    )
                )
                if (isAdmin) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("edit_bank_deposit")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit deposit",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("delete_bank_deposit")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete deposit",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shared summary card matching the Members Dashboard total cards (neutral surface, subtle border, accented value).
 */
@Composable
private fun BankSummaryCard(
    label: String,
    valueText: String,
    icon: ImageVector,
    accent: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
    isDarkMode: Boolean
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isDarkMode) Color(0xFF131B2E) else Color.White
        ),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = accent,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
            )
        }
    }
}

/**
 * Add/edit dialog for a bank deposit: depositor picked from the member list, a DD-MM-YYYY date, and a positive amount.
 */
@Composable
private fun BankDepositDialog(
    members: List<Member>,
    existing: BankDeposit?,
    onDismiss: () -> Unit,
    onSave: (Member, Double, String) -> Unit
) {
    val context = LocalContext.current

    // Comment: Sorted alphabetically so the depositor picker is easy to scan, matching the Admin Panel's member dropdown
    val sortedMembers = remember(members) {
        members.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    var selectedMemberId by remember { mutableStateOf(existing?.depositedById ?: -1) }
    var amountText by remember {
        mutableStateOf(existing?.amount?.let { String.format(Locale.US, "%.0f", it) } ?: "")
    }
    var dateText by remember {
        mutableStateOf(existing?.dateText ?: SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date()))
    }
    var isMemberMenuOpen by remember { mutableStateOf(false) }

    val selectedMember = sortedMembers.find { it.id == selectedMemberId }
    val parsedAmount = amountText.trim().toDoubleOrNull()
    val canSave = selectedMember != null && parsedAmount != null && parsedAmount > 0

    val datePickerDialog = remember {
        val calendar = Calendar.getInstance()
        DatePickerDialog(
            context,
            { _, selectedYear, selectedMonth, selectedDayOfMonth ->
                dateText = String.format(
                    Locale.US,
                    "%02d-%02d-%04d",
                    selectedDayOfMonth,
                    selectedMonth + 1,
                    selectedYear
                )
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .testTag("bank_deposit_dialog"),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (existing == null) "Record Bank Deposit" else "Edit Bank Deposit",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )

                // Depositor picker
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { isMemberMenuOpen = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = selectedMember?.name ?: "Select depositor",
                            maxLines = 1
                        )
                    }
                    DropdownMenu(
                        expanded = isMemberMenuOpen,
                        onDismissRequest = { isMemberMenuOpen = false },
                        modifier = Modifier.heightIn(max = 280.dp)
                    ) {
                        sortedMembers.forEach { member ->
                            DropdownMenuItem(
                                text = { Text(member.name) },
                                onClick = {
                                    selectedMemberId = member.id
                                    isMemberMenuOpen = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount (৳)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bank_deposit_amount")
                )

                OutlinedButton(
                    onClick = { datePickerDialog.show() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bank_deposit_date")
                ) {
                    Text("Deposit date: $dateText")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val member = selectedMember
                            val amount = parsedAmount
                            if (member != null && amount != null && amount > 0) {
                                onSave(member, amount, dateText)
                            }
                        },
                        enabled = canSave,
                        modifier = Modifier.testTag("save_bank_deposit")
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
