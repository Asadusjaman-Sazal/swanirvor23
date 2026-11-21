package com.example.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Comment: The third startup state between splash and either the app or login. It keeps the branded
 * background visible while a saved session is resolved, and gives an explicit Retry path when the
 * local member cannot be found without a network connection.
 */
@Composable
fun SessionRestoringScreen(
    errorMessage: String?,
    onRetry: () -> Unit,
    onSignInAgain: () -> Unit
) {
    val deepNavy = Color(0xFF0B1C30)
    val goldAccent = Color(0xFFE9C176)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(deepNavy, Color(0xFF132A44), Color(0xFF081424))
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Swanirvor-23",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineMedium,
                letterSpacing = 1.5.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (errorMessage == null) {
                CircularProgressIndicator(color = goldAccent)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Restoring your session…",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            } else {
                // Comment: The session still exists, so this is a retryable restore failure rather
                // than a logout. Signing in again is an explicit choice that clears the session.
                Text(
                    text = errorMessage,
                    color = Color(0xFFF07171),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Color(0xFFF07171).copy(alpha = 0.12f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(16.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onRetry) {
                    Text(text = "Retry")
                }
                TextButton(onClick = onSignInAgain) {
                    Text(text = "Sign in again")
                }
            }
        }
    }
}
