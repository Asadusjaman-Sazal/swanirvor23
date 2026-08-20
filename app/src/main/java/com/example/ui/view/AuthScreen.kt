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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.viewmodel.SavingsViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.graphics.drawable.BitmapDrawable
import android.util.DisplayMetrics
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter

/**
 * Beautiful, cohesive Sign In and Sign Up page for the Swanirvor-23 App.
 * Matches the deep navy and gold professional aesthetic of the legal savings tracker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(viewModel: SavingsViewModel) {
    var isSignInMode by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    // Form Field States
    var nameInput by remember { mutableStateOf("") }
    // Comment: Store the user's membership number during the Sign Up process
    var membershipNoInput by remember { mutableStateOf("") }
    // Comment: Store the user's mobile number during the Sign Up process (digits only; the +88 prefix is prepended on save)
    var mobileNoInput by remember { mutableStateOf("") }
    // Comment: Prefill the email field with the last registered/logged-in email address for quick and easy authentication
    var emailInput by remember { mutableStateOf(com.example.data.SupabaseClient.getRegisteredEmail()) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    // Loading & Error states
    var isLoading by remember { mutableStateOf(false) }
    var authErrorMessage by remember { mutableStateOf<String?>(null) }
    // Comment: Store successful signup message to display above the form inputs
    var signUpSuccessMessage by remember { mutableStateOf<String?>(null) }

    // Comment: Observe Google login states from ViewModel
    val googleLoginLoading by viewModel.googleLoginLoading.collectAsStateWithLifecycle()
    val googleLoginError by viewModel.googleLoginError.collectAsStateWithLifecycle()

    // Comment: Automatically sync the Google Login error into our UI display state
    LaunchedEffect(googleLoginError) {
        if (googleLoginError != null) {
            authErrorMessage = googleLoginError
        }
    }

    // Access active styling palettes
    val deepNavy = Color(0xFF0B1C30)
    val goldAccent = Color(0xFFE9C176)
    val tealAccent = Color(0xFF0C9488)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        deepNavy,
                        Color(0xFF132A44),
                        Color(0xFF081424)
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center
    ) {
        // Overlay decorative geometric lines for professional visual texture
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = goldAccent.copy(alpha = 0.05f),
                radius = 300.dp.toPx(),
                center = Offset(size.width * 0.9f, size.height * 0.1f)
            )
            drawCircle(
                color = tealAccent.copy(alpha = 0.04f),
                radius = 400.dp.toPx(),
                center = Offset(size.width * 0.1f, size.height * 0.8f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App Branding Logo Section
            // Comment: Use the actual manually changed app logo via rememberAppLogoPainter() helper.
            // This prevents the default vector favicon from displaying as a solid yellow dot and removes extraneous background/border layers.
            Image(
                painter = rememberAppLogoPainter(),
                contentDescription = "Swanirvor-23 Logo",
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(16.dp))
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Swanirvor-23",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineMedium,
                letterSpacing = 1.5.sp
            )

            Text(
                text = "A Savings Society by NDBA Batch 2023",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Auth Card Panel containing Form fields
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("auth_card"),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF132338).copy(alpha = 0.85f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Switch Selector (Tab style)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(Color(0xFF0B1624), RoundedCornerShape(24.dp))
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (isSignInMode) goldAccent else Color.Transparent)
                                .clickable {
                                    isSignInMode = true
                                    authErrorMessage = null
                                    signUpSuccessMessage = null
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Sign In",
                                fontWeight = FontWeight.SemiBold,
                                color = if (isSignInMode) deepNavy else Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (!isSignInMode) goldAccent else Color.Transparent)
                                .clickable {
                                    isSignInMode = false
                                    authErrorMessage = null
                                    signUpSuccessMessage = null
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Sign Up",
                                fontWeight = FontWeight.SemiBold,
                                color = if (!isSignInMode) deepNavy else Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = if (isSignInMode) "Welcome Back" else "Create Account",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )

                    Text(
                        text = if (isSignInMode) "Securely sign in to access your dashboard" else "Join our community collective savings plan",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
                    )

                    // Comment: Beautiful success message display above the form inputs when redirecting from successful Sign Up
                    AnimatedVisibility(
                        visible = isSignInMode && signUpSuccessMessage != null,
                        enter = fadeIn() + expandVertically(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        signUpSuccessMessage?.let { successMsg ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp)
                                    .background(Color(0xFF0C9488).copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                    .border(1.dp, Color(0xFF0C9488).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                    .padding(16.dp)
                                    .testTag("signup_success_banner")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Start
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Success Icon",
                                        tint = Color(0xFF2DD4BF),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = successMsg,
                                        color = Color(0xFFCCFBF1),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    // Form - Name Field (Only shown in Sign Up Mode)
                    AnimatedVisibility(
                        visible = !isSignInMode,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            label = { Text("Full Name") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = "Name Icon", tint = goldAccent) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color(0xFF0B1624),
                                unfocusedContainerColor = Color(0xFF0B1624),
                                focusedBorderColor = goldAccent,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                focusedLabelColor = goldAccent,
                                unfocusedLabelColor = Color.White.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                                .testTag("name_input")
                        )
                    }

                    // Comment: Form - Membership No Field (Only shown in Sign Up Mode as requested)
                    AnimatedVisibility(
                        visible = !isSignInMode,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        OutlinedTextField(
                            value = membershipNoInput,
                            onValueChange = { membershipNoInput = it },
                            label = { Text("Membership No.") },
                            leadingIcon = { Icon(Icons.Default.AccountBox, contentDescription = "Membership No Icon", tint = goldAccent) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color(0xFF0B1624),
                                unfocusedContainerColor = Color(0xFF0B1624),
                                focusedBorderColor = goldAccent,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                focusedLabelColor = goldAccent,
                                unfocusedLabelColor = Color.White.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                                .testTag("membership_no_input")
                        )
                    }

                    // Comment: Form - Mobile No Field (Only shown in Sign Up Mode as requested)
                    // Matches the Membership No. box styling; the +88 prefix is immutable and only digits are typed
                    AnimatedVisibility(
                        visible = !isSignInMode,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        OutlinedTextField(
                            value = mobileNoInput,
                            onValueChange = {
                                // Comment: Keep only digits, drop a pasted leading 88 country code so it is not doubled,
                                // and cap at 11 digits so the full number with the +88 prefix never exceeds 14 characters
                                mobileNoInput = it.filter(Char::isDigit).let { digits ->
                                    val cleaned = if (digits.startsWith("88") && digits.length > 11) digits.removePrefix("88") else digits
                                    cleaned.take(11)
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.Phone, contentDescription = "Mobile No Icon", tint = goldAccent) },
                            prefix = { Text("+88", fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.7f)) },
                            placeholder = { Text("1XXXXXXXXX", color = Color.White.copy(alpha = 0.4f)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color(0xFF0B1624),
                                unfocusedContainerColor = Color(0xFF0B1624),
                                focusedBorderColor = goldAccent,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.15f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                                .testTag("mobile_no_input")
                        )
                    }

                    // Form - Email Field
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text("Email Address") },
                        leadingIcon = { Icon(Icons.Default.Email, contentDescription = "Email Icon", tint = goldAccent) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF0B1624),
                            unfocusedContainerColor = Color(0xFF0B1624),
                            focusedBorderColor = goldAccent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                            focusedLabelColor = goldAccent,
                            unfocusedLabelColor = Color.White.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                            .testTag("email_input")
                    )

                    // Form - Password Field
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Password") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = "Password Icon", tint = goldAccent) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = "Toggle password visibility",
                                    tint = goldAccent.copy(alpha = 0.7f)
                                )
                            }
                        },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF0B1624),
                            unfocusedContainerColor = Color(0xFF0B1624),
                            focusedBorderColor = goldAccent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                            focusedLabelColor = goldAccent,
                            unfocusedLabelColor = Color.White.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = if (isSignInMode) 8.dp else 24.dp)
                            .testTag("password_input")
                    )

                    // Comment: Forgot Password button (only shown in Sign In mode)
                    AnimatedVisibility(
                        visible = isSignInMode,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(
                                text = "Forgot Password?",
                                color = goldAccent,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable {
                                        focusManager.clearFocus()
                                        if (emailInput.isBlank()) {
                                            authErrorMessage = "Please enter your email address to reset your password."
                                            Toast.makeText(context, "Please enter your email address.", Toast.LENGTH_SHORT).show()
                                        } else {
                                            isLoading = true
                                            authErrorMessage = null
                                            viewModel.recoverPassword(emailInput) { success, msg ->
                                                isLoading = false
                                                if (!success) {
                                                    authErrorMessage = msg
                                                } else {
                                                    signUpSuccessMessage = msg
                                                }
                                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                    .padding(vertical = 4.dp, horizontal = 8.dp)
                                    .testTag("forgot_password_button")
                            )
                        }
                    }

                    // Primary Action Button
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            isLoading = true
                            authErrorMessage = null
                            if (isSignInMode) {
                                viewModel.login(emailInput, passwordInput) { success, msg ->
                                    isLoading = false
                                    if (!success) {
                                        authErrorMessage = msg
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                viewModel.signUp(nameInput, emailInput, passwordInput, membershipNoInput, mobileNoInput) { success, msg ->
                                    isLoading = false
                                    if (!success) {
                                        authErrorMessage = msg
                                    } else {
                                        // Comment: Switch to Sign In mode, keep emailInput, and show success banner above the form
                                        isSignInMode = true
                                        signUpSuccessMessage = "Your account has been created. Please login with your email & password."
                                        passwordInput = ""
                                        membershipNoInput = ""
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("auth_submit_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = goldAccent,
                            contentColor = deepNavy
                        ),
                        shape = RoundedCornerShape(26.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = deepNavy,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = if (isSignInMode) "Sign In" else "Register",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Comment: Simple error handling - small error message displayed under the form
                    androidx.compose.animation.AnimatedVisibility(
                        visible = authErrorMessage != null,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        authErrorMessage?.let { error ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = error,
                                color = Color(0xFFF07171), // Visually polished eye-safe coral/red
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp)
                                    .testTag("auth_error_message")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Comment: Beautiful horizontal divider representing the "OR" visual separator
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.weight(1f),
                            color = Color.White.copy(alpha = 0.15f)
                        )
                        Text(
                            text = "OR",
                            color = Color.White.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp),
                            fontWeight = FontWeight.Bold
                        )
                        HorizontalDivider(
                            modifier = Modifier.weight(1f),
                            color = Color.White.copy(alpha = 0.15f)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Comment: High-fidelity "Sign In/Sign Up with Google" button using Supabase OAuth
                    OutlinedButton(
                        onClick = {
                            if (!googleLoginLoading && !isLoading) {
                                // Comment: Force Google Account Chooser screen (prompt select_account) so user can choose or add another Gmail account
                                // We use percent-encoded brackets (%5B and %5D) so Android Uri.parse and Chrome do not strip or corrupt the queryParams[prompt] parameter
                                val url = "${com.example.BuildConfig.SUPABASE_URL}/auth/v1/authorize?provider=google&redirect_to=swanirvor23://login-callback&queryParams%5Bprompt%5D=select_account&query_params%5Bprompt%5D=select_account"
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("google_auth_button"),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White
                        ),
                        enabled = !googleLoginLoading && !isLoading
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (googleLoginLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = goldAccent,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Signing in...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                            } else {
                                // Comment: Decorative icon representation for the Google authentication entry point
                                Icon(
                                    imageVector = Icons.Default.AccountCircle,
                                    contentDescription = "Google Icon",
                                    tint = goldAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = if (isSignInMode) "Sign In with Google" else "Sign Up with Google",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
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
