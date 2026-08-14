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
import com.example.util.escapeCsv
import com.example.util.formatToDdMmYyyy
import com.example.util.getCurrentCycleRange
import com.example.util.parseDateTextToMillis
import java.text.SimpleDateFormat
import java.util.*

/**
 * Admin Panel Composable (Admin tab)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(viewModel: SavingsViewModel) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val members by viewModel.allMembers.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val isDarkMode = appSettings.isDarkMode
    // Retrieve all savings from the ViewModel to render full savings histories
    val allSavings by viewModel.allSavings.collectAsStateWithLifecycle()

    // Flag to toggle showing the list of individual member names
    var isIndividualStatementsOpen by remember { mutableStateOf(false) }
    // State to determine if we are exporting the entire ledger or a single member statement
    var pdfExportType by remember { mutableStateOf("FullLedger") } // "FullLedger" or "Individual"
    // Reference to the selected member for generating individual statement PDFs
    var selectedMemberForPdf by remember { mutableStateOf<Member?>(null) }

    // Duration options for ledger and individual statements filtering
    var selectedDuration by remember { mutableStateOf("All Time") }
    var customStartDateText by remember { mutableStateOf("") }
    var customEndDateText by remember { mutableStateOf("") }
    var customStartMillis by remember { mutableStateOf<Long?>(null) }
    var customEndMillis by remember { mutableStateOf<Long?>(null) }

    val formatSdf = remember { SimpleDateFormat("dd-MM-yyyy", Locale.US) }

    val calendarStart = remember { Calendar.getInstance() }
    val startDatePickerDialog = remember {
        android.app.DatePickerDialog(
            context,
            { _, selectedYear, selectedMonth, selectedDayOfMonth ->
                val cal = Calendar.getInstance()
                cal.set(selectedYear, selectedMonth, selectedDayOfMonth, 0, 0, 0)
                cal.set(Calendar.MILLISECOND, 0)
                customStartMillis = cal.timeInMillis
                customStartDateText = formatSdf.format(cal.time)
            },
            calendarStart.get(Calendar.YEAR),
            calendarStart.get(Calendar.MONTH),
            calendarStart.get(Calendar.DAY_OF_MONTH)
        )
    }

    val calendarEnd = remember { Calendar.getInstance() }
    val endDatePickerDialog = remember {
        android.app.DatePickerDialog(
            context,
            { _, selectedYear, selectedMonth, selectedDayOfMonth ->
                val cal = Calendar.getInstance()
                cal.set(selectedYear, selectedMonth, selectedDayOfMonth, 23, 59, 59)
                cal.set(Calendar.MILLISECOND, 999)
                customEndMillis = cal.timeInMillis
                customEndDateText = formatSdf.format(cal.time)
            },
            calendarEnd.get(Calendar.YEAR),
            calendarEnd.get(Calendar.MONTH),
            calendarEnd.get(Calendar.DAY_OF_MONTH)
        )
    }

    // Helper function to calculate timestamp range based on the selected duration option
    fun getDurationTimestampRange(
        option: String,
        customStart: Long?,
        customEnd: Long?
    ): Pair<Long, Long> {
        val now = Calendar.getInstance()
        when (option) {
            "All Time" -> {
                return Pair(0L, Long.MAX_VALUE)
            }
            "This Week" -> {
                val cal = Calendar.getInstance()
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                return Pair(cal.timeInMillis, Long.MAX_VALUE)
            }
            "This Month" -> {
                val cal = Calendar.getInstance()
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                return Pair(cal.timeInMillis, Long.MAX_VALUE)
            }
            "Last Month" -> {
                val calStart = Calendar.getInstance()
                calStart.add(Calendar.MONTH, -1)
                calStart.set(Calendar.DAY_OF_MONTH, 1)
                calStart.set(Calendar.HOUR_OF_DAY, 0)
                calStart.set(Calendar.MINUTE, 0)
                calStart.set(Calendar.SECOND, 0)
                calStart.set(Calendar.MILLISECOND, 0)

                val calEnd = Calendar.getInstance()
                calEnd.set(Calendar.DAY_OF_MONTH, 1)
                calEnd.set(Calendar.HOUR_OF_DAY, 0)
                calEnd.set(Calendar.MINUTE, 0)
                calEnd.set(Calendar.SECOND, 0)
                calEnd.set(Calendar.MILLISECOND, 0)

                return Pair(calStart.timeInMillis, calEnd.timeInMillis - 1)
            }
            "Last 3 Months" -> {
                val cal = Calendar.getInstance()
                cal.add(Calendar.MONTH, -3)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                return Pair(cal.timeInMillis, Long.MAX_VALUE)
            }
            "Last 6 Months" -> {
                val cal = Calendar.getInstance()
                cal.add(Calendar.MONTH, -6)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                return Pair(cal.timeInMillis, Long.MAX_VALUE)
            }
            "Last 12 Months" -> {
                val cal = Calendar.getInstance()
                cal.add(Calendar.MONTH, -12)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                return Pair(cal.timeInMillis, Long.MAX_VALUE)
            }
            "Custom" -> {
                val start = customStart ?: 0L
                val end = if (customEnd != null) {
                    val cal = Calendar.getInstance()
                    cal.timeInMillis = customEnd
                    cal.set(Calendar.HOUR_OF_DAY, 23)
                    cal.set(Calendar.MINUTE, 59)
                    cal.set(Calendar.SECOND, 59)
                    cal.set(Calendar.MILLISECOND, 999)
                    cal.timeInMillis
                } else {
                    Long.MAX_VALUE
                }
                return Pair(start, end)
            }
            else -> return Pair(0L, Long.MAX_VALUE)
        }
    }

    // Temporary variables to support exporting CSV data to internal storage or share sheet
    var pendingCsvContent by remember { mutableStateOf("") }
    var showExportOptionDialog by remember { mutableStateOf(false) }

    // Activity launcher for creating and saving the CSV document natively using SAF (Storage Access Framework)
    val createCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(pendingCsvContent.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "Data saved successfully to Phone Storage!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Failed to save file: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Activity launcher for creating and saving the PDF document natively using SAF (Storage Access Framework)
    val contentResolver = context.contentResolver
    val createPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    // Initialize the Android PDF document
                    val pdfDocument = PdfDocument()

                    if (pdfExportType == "FullLedger") {
                        // Filter savings and sum up each member's contributions for the selected duration period
                        val (startT, endT) = getDurationTimestampRange(selectedDuration, customStartMillis, customEndMillis)
                        val memberSavingsMap = allSavings
                            .filter { it.timestamp in startT..endT }
                            .groupBy { it.memberId }

                        val sortedMembers = members.map { m ->
                            val durationSavings = memberSavingsMap[m.id]?.sumOf { it.amount } ?: 0.0
                            m.copy(totalSavings = durationSavings)
                        }.sortedBy { it.name }

                        var pageNumber = 1
                        var pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create() // A4 Size: 595 x 842 points
                        var page = pdfDocument.startPage(pageInfo)
                        var canvas = page.canvas

                        // Helper to draw a consistent and elegant header on each page of the ledger
                        fun drawPageHeader(canvas: android.graphics.Canvas, pageNum: Int) {
                            // Title paint (Primary Teal color)
                            val titlePaint = Paint().apply {
                                color = android.graphics.Color.parseColor("#0C9488")
                                textSize = 18f
                                isFakeBoldText = true
                            }
                            // Title text updated to reflect Swanirvor-23 app identity
                            canvas.drawText("Swanirvor-23 Savings Society", 40f, 50f, titlePaint)

                            // Subtitle paint with metadata
                            val subtitlePaint = Paint().apply {
                                color = android.graphics.Color.DKGRAY
                                textSize = 9f
                            }
                            val dateStr = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US).format(Date())
                            val durationText = when (selectedDuration) {
                                "Custom" -> {
                                    val startStr = if (customStartDateText.isEmpty()) "Beginning" else customStartDateText
                                    val endStr = if (customEndDateText.isEmpty()) "Present" else customEndDateText
                                    "Range: $startStr to $endStr"
                                }
                                "All Time" -> "All Time"
                                else -> selectedDuration
                            }
                            canvas.drawText("Full Society Ledger ($durationText) • Generated on $dateStr • Page $pageNum", 40f, 70f, subtitlePaint)

                            // Separator line
                            val linePaint = Paint().apply {
                                color = android.graphics.Color.parseColor("#0C9488")
                                strokeWidth = 2f
                                style = Paint.Style.STROKE
                            }
                            canvas.drawLine(40f, 80f, 555f, 80f, linePaint)

                            // Column headers background band
                            val headerBgPaint = Paint().apply {
                                color = android.graphics.Color.parseColor("#F3F4F6")
                                style = Paint.Style.FILL
                            }
                            canvas.drawRect(40f, 100f, 555f, 125f, headerBgPaint)

                            // Column headers text label styles
                            val headerTextPaint = Paint().apply {
                                color = android.graphics.Color.BLACK
                                textSize = 10f
                                isFakeBoldText = true
                            }
                            canvas.drawText("Serial No.", 45f, 117f, headerTextPaint)
                            canvas.drawText("Member Name", 105f, 117f, headerTextPaint)
                            // Header label changed to Membership No. per user request
                            canvas.drawText("Membership No.", 305f, 117f, headerTextPaint)
                            canvas.drawText("Total Savings", 420f, 117f, headerTextPaint)
                        }

                        // Render the first page header
                        drawPageHeader(canvas, pageNumber)

                        // Line divider for records
                        val rowLinePaint = Paint().apply {
                            color = android.graphics.Color.parseColor("#E5E7EB")
                            style = Paint.Style.STROKE
                            strokeWidth = 1f
                        }
                        val rowTextPaint = Paint().apply {
                            color = android.graphics.Color.BLACK
                            textSize = 10f
                        }

                        var currentY = 145f
                        val rowHeight = 25f
                        val pageBottomLimit = 800f

                        // Write each member row onto the ledger
                        for ((index, m) in sortedMembers.withIndex()) {
                            // Check if we need to paginate to a new page
                            if (currentY + rowHeight > pageBottomLimit) {
                                pdfDocument.finishPage(page)

                                pageNumber++
                                pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                                page = pdfDocument.startPage(pageInfo)
                                canvas = page.canvas

                                drawPageHeader(canvas, pageNumber)
                                currentY = 145f
                            }

                            // Zebra striping for enhanced visual scanning
                            if (index % 2 == 1) {
                                val rowBgPaint = Paint().apply {
                                    color = android.graphics.Color.parseColor("#F9FAFB")
                                    style = Paint.Style.FILL
                                }
                                canvas.drawRect(40f, currentY - 18f, 555f, currentY + 7f, rowBgPaint)
                            }

                            // Draw horizontal line below the row
                            canvas.drawLine(40f, currentY + 7f, 555f, currentY + 7f, rowLinePaint)

                            val serialNo = (index + 1).toString()

                            // Use customized appSettings membership No for current user, otherwise generate sequential IDs
                            // This ensures the custom Membership No. from Settings is displayed in the ledger
                            val membershipNo = if (m.id == viewModel.currentUserMemberId) {
                                appSettings.membershipNo
                            } else {
                                "LS-2023-${String.format(Locale.US, "%03d", m.id)}"
                            }
                            // Format total savings as integer per user preference
                            val totalSavingsStr = String.format(Locale.US, "%,.0f৳", m.totalSavings)

                            // Draw row columns
                            canvas.drawText(serialNo, 45f, currentY, rowTextPaint)
                            canvas.drawText(m.name, 105f, currentY, rowTextPaint)
                            canvas.drawText(membershipNo, 305f, currentY, rowTextPaint)
                            canvas.drawText(totalSavingsStr, 420f, currentY, rowTextPaint)

                            currentY += rowHeight
                        }

                        // Final grand total box placement
                        if (currentY + 40f > pageBottomLimit) {
                            pdfDocument.finishPage(page)
                            pageNumber++
                            pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                            page = pdfDocument.startPage(pageInfo)
                            canvas = page.canvas
                            drawPageHeader(canvas, pageNumber)
                            currentY = 145f
                        }

                        val totalSavingsAll = sortedMembers.sumOf { it.totalSavings }
                        val totalBgPaint = Paint().apply {
                            color = android.graphics.Color.parseColor("#E0F2FE") // Soft blue band
                            style = Paint.Style.FILL
                        }
                        canvas.drawRect(40f, currentY - 18f, 555f, currentY + 12f, totalBgPaint)

                        val totalTextPaint = Paint().apply {
                            color = android.graphics.Color.BLACK
                            textSize = 10f
                            isFakeBoldText = true
                        }
                        canvas.drawText("Total Members: ${sortedMembers.size}", 45f, currentY - 2f + 4f, totalTextPaint)
                        canvas.drawText("Grand Total Savings:", 305f, currentY - 2f + 4f, totalTextPaint)
                        // Format grand total as integer per user preference
                        canvas.drawText(String.format(Locale.US, "%,.0f৳", totalSavingsAll), 420f, currentY - 2f + 4f, totalTextPaint)

                        // Finish the main summary page
                        pdfDocument.finishPage(page)

                        // Render each member's individual detailed statement as subsequent pages of the Full Ledger report
                        for (member in sortedMembers) {
                            // Filter all contributions belonging to this member within the duration period and sort chronologically
                            val memberSavings = allSavings.filter { it.memberId == member.id && it.timestamp in startT..endT }
                                .sortedBy { it.timestamp }
                            val totalSavingsInPeriod = memberSavings.sumOf { it.amount }

                            pageNumber++
                            pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                            page = pdfDocument.startPage(pageInfo)
                            canvas = page.canvas

                            // Local helper to draw the individual header for full ledger's detail pages
                            fun drawFullLedgerIndividualHeader(cv: android.graphics.Canvas, pNum: Int) {
                                // Title paint (Primary Teal color)
                                val titlePaint = Paint().apply {
                                    color = android.graphics.Color.parseColor("#0C9488")
                                    textSize = 18f
                                    isFakeBoldText = true
                                }
                                cv.drawText("Swanirvor-23 Savings Society", 40f, 50f, titlePaint)

                                // Subtitle metadata block
                                val subtitlePaint = Paint().apply {
                                    color = android.graphics.Color.DKGRAY
                                    textSize = 9f
                                }
                                val dateStr = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US).format(Date())
                                val durationText = when (selectedDuration) {
                                    "Custom" -> {
                                        val startStr = if (customStartDateText.isEmpty()) "Beginning" else customStartDateText
                                        val endStr = if (customEndDateText.isEmpty()) "Present" else customEndDateText
                                        "Range: $startStr to $endStr"
                                    }
                                    "All Time" -> "All Time"
                                    else -> selectedDuration
                                }
                                cv.drawText("Individual Savings Statement ($durationText) • Generated on $dateStr • Page $pNum", 40f, 70f, subtitlePaint)

                                // Elegant separator line
                                val linePaint = Paint().apply {
                                    color = android.graphics.Color.parseColor("#0C9488")
                                    strokeWidth = 2f
                                    style = Paint.Style.STROKE
                                }
                                cv.drawLine(40f, 80f, 555f, 80f, linePaint)

                                // Member Profile Information container box
                                val infoBgPaint = Paint().apply {
                                    color = android.graphics.Color.parseColor("#F0FDF4") // soft green accent band
                                    style = Paint.Style.FILL
                                }
                                cv.drawRect(40f, 95f, 555f, 155f, infoBgPaint)

                                val labelPaint = Paint().apply {
                                    color = android.graphics.Color.BLACK
                                    textSize = 10f
                                    isFakeBoldText = true
                                }
                                val valPaint = Paint().apply {
                                    color = android.graphics.Color.BLACK
                                    textSize = 10f
                                }

                                val membershipId = if (member.id == viewModel.currentUserMemberId) {
                                    appSettings.membershipNo
                                } else {
                                    "LS-2023-${String.format(Locale.US, "%03d", member.id)}"
                                }

                                cv.drawText("Member Name:", 55f, 115f, labelPaint)
                                cv.drawText(member.name, 155f, 115f, valPaint)

                                cv.drawText("Membership No.:", 55f, 135f, labelPaint)
                                cv.drawText(membershipId, 155f, 135f, valPaint)

                                cv.drawText("Total Savings (Period):", 350f, 115f, labelPaint)
                                cv.drawText(String.format(Locale.US, "%,.0f৳", totalSavingsInPeriod), 450f, 115f, valPaint)

                                cv.drawText("Email Address:", 350f, 135f, labelPaint)
                                cv.drawText(member.email, 450f, 135f, valPaint)

                                // Contribution History Table headers background
                                val tblHeaderBgPaint = Paint().apply {
                                    color = android.graphics.Color.parseColor("#F3F4F6")
                                    style = Paint.Style.FILL
                                }
                                cv.drawRect(40f, 175f, 555f, 200f, tblHeaderBgPaint)

                                val tblHeaderTextPaint = Paint().apply {
                                    color = android.graphics.Color.BLACK
                                    textSize = 10f
                                    isFakeBoldText = true
                                }
                                cv.drawText("Serial No.", 45f, 192f, tblHeaderTextPaint)
                                cv.drawText("Contribution Date / Text", 120f, 192f, tblHeaderTextPaint)
                                cv.drawText("Amount", 420f, 192f, tblHeaderTextPaint)
                            }

                            // Render first page header & info card for this member's detail statement
                            drawFullLedgerIndividualHeader(canvas, pageNumber)

                            var individualY = 220f
                            val indRowHeight = 25f

                            // Write each contribution record for the individual member detail page
                            for ((indIndex, saving) in memberSavings.withIndex()) {
                                if (individualY + indRowHeight > pageBottomLimit) {
                                    pdfDocument.finishPage(page)
                                    pageNumber++
                                    pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                                    page = pdfDocument.startPage(pageInfo)
                                    canvas = page.canvas

                                    drawFullLedgerIndividualHeader(canvas, pageNumber)
                                    individualY = 220f
                                }

                                // Alternating rows highlighting
                                if (indIndex % 2 == 1) {
                                    val rowBgPaint = Paint().apply {
                                        color = android.graphics.Color.parseColor("#F9FAFB")
                                        style = Paint.Style.FILL
                                    }
                                    canvas.drawRect(40f, individualY - 18f, 555f, individualY + 7f, rowBgPaint)
                                }

                                canvas.drawLine(40f, individualY + 7f, 555f, individualY + 7f, rowLinePaint)

                                val serialNo = (indIndex + 1).toString()
                                val amountStr = String.format(Locale.US, "%,.0f৳", saving.amount)

                                canvas.drawText(serialNo, 45f, individualY, rowTextPaint)
                                canvas.drawText(saving.dateText, 120f, individualY, rowTextPaint)
                                canvas.drawText(amountStr, 420f, individualY, rowTextPaint)

                                individualY += indRowHeight
                            }

                            // Render summary details box at bottom of individual member's statement
                            if (individualY + 40f > pageBottomLimit) {
                                pdfDocument.finishPage(page)
                                pageNumber++
                                pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                                page = pdfDocument.startPage(pageInfo)
                                canvas = page.canvas
                                drawFullLedgerIndividualHeader(canvas, pageNumber)
                                individualY = 220f
                            }

                            val indTotalBgPaint = Paint().apply {
                                color = android.graphics.Color.parseColor("#E0F2FE") // Soft blue band
                                style = Paint.Style.FILL
                            }
                            canvas.drawRect(40f, individualY - 18f, 555f, individualY + 12f, indTotalBgPaint)

                            val indTotalTextPaint = Paint().apply {
                                color = android.graphics.Color.BLACK
                                textSize = 10f
                                isFakeBoldText = true
                            }
                            canvas.drawText("Total Contributions: ${memberSavings.size}", 45f, individualY - 2f + 4f, indTotalTextPaint)
                            canvas.drawText("Sum of Savings:", 305f, individualY - 2f + 4f, indTotalTextPaint)
                            canvas.drawText(String.format(Locale.US, "%,.0f৳", totalSavingsInPeriod), 420f, individualY - 2f + 4f, indTotalTextPaint)

                            // Finish page for this member
                            pdfDocument.finishPage(page)
                        }

                        pdfDocument.writeTo(outputStream)
                        pdfDocument.close()
                        Toast.makeText(context, "Ledger PDF generated and saved successfully!", Toast.LENGTH_LONG).show()
                    } else {
                        // Individual statement PDF generation
                        val member = selectedMemberForPdf
                        if (member != null) {
                            // Filter all contributions belonging to the selected member within the duration period and sort chronologically
                            val (startT, endT) = getDurationTimestampRange(selectedDuration, customStartMillis, customEndMillis)
                            val memberSavings = allSavings.filter { it.memberId == member.id && it.timestamp in startT..endT }
                                .sortedBy { it.timestamp }
                            val totalSavingsInPeriod = memberSavings.sumOf { it.amount }

                            var pageNumber = 1
                            var pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create() // A4 Size: 595 x 842 points
                            var page = pdfDocument.startPage(pageInfo)
                            var canvas = page.canvas

                            // Helper to render the header and member metadata panel on each statement page
                            fun drawIndividualPageHeader(canvas: android.graphics.Canvas, pageNum: Int) {
                                // Title paint (Primary Teal color)
                                val titlePaint = Paint().apply {
                                    color = android.graphics.Color.parseColor("#0C9488")
                                    textSize = 18f
                                    isFakeBoldText = true
                                }
                                canvas.drawText("Swanirvor-23 Savings Society", 40f, 50f, titlePaint)

                                // Subtitle metadata block
                                val subtitlePaint = Paint().apply {
                                    color = android.graphics.Color.DKGRAY
                                    textSize = 9f
                                }
                                val dateStr = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US).format(Date())
                                val durationText = when (selectedDuration) {
                                    "Custom" -> {
                                        val startStr = if (customStartDateText.isEmpty()) "Beginning" else customStartDateText
                                        val endStr = if (customEndDateText.isEmpty()) "Present" else customEndDateText
                                        "Range: $startStr to $endStr"
                                    }
                                    "All Time" -> "All Time"
                                    else -> selectedDuration
                                }
                                canvas.drawText("Individual Savings Statement ($durationText) • Generated on $dateStr • Page $pageNum", 40f, 70f, subtitlePaint)

                                // Elegant separator line
                                val linePaint = Paint().apply {
                                    color = android.graphics.Color.parseColor("#0C9488")
                                    strokeWidth = 2f
                                    style = Paint.Style.STROKE
                                }
                                canvas.drawLine(40f, 80f, 555f, 80f, linePaint)

                                // Member Profile Information container box
                                val infoBgPaint = Paint().apply {
                                    color = android.graphics.Color.parseColor("#F0FDF4") // soft green accent band
                                    style = Paint.Style.FILL
                                }
                                canvas.drawRect(40f, 95f, 555f, 155f, infoBgPaint)

                                val labelPaint = Paint().apply {
                                    color = android.graphics.Color.BLACK
                                    textSize = 10f
                                    isFakeBoldText = true
                                }
                                val valPaint = Paint().apply {
                                    color = android.graphics.Color.BLACK
                                    textSize = 10f
                                }

                                val membershipId = if (member.id == viewModel.currentUserMemberId) {
                                    appSettings.membershipNo
                                } else {
                                    "LS-2023-${String.format(Locale.US, "%03d", member.id)}"
                                }

                                canvas.drawText("Member Name:", 55f, 115f, labelPaint)
                                canvas.drawText(member.name, 155f, 115f, valPaint)

                                canvas.drawText("Membership No.:", 55f, 135f, labelPaint)
                                canvas.drawText(membershipId, 155f, 135f, valPaint)

                                canvas.drawText("Total Savings (Period):", 350f, 115f, labelPaint)
                                // Format total savings as integer per user preference
                                canvas.drawText(String.format(Locale.US, "%,.0f৳", totalSavingsInPeriod), 450f, 115f, valPaint)

                                canvas.drawText("Email Address:", 350f, 135f, labelPaint)
                                canvas.drawText(member.email, 450f, 135f, valPaint)

                                // Contribution History Table headers background
                                val headerBgPaint = Paint().apply {
                                    color = android.graphics.Color.parseColor("#F3F4F6")
                                    style = Paint.Style.FILL
                                }
                                canvas.drawRect(40f, 175f, 555f, 200f, headerBgPaint)

                                val headerTextPaint = Paint().apply {
                                    color = android.graphics.Color.BLACK
                                    textSize = 10f
                                    isFakeBoldText = true
                                }
                                canvas.drawText("Serial No.", 45f, 192f, headerTextPaint)
                                canvas.drawText("Contribution Date / Text", 120f, 192f, headerTextPaint)
                                canvas.drawText("Amount", 420f, 192f, headerTextPaint)
                            }

                            // Render first page header & info card
                            drawIndividualPageHeader(canvas, pageNumber)

                            val rowLinePaint = Paint().apply {
                                color = android.graphics.Color.parseColor("#E5E7EB")
                                style = Paint.Style.STROKE
                                strokeWidth = 1f
                            }
                            val rowTextPaint = Paint().apply {
                                color = android.graphics.Color.BLACK
                                textSize = 10f
                            }

                            var currentY = 220f
                            val rowHeight = 25f
                            val pageBottomLimit = 800f

                            // Write each contribution record
                            for ((index, saving) in memberSavings.withIndex()) {
                                if (currentY + rowHeight > pageBottomLimit) {
                                    pdfDocument.finishPage(page)
                                    pageNumber++
                                    pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                                    page = pdfDocument.startPage(pageInfo)
                                    canvas = page.canvas

                                    drawIndividualPageHeader(canvas, pageNumber)
                                    currentY = 220f
                                }

                                // Alternating rows highlighting
                                if (index % 2 == 1) {
                                    val rowBgPaint = Paint().apply {
                                        color = android.graphics.Color.parseColor("#F9FAFB")
                                        style = Paint.Style.FILL
                                    }
                                    canvas.drawRect(40f, currentY - 18f, 555f, currentY + 7f, rowBgPaint)
                                }

                                canvas.drawLine(40f, currentY + 7f, 555f, currentY + 7f, rowLinePaint)

                                val serialNo = (index + 1).toString()
                                // Format saving amount as integer per user preference
                                val amountStr = String.format(Locale.US, "%,.0f৳", saving.amount)

                                canvas.drawText(serialNo, 45f, currentY, rowTextPaint)
                                canvas.drawText(saving.dateText, 120f, currentY, rowTextPaint)
                                canvas.drawText(amountStr, 420f, currentY, rowTextPaint)

                                currentY += rowHeight
                            }

                            // Render summary details box at bottom
                            if (currentY + 40f > pageBottomLimit) {
                                pdfDocument.finishPage(page)
                                pageNumber++
                                pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                                page = pdfDocument.startPage(pageInfo)
                                canvas = page.canvas
                                drawIndividualPageHeader(canvas, pageNumber)
                                currentY = 220f
                            }

                            val totalBgPaint = Paint().apply {
                                color = android.graphics.Color.parseColor("#E0F2FE") // Soft blue band
                                style = Paint.Style.FILL
                            }
                            canvas.drawRect(40f, currentY - 18f, 555f, currentY + 12f, totalBgPaint)

                            val totalTextPaint = Paint().apply {
                                color = android.graphics.Color.BLACK
                                textSize = 10f
                                isFakeBoldText = true
                            }
                            canvas.drawText("Total Contributions: ${memberSavings.size}", 45f, currentY - 2f + 4f, totalTextPaint)
                            canvas.drawText("Sum of Savings:", 305f, currentY - 2f + 4f, totalTextPaint)
                            // Format sum of savings as integer per user preference
                            canvas.drawText(String.format(Locale.US, "%,.0f৳", totalSavingsInPeriod), 420f, currentY - 2f + 4f, totalTextPaint)

                            pdfDocument.finishPage(page)
                            pdfDocument.writeTo(outputStream)
                            pdfDocument.close()
                            Toast.makeText(context, "${member.name}'s savings statement generated successfully!", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Error saving PDF: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    var showMemberDropdown by remember { mutableStateOf(false) }
    val selectedMember by viewModel.selectedMemberForContribution.collectAsStateWithLifecycle()
    // Initialize Weekly Amount to empty string per user request
    var weeklyAmountText by remember { mutableStateOf("") }

    val todayDateString = remember {
        val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.US)
        sdf.format(Date())
    }
    var contributionDateText by remember { mutableStateOf(todayDateString) }

    val calendar = remember { Calendar.getInstance() }
    val datePickerDialog = remember {
        android.app.DatePickerDialog(
            context,
            { _, selectedYear, selectedMonth, selectedDayOfMonth ->
                val formattedDate = String.format(Locale.US, "%02d-%02d-%04d", selectedDayOfMonth, selectedMonth + 1, selectedYear)
                contributionDateText = formattedDate
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    // Active cycle Sunday to Saturday date/timestamp range and status calculations
    val (currentSunday, currentSaturday) = getCurrentCycleRange()

    // Comment: Format the Starts (this week's Sunday) and Ends (this week's Saturday) dates directly from the robust currentSunday and currentSaturday timestamps
    val activeCycleStartsText = remember(currentSunday) {
        SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date(currentSunday))
    }
    val activeCycleEndsText = remember(currentSaturday) {
        SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date(currentSaturday))
    }
    val paidMemberIds = members.filter { m ->
        allSavings.any { s ->
            s.memberId == m.id &&
                    (if (s.timestamp > 0) s.timestamp else parseDateTextToMillis(s.dateText)) in currentSunday..currentSaturday
        }
    }.map { it.id }.toSet()

    val paidMembers = members.filter { paidMemberIds.contains(it.id) }
    val unpaidMembers = members.filter { !paidMemberIds.contains(it.id) }

    val paidMembersCount = paidMembers.size
    val unpaidMembersCount = unpaidMembers.size
    val participationRate = if (members.isNotEmpty()) (paidMembersCount.toFloat() / members.size * 100).toInt() else 0

    var selectedActiveCycleTab by remember { mutableStateOf("Unpaid") }

    // Comment: Track the single open Admin Panel accordion section (null = all closed); opening one closes the others
    val adminOpenSection by viewModel.adminOpenSection.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- SECTION 1: Member Contribution Accordion ---
        AccordionCard(
            title = "Member Contribution",
            icon = Icons.Default.Payments,
            isOpen = adminOpenSection == SavingsViewModel.SECTION_CONTRIBUTION,
            onToggle = { viewModel.toggleAdminSection(SavingsViewModel.SECTION_CONTRIBUTION) },
            containerColor = if (isDarkMode) Color(0xFF131B2E) else Color.White
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Dropdown Member Selector
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Select Member",
                        style = MaterialTheme.typography.labelLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                    ExposedDropdownMenuBox(
                        expanded = showMemberDropdown,
                        onExpandedChange = { showMemberDropdown = !showMemberDropdown }
                    ) {
                        OutlinedTextField(
                            value = selectedMember?.name ?: "Choose a member...",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showMemberDropdown) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                                .testTag("select_member_input"),
                            shape = RoundedCornerShape(8.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = showMemberDropdown,
                            onDismissRequest = { showMemberDropdown = false }
                        ) {
                            members.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m.name) },
                                    onClick = {
                                        viewModel.selectMemberForContribution(m)
                                        showMemberDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Weekly Amount Input
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Weekly Amount",
                        style = MaterialTheme.typography.labelLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                    // Removed the 0.00 placeholder and kept the taka symbol per user request
                    OutlinedTextField(
                        value = weeklyAmountText,
                        onValueChange = { weeklyAmountText = it },
                        trailingIcon = { Text("৳", fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("contribution_amount_input"),
                        shape = RoundedCornerShape(8.dp)
                    )
                }

                // Date Input
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Date",
                        style = MaterialTheme.typography.labelLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = contributionDateText,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = {
                                IconButton(onClick = { datePickerDialog.show() }) {
                                    Icon(
                                        imageVector = Icons.Default.CalendarToday,
                                        contentDescription = "Select Date"
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("contribution_date_input"),
                            shape = RoundedCornerShape(8.dp)
                        )
                        // Invisible overlay to capture click events on the entire text field area
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable { datePickerDialog.show() }
                        )
                    }
                }

                // Save / Clear Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            val targetMember = selectedMember
                            val amt = weeklyAmountText.toDoubleOrNull()
                            if (targetMember != null && amt != null && amt > 0) {
                                // Reformat date or keep as string
                                viewModel.addSavingsContribution(
                                    memberId = targetMember.id,
                                    memberName = targetMember.name,
                                    amount = amt,
                                    dateText = contributionDateText
                                )
                                Toast.makeText(context, "Saved contribution of $amt৳ for ${targetMember.name}", Toast.LENGTH_LONG).show()
                                // Clear selection
                                viewModel.selectMemberForContribution(null)
                                // Reset to empty string per user request
                                weeklyAmountText = ""
                            } else {
                                Toast.makeText(context, "Please select a member and enter a valid amount", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("contribution_save_button")
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Save, contentDescription = "Save", modifier = Modifier.size(18.dp))
                            Text("Save")
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            viewModel.selectMemberForContribution(null)
                            weeklyAmountText = ""
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.RestartAlt, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                            Text("Clear")
                        }
                    }
                }
            }
        }

        // --- SECTION 2: Active Cycle Accordion ---
        AccordionCard(
            title = "This Week's Payment",
            icon = Icons.Default.Update,
            isOpen = adminOpenSection == SavingsViewModel.SECTION_ACTIVE_CYCLE,
            onToggle = { viewModel.toggleAdminSection(SavingsViewModel.SECTION_ACTIVE_CYCLE) },
            containerColor = if (isDarkMode) Color(0xFF131B2E) else Color.White
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Payment Status",
                    style = MaterialTheme.typography.labelLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )

                // Paid / Unpaid Status Tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Comment: Style the Paid button to have high contrast solid teal background when selected, and white/dark navy background when unselected
                    val isPaidSelected = selectedActiveCycleTab == "Paid"
                    Button(
                        onClick = { selectedActiveCycleTab = "Paid" },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPaidSelected) Color(0xFF0C9488) else if (isDarkMode) Color(0xFF213145) else Color.White,
                            contentColor = if (isPaidSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isPaidSelected) Color(0xFF0C9488) else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Paid count",
                                tint = if (isPaidSelected) Color.White else Color(0xFF0C9488),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Paid: $paidMembersCount",
                                color = if (isPaidSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (isPaidSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }

                    // Comment: Style the Unpaid button to have high contrast solid error red background when selected, and white/dark navy background when unselected
                    val isUnpaidSelected = selectedActiveCycleTab == "Unpaid"
                    Button(
                        onClick = { selectedActiveCycleTab = "Unpaid" },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isUnpaidSelected) MaterialTheme.colorScheme.error else if (isDarkMode) Color(0xFF213145) else Color.White,
                            contentColor = if (isUnpaidSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isUnpaidSelected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = "Unpaid count",
                                tint = if (isUnpaidSelected) Color.White else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Unpaid: $unpaidMembersCount",
                                color = if (isUnpaidSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (isUnpaidSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                // Progress Bar
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Disable the default Material 3 stop indicator (green dot/tint on the right side) per user request
                    LinearProgressIndicator(
                        progress = { participationRate / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = Color(0xFF0C9488),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        drawStopIndicator = {}
                    )
                    Text(
                        text = "$participationRate% PARTICIPATION",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }

                // Paid / Unpaid members list
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                val displayedMembers = if (selectedActiveCycleTab == "Paid") {
                    paidMembers
                } else {
                    unpaidMembers
                }

                if (displayedMembers.isEmpty()) {
                    Text(
                        text = "No members under this tab",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.outline),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    Column(
                        modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        displayedMembers.forEach { m ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (selectedActiveCycleTab == "Unpaid") Color(0xFFFEDAD6).copy(
                                            alpha = 0.2f
                                        ) else MaterialTheme.colorScheme.surfaceContainerLow,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    MemberAvatar(name = m.name, avatarUrl = m.avatarUrl, size = 32.dp)
                                    Text(m.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                                }

                                if (selectedActiveCycleTab == "Unpaid") {
                                    IconButton(
                                        onClick = {
                                            // Comment: Update database to set receivedAdminNotification = true for targeted member reminder
                                            viewModel.updateMember(m.copy(receivedAdminNotification = true))
                                            Toast.makeText(context, "Notification reminder sent to ${m.name}!", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Notifications,
                                            contentDescription = "Send notification",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                // Add comment: Show the weekly amount paid on the right side of the member name if they are in the Paid list tab
                                if (selectedActiveCycleTab == "Paid") {
                                    val memberSavingsThisWeek = allSavings.filter { s ->
                                        s.memberId == m.id &&
                                                (if (s.timestamp > 0) s.timestamp else parseDateTextToMillis(s.dateText)) in currentSunday..currentSaturday
                                    }
                                    val totalWeeklyAmount = memberSavingsThisWeek.sumOf { it.amount }
                                    Text(
                                        text = String.format(Locale.US, "%,.0f৳", totalWeeklyAmount),
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF0C9488)
                                        ),
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Cycle Start / End Details
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("STARTS", style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.outline))
                        Text(activeCycleStartsText, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("ENDS", style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.outline))
                        Text(activeCycleEndsText, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                    }
                }
            }
        }

        // --- NEW SECTION: Change Requests Accordion (Removed 'Member' from title and updated icon for distinct appearance) ---
        val changeRequests by viewModel.allChangeRequests.collectAsStateWithLifecycle()
        val pendingRequests = changeRequests.filter { it.status == "Pending" }

        AccordionCard(
            title = "Change Requests (${pendingRequests.size})",
            icon = Icons.Default.PendingActions,
            isOpen = adminOpenSection == SavingsViewModel.SECTION_CHANGE_REQUESTS,
            onToggle = { viewModel.toggleAdminSection(SavingsViewModel.SECTION_CHANGE_REQUESTS) },
            containerColor = if (isDarkMode) Color(0xFF131B2E) else Color.White,
            // Comment: Highlight the Change Request button and section outline with a red accent whenever any change request exists
            accentColor = if (changeRequests.isNotEmpty()) Color(0xFFD32F2F) else null
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (pendingRequests.isEmpty()) {
                    Text(
                        "No pending change requests.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.outline)
                    )
                } else {
                    pendingRequests.forEach { req ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (req.requestType == "RemoveMember") {
                                            "Remove User: ${req.memberName}"
                                        } else if (req.requestType == "MakeAdmin") {
                                            "Make Admin: ${req.memberName}"
                                        } else if (req.requestType == "RemoveAdmin") {
                                            "Remove Admin: ${req.memberName}"
                                        } else {
                                            "${req.memberName} (${req.requestType})"
                                        },
                                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    AssistChip(
                                        onClick = {},
                                        // Comment: Pending status badge text size reduced by 40% (fontSize = 8.sp) and forced single-line to prevent wrapping
                                        label = {
                                            Text(
                                                text = "PENDING",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    )
                                }

                                Text(
                                    text = if (req.requestType == "RemoveMember") {
                                        "Requested to permanently remove user/member '${req.memberName}' from the app."
                                    } else if (req.requestType == "MakeAdmin") {
                                        "Requested to promote user '${req.memberName}' to the Admin role."
                                    } else if (req.requestType == "RemoveAdmin") {
                                        "Requested to remove Admin role from user '${req.memberName}' (demote to Member)."
                                    } else if (req.requestType == "Delete") {
                                        "Requested to delete contribution of ${req.originalAmount.toInt()}৳ from ${formatToDdMmYyyy(req.originalDateText)}."
                                    } else {
                                        "Requested to change contribution on ${formatToDdMmYyyy(req.originalDateText)} from ${req.originalAmount.toInt()}৳ to ${req.newAmount?.toInt()}৳ (${req.newDateText?.let { formatToDdMmYyyy(it) } ?: ""})."
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )

                                // Handling RemoveMember, MakeAdmin, and RemoveAdmin with multi-admin confirmation per user request
                                if (req.requestType == "RemoveMember" || req.requestType == "MakeAdmin" || req.requestType == "RemoveAdmin") {
                                    val allAdmins = members.filter { it.role == "Admin" }
                                    val confirmedAdminIds = req.newDateText?.split(",")
                                        ?.filter { it.isNotEmpty() }
                                        ?.mapNotNull { it.toIntOrNull() } ?: emptyList()

                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "Admin Confirmations (${confirmedAdminIds.size} of ${allAdmins.size}):",
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                                        )
                                        allAdmins.forEach { admin ->
                                            val isConfirmed = confirmedAdminIds.contains(admin.id)
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = if (isConfirmed) "Confirmed" else "Not Confirmed",
                                                    tint = if (isConfirmed) Color(0xFF0C9488) else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = admin.name,
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        color = if (isConfirmed) Color(0xFF0C9488) else MaterialTheme.colorScheme.onSurface
                                                    )
                                                )
                                            }
                                        }

                                        // Add comment: Render Approve and Reject buttons for the currently logged-in Admin instead of buttons for all admins
                                        val currentAdmin = members.find { it.id == viewModel.currentUserMemberId }
                                        val hasApproved = currentAdmin != null && confirmedAdminIds.contains(currentAdmin.id)

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    if (currentAdmin != null) {
                                                        if (req.requestType == "RemoveMember") {
                                                            viewModel.confirmRemoveMemberAsAdmin(req, currentAdmin.id, allAdmins)
                                                        } else {
                                                            viewModel.confirmRoleChangeAsAdmin(req, currentAdmin.id, allAdmins)
                                                        }
                                                        Toast.makeText(context, "Approved request as ${currentAdmin.name}", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                enabled = !hasApproved && currentAdmin != null,
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C9488)),
                                                modifier = Modifier.weight(1.5f).testTag("approve_action_${req.id}")
                                            ) {
                                                Icon(Icons.Default.Check, contentDescription = "Approve", modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(if (hasApproved) "Approved" else "Approve")
                                            }

                                            OutlinedButton(
                                                onClick = {
                                                    viewModel.rejectChangeRequest(req)
                                                    Toast.makeText(context, "Rejected request for ${req.memberName}", Toast.LENGTH_SHORT).show()
                                                },
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                                modifier = Modifier.weight(1f).testTag("reject_action_${req.id}")
                                            ) {
                                                Icon(Icons.Default.Close, contentDescription = "Reject", modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Reject")
                                            }
                                        }
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                viewModel.approveChangeRequest(req)
                                                Toast.makeText(context, "Approved request for ${req.memberName}", Toast.LENGTH_SHORT).show()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C9488)),
                                            modifier = Modifier.weight(1.5f).testTag("approve_request_${req.id}")
                                        ) {
                                            Icon(Icons.Default.Check, contentDescription = "Approve", modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Approve")
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                viewModel.rejectChangeRequest(req)
                                                Toast.makeText(context, "Rejected request for ${req.memberName}", Toast.LENGTH_SHORT).show()
                                            },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                            modifier = Modifier.weight(1f).testTag("reject_request_${req.id}")
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Reject", modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Reject")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Comment: Section 3 (Automated Notification Accordion) has been relocated to the Settings screen as requested.

        // --- SECTION 4: Members Status Accordion Table ---
        AccordionCard(
            title = "Members Management",
            icon = Icons.Default.ManageAccounts,
            isOpen = adminOpenSection == SavingsViewModel.SECTION_MEMBERS,
            onToggle = { viewModel.toggleAdminSection(SavingsViewModel.SECTION_MEMBERS) },
            containerColor = if (isDarkMode) Color(0xFF131B2E) else Color.White
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                members.forEachIndexed { idx, m ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (idx % 2 == 0) {
                                    if (isDarkMode) Color(0xFF131B2E) else Color.White
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                }
                            )
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MemberAvatar(name = m.name, avatarUrl = m.avatarUrl, size = 32.dp)
                            Column {
                                Text(
                                    m.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    m.email,
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.outline)
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Role Badge (Admin / Member)
                            AssistChip(
                                onClick = { },
                                label = { Text(m.role.uppercase(Locale.ROOT)) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (m.role == "Admin") Color(0xFFFED488) else Color.Transparent
                                )
                            )

                            // 3-dot dropdown action button per user request
                            // Allows removing the user, making them admin, or removing admin role
                            var showMenu by remember(m.id) { mutableStateOf(false) }
                            Box {
                                IconButton(
                                    onClick = { showMenu = true },
                                    modifier = Modifier.size(32.dp).testTag("member_menu_${m.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Options",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Remove User") },
                                        onClick = {
                                            showMenu = false
                                            viewModel.requestRemoveMember(m)
                                            Toast.makeText(context, "Remove request generated for ${m.name}!", Toast.LENGTH_SHORT).show()
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Remove User",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    )
                                    if (m.role == "Member") {
                                        DropdownMenuItem(
                                            text = { Text("Make Admin") },
                                            onClick = {
                                                showMenu = false
                                                viewModel.requestChangeMemberRole(m, "Admin")
                                                Toast.makeText(context, "Promotion request generated for ${m.name}!", Toast.LENGTH_SHORT).show()
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.AdminPanelSettings,
                                                    contentDescription = "Make Admin",
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        )
                                    } else {
                                        DropdownMenuItem(
                                            text = { Text("Remove Admin Role") },
                                            onClick = {
                                                showMenu = false
                                                viewModel.requestChangeMemberRole(m, "Member")
                                                Toast.makeText(context, "Demotion request generated for ${m.name}!", Toast.LENGTH_SHORT).show()
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.Person,
                                                    contentDescription = "Remove Admin Role",
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- SECTION 5: Reports & Export Accordion ---
        AccordionCard(
            title = "Reports & Export",
            icon = Icons.Default.Description,
            isOpen = adminOpenSection == SavingsViewModel.SECTION_REPORTS,
            onToggle = { viewModel.toggleAdminSection(SavingsViewModel.SECTION_REPORTS) },
            containerColor = if (isDarkMode) Color(0xFF131B2E) else Color.White
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Generate comprehensive ledgers or individual contribution histories for auditing.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )

                // --- Duration Header and Selector ---
                Text(
                    text = "Duration",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.padding(top = 4.dp)
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    val durationOptions = listOf(
                        "All Time", "This Week", "This Month", "Last Month",
                        "Last 3 Months", "Last 6 Months", "Last 12 Months", "Custom"
                    )

                    // Symmetrical 2-column grid
                    durationOptions.chunked(2).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowItems.forEach { option ->
                                Box(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(36.dp)
                                            .clickable { selectedDuration = option }
                                            .testTag("duration_btn_${option.lowercase(Locale.US).replace(" ", "_")}"),
                                        shape = RoundedCornerShape(18.dp),
                                        color = if (selectedDuration == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        border = BorderStroke(
                                            1.dp,
                                            if (selectedDuration == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                        )
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            Text(
                                                text = option,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontWeight = if (selectedDuration == option) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (selectedDuration == option) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // If Custom option is selected, show calendar pickers
                if (selectedDuration == "Custom") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Start Date Button
                        OutlinedButton(
                            onClick = { startDatePickerDialog.show() },
                            modifier = Modifier.weight(1f).testTag("custom_start_date_button"),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "Start Date",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = if (customStartDateText.isEmpty()) "Select" else customStartDateText,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // End Date Button
                        OutlinedButton(
                            onClick = { endDatePickerDialog.show() },
                            modifier = Modifier.weight(1f).testTag("custom_end_date_button"),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "End Date",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = if (customEndDateText.isEmpty()) "Select" else customEndDateText,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // Full Ledger PDF download button
                OutlinedButton(
                    onClick = {
                        // Set the export type to FullLedger before launching the natively integrated SAF CreateDocument launcher
                        pdfExportType = "FullLedger"
                        // Generate a clean default filename with the current date to make it highly descriptive
                        val dateSuffix = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                        // Use Swanirvor-23 in the generated PDF filename per user request
                        createPdfLauncher.launch("swanirvor_23_full_ledger_$dateSuffix.pdf")
                    },
                    modifier = Modifier.fillMaxWidth().testTag("download_full_ledger_pdf"),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Summarize, contentDescription = "Ledger")
                            Text("Full Ledger", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Icon(Icons.Default.Download, contentDescription = "Download")
                    }
                }

                // Individual Statements expandable toggle button
                OutlinedButton(
                    onClick = {
                        // Toggle the visibility state of the individual members list under this panel
                        isIndividualStatementsOpen = !isIndividualStatementsOpen
                    },
                    modifier = Modifier.fillMaxWidth().testTag("individual_statements_toggle_button"),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.PersonSearch, contentDescription = "Statement")
                            Text("Individual Statements", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Icon(
                            imageVector = if (isIndividualStatementsOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "Expand individual statements"
                        )
                    }
                }

                // If expanded, show the list of all members styled beautifully with quick action icons to download their statements
                if (isIndividualStatementsOpen) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Select a member to generate their PDF statement:",
                                style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )

                            // Filter savings and sum up each member's contributions for the selected duration period
                            val (startT, endT) = getDurationTimestampRange(selectedDuration, customStartMillis, customEndMillis)
                            val memberSavingsMap = allSavings
                                .filter { it.timestamp in startT..endT }
                                .groupBy { it.memberId }

                            // Sort members alphabetically by name for high scannability and structural elegance
                            val sortedList = members.sortedBy { it.name }
                            sortedList.forEach { member ->
                                val totalSavingsInPeriod = memberSavingsMap[member.id]?.sumOf { it.amount } ?: 0.0
                                val membershipId = if (member.id == viewModel.currentUserMemberId) {
                                    appSettings.membershipNo
                                } else {
                                    "LS-2023-${String.format(Locale.US, "%03d", member.id)}"
                                }

                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            // Set the target metadata so the SAF launcher callback generates the right statement PDF
                                            pdfExportType = "Individual"
                                            selectedMemberForPdf = member
                                            val dateSuffix = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                                            val safeName = member.name.replace(" ", "_").lowercase(Locale.US)
                                            createPdfLauncher.launch("statement_${safeName}_$dateSuffix.pdf")
                                        }
                                        .testTag("member_statement_row_${member.id}"),
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color.White,
                                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Avatar display matching society directory aesthetics
                                            MemberAvatar(name = member.name, avatarUrl = member.avatarUrl, size = 28.dp)
                                            Column {
                                                Text(
                                                    text = member.name,
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                                )
                                                Text(
                                                    text = "ID: $membershipId",
                                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.outline)
                                                )
                                            }
                                        }
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Format period savings as integer per user preference
                                            Text(
                                                text = String.format(Locale.US, "%,.0f৳", totalSavingsInPeriod),
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                            )
                                            Icon(
                                                imageVector = Icons.Default.Description,
                                                contentDescription = "Download statement PDF",
                                                tint = Color(0xFF0C9488),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Add comment: Export App Data Button to export all members' savings grouped by member name in CSV format
                OutlinedButton(
                    onClick = {
                        // Create CSV content by sorting and grouping savings history by member
                        val csvContent = buildString {
                            // UTF-8 BOM to ensure Excel opens it with correct UTF-8 encoding
                            append('\ufeff')

                            // Sort members alphabetically by name
                            val sortedMembers = members.sortedBy { it.name }
                            sortedMembers.forEach { member ->
                                val membershipId = if (member.id == viewModel.currentUserMemberId) {
                                    appSettings.membershipNo
                                } else {
                                    "LS-2023-${String.format(Locale.US, "%03d", member.id)}"
                                }

                                val escapedName = escapeCsv(member.name)
                                val escapedEmail = escapeCsv(member.email)
                                val escapedMembershipNo = escapeCsv(membershipId)

                                append("Member Name: $escapedName - Membership No.: $escapedMembershipNo, Email: $escapedEmail\n")
                                append("Serial No., Deposit Date, Savings Amount\n")

                                // Get all savings for this member, sorted by timestamp ascending
                                val memberSavings = allSavings.filter { it.memberId == member.id }.sortedBy { it.timestamp }
                                memberSavings.forEachIndexed { index, saving ->
                                    val serialNo = index + 1
                                    val dateText = escapeCsv(saving.dateText)
                                    val amount = saving.amount
                                    append("$serialNo, $dateText, $amount\n")
                                }

                                val totalCount = memberSavings.size
                                val totalSum = memberSavings.sumOf { it.amount }
                                append("Total Deposit ($totalCount), $totalSum\n\n")
                            }
                        }

                        // Set CSV content in state and open the custom destination chooser dialog
                        pendingCsvContent = csvContent
                        showExportOptionDialog = true
                    },
                    modifier = Modifier.fillMaxWidth().testTag("export_app_data_button"),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Share, contentDescription = "Export")
                            Text("Export App Data", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Icon(Icons.Default.Download, contentDescription = "Download")
                    }
                }

                // Add comment: Export Options Dialog prompting the user to choose between Local Storage (SAF) and Google Drive & Others (Share Sheet)
                if (showExportOptionDialog) {
                    AlertDialog(
                        onDismissRequest = { showExportOptionDialog = false },
                        title = {
                            Text(
                                text = "Export Options",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        },
                        text = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            ) {
                                Text(
                                    text = "Choose your preferred export destination for all members' savings history:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                // Option 1: Save locally to phone storage with custom backup naming convention
                                Surface(
                                    onClick = {
                                        showExportOptionDialog = false
                                        // Generate current timestamp suffix
                                        val dateSuffix = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                                        // Launch SAF document creator with backup filename
                                        createCsvLauncher.launch("swanirvor_23_backup_$dateSuffix.csv")
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    modifier = Modifier.fillMaxWidth().testTag("export_phone_storage_option")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.PhoneAndroid,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Phone Storage",
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Save CSV file locally to your device",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                // Option 2: Share to external apps / Google Drive
                                Surface(
                                    onClick = {
                                        showExportOptionDialog = false
                                        try {
                                            // Generate current timestamp suffix for the shared backup filename
                                            val dateSuffix = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                                            val file = File(context.cacheDir, "swanirvor_23_backup_$dateSuffix.csv")
                                            file.writeText(pendingCsvContent, Charsets.UTF_8)

                                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                file
                                            )

                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/csv"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                putExtra(Intent.EXTRA_SUBJECT, "All Members Savings History")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(intent, "Export Savings History"))
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            Toast.makeText(context, "Error exporting data: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    modifier = Modifier.fillMaxWidth().testTag("export_share_option")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f),
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.Share,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.secondary
                                                )
                                            }
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Google Drive & Others",
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Share or upload to cloud services",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(
                                onClick = { showExportOptionDialog = false }
                            ) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }
        }
    }
}
