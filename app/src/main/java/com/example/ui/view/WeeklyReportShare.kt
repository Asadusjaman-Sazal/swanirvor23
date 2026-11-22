package com.example.ui.view

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.example.util.WeeklyReportData
import com.example.util.WeeklyReportFormatter
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pop-over shown by the Weekly Report button, offering a shareable PDF or copied/shared text.
 */
@Composable
fun WeeklyReportOptionsDialog(
    report: WeeklyReportData,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("weekly_report_options")
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Weekly Report",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Choose how to share this week's report.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                WeeklyReportOption(
                    icon = Icons.Default.PictureAsPdf,
                    title = "PDF",
                    description = "Create a PDF and share it",
                    tag = "weekly_report_pdf_option",
                    onClick = {
                        onDismiss()
                        shareWeeklyReportPdf(context, report)
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                WeeklyReportOption(
                    icon = Icons.Default.TextFields,
                    title = "Text",
                    description = "Copy the report and share it",
                    tag = "weekly_report_text_option",
                    onClick = {
                        onDismiss()
                        shareWeeklyReportText(context, report)
                    }
                )
            }
        }
    }
}

/**
 * One selectable row inside the Weekly Report pop-over.
 */
@Composable
private fun WeeklyReportOption(
    icon: ImageVector,
    title: String,
    description: String,
    tag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Writes the report to the cache and opens the system share sheet with a PDF attachment.
 */
private fun shareWeeklyReportPdf(context: Context, report: WeeklyReportData) {
    val dateSuffix = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
    val file = File(context.cacheDir, "weekly_report_$dateSuffix.pdf")

    try {
        file.outputStream().use { output -> writeWeeklyReportPdf(report, output) }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Weekly Report")
            clipData = ClipData.newRawUri("Weekly Report", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Weekly Report"))
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Could not share the PDF: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}

/**
 * Copies the report to the clipboard, then opens the same share sheet for WhatsApp and other apps.
 */
private fun shareWeeklyReportText(context: Context, report: WeeklyReportData) {
    val text = WeeklyReportFormatter.build(report)

    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Weekly Report", text))
        Toast.makeText(context, "Weekly report copied to clipboard", Toast.LENGTH_SHORT).show()

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "Weekly Report")
        }
        context.startActivity(Intent.createChooser(intent, "Share Weekly Report"))
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Could not share the report: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}

/**
 * Renders the same report text into an A4 PDF, with wrapped rows and pagination for long member lists.
 */
private fun writeWeeklyReportPdf(report: WeeklyReportData, outputStream: OutputStream) {
    val document = PdfDocument()
    var pageNumber = 1
    var page = document.startPage(
        PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
    )
    var canvas = page.canvas
    var y = 54f

    val titlePaint = Paint().apply {
        color = android.graphics.Color.parseColor("#0C9488")
        textSize = 18f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    val sectionPaint = Paint().apply {
        color = android.graphics.Color.BLACK
        textSize = 11f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    val bodyPaint = Paint().apply {
        color = android.graphics.Color.DKGRAY
        textSize = 10.5f
        isAntiAlias = true
    }
    val rulePaint = Paint().apply {
        color = android.graphics.Color.parseColor("#0C9488")
        strokeWidth = 1.5f
    }

    // Comment: Finish the current page and continue the report with a small repeated heading.
    fun startNewPage() {
        document.finishPage(page)
        pageNumber++
        page = document.startPage(
            PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
        )
        canvas = page.canvas
        y = 54f
        canvas.drawText("Weekly Report (continued)", 40f, y, titlePaint)
        y += 12f
        canvas.drawLine(40f, y, 555f, y, rulePaint)
        y += 22f
    }

    val lines = WeeklyReportFormatter.build(report).lines()
    lines.forEachIndexed { index, rawLine ->
        if (index == 0) {
            canvas.drawText(rawLine, 40f, y, titlePaint)
            y += 12f
            canvas.drawLine(40f, y, 555f, y, rulePaint)
            y += 22f
            return@forEachIndexed
        }

        if (rawLine.isBlank()) {
            y += 9f
            return@forEachIndexed
        }

        val isSection = rawLine.endsWith(":")
        val paint = if (isSection) sectionPaint else bodyPaint
        val lineHeight = if (isSection) 18f else 15f

        wrapLine(rawLine, paint, 515f).forEach { wrappedLine ->
            if (y + lineHeight > 800f) {
                startNewPage()
            }
            canvas.drawText(wrappedLine, 40f, y, paint)
            y += lineHeight
        }
    }

    document.finishPage(page)
    document.writeTo(outputStream)
    document.close()
}

/**
 * Splits a report line at word boundaries so long member names never run off the PDF page.
 */
private fun wrapLine(text: String, paint: Paint, maxWidth: Float): List<String> {
    if (paint.measureText(text) <= maxWidth) return listOf(text)

    val lines = mutableListOf<String>()
    val current = StringBuilder()

    text.split(" ").forEach { word ->
        val candidate = if (current.isEmpty()) word else "$current $word"
        if (paint.measureText(candidate) <= maxWidth) {
            current.clear()
            current.append(candidate)
            return@forEach
        }

        if (current.isNotEmpty()) {
            lines.add(current.toString())
            current.clear()
        }

        // Comment: A single unbroken long word is split by character as a last resort.
        var chunk = StringBuilder()
        word.forEach { character ->
            if (paint.measureText(chunk.toString() + character) > maxWidth && chunk.isNotEmpty()) {
                lines.add(chunk.toString())
                chunk = StringBuilder()
            }
            chunk.append(character)
        }
        current.append(chunk)
    }

    if (current.isNotEmpty()) lines.add(current.toString())
    return lines.ifEmpty { listOf(text) }
}
