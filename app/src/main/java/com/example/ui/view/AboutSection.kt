package com.example.ui.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.R

/**
 * Settings > About section: app identity (logo, name, version), the developer card and the
 * copyright line. Accordion-styled like every other Settings section.
 */
@Composable
fun AboutSection(
    isOpen: Boolean,
    onToggle: () -> Unit,
    containerColor: Color
) {
    AccordionCard(
        title = "About",
        icon = Icons.Default.Info,
        isOpen = isOpen,
        onToggle = onToggle,
        containerColor = containerColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Comment: Bundled app logo, app name and the installed version (parsed from versionHistory.txt)
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "Swanirvor-23 logo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
            Text(
                text = "Swanirvor-23",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Comment: Developer card — identity only (support channels live elsewhere)
            Text(
                text = "Designed & Developed By",
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.outline
            )
            Image(
                painter = painterResource(id = R.drawable.dev_profile),
                contentDescription = "Asadusjaman Sazal",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
            )
            Text(
                text = "Asadusjaman Sazal",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Advocate",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = "District & Sessions Judge Court, Narayanganj.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Comment: Copyright line
            Text(
                text = "© 2026 Asadusjaman Sazal",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
