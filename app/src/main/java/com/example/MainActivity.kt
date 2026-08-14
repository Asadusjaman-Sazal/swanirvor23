package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.view.MainScreen
import com.example.ui.viewmodel.SavingsViewModel
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var savingsViewModel: SavingsViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.data.SupabaseClient.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            val viewModel: SavingsViewModel = viewModel()
            savingsViewModel = viewModel
            val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()

            // Comment: Control the display state of the splash screen
            var showSplashScreen by remember { mutableStateOf(true) }

            // Handle the launch intent if it contains deep link parameters or notification click extras
            androidx.compose.runtime.LaunchedEffect(intent) {
                intent?.let {
                    handleDeepLink(it, viewModel)
                    handleNotificationIntent(it, viewModel)
                }
            }

            // Reactive theme styling (Light / Dark mode toggle)
            MyApplicationTheme(darkTheme = appSettings.isDarkMode) {
                // Comment: Show Splash Screen first on application startup, then navigate to MainScreen
                if (showSplashScreen) {
                    SplashScreen(onTimeout = { showSplashScreen = false })
                } else {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        savingsViewModel?.let { viewModel ->
            handleDeepLink(intent, viewModel)
            handleNotificationIntent(intent, viewModel)
        }
    }

    // Comment: Override onResume to force a complete layout and redraw of the window's decor view.
    // This solves the rendering hang on warm relaunch where the Compose UI stays white/blank until touched.
    override fun onResume() {
        super.onResume()
        try {
            window.decorView.requestLayout()
            window.decorView.postInvalidate()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to force redraw on resume", e)
        }
    }

    private fun handleNotificationIntent(intent: android.content.Intent, viewModel: SavingsViewModel) {
        val navigateTo = intent.getStringExtra("navigate_to")
        if (navigateTo != null) {
            android.util.Log.d("MainActivity", "Notification Intent received. navigate_to: $navigateTo")
            viewModel.triggerNavigation(navigateTo)
        }
    }

    private fun handleDeepLink(intent: android.content.Intent, viewModel: SavingsViewModel) {
        val uri: android.net.Uri? = intent.data
        if (uri != null) {
            val scheme = uri.scheme
            val host = uri.host
            android.util.Log.i("MainActivity", "Deep Link received! Scheme: $scheme, Host: $host, URI: $uri")
            if (scheme == "swanirvor23" || scheme == "com.legumsoft.swanirvor23") {
                // Access fragment or query
                val fragment = uri.fragment
                val query = uri.query
                val paramStr = if (!fragment.isNullOrBlank()) fragment else if (!query.isNullOrBlank()) query else null

                android.util.Log.d("MainActivity", "Parsing deep link parameters. Fragment: $fragment, Query: $query")
                if (!paramStr.isNullOrBlank()) {
                    val params = parseFragmentParameters(paramStr)
                    val accessToken = params["access_token"]
                    val refreshToken = params["refresh_token"] ?: ""
                    val expiresIn = params["expires_in"] ?: "3600"
                    val tokenType = params["token_type"] ?: "bearer"
                    val type = params["type"] ?: ""

                    android.util.Log.i("MainActivity", "Deep Link parsed tokens: accessToken isNullOrBlank=${accessToken.isNullOrBlank()}, refreshToken isNullOrBlank=${refreshToken.isEmpty()}, type=$type")

                    if (!accessToken.isNullOrBlank()) {
                        if (type == "recovery" || uri.toString().contains("type=recovery") || uri.toString().contains("recovery")) {
                            android.util.Log.i("MainActivity", "Password recovery callback detected. Initiating recovery flow.")
                            viewModel.handleRecoveryCallback(accessToken, refreshToken, expiresIn, tokenType)
                        } else {
                            android.util.Log.i("MainActivity", "Standard login callback detected. Initiating standard OAuth flow.")
                            viewModel.handleGoogleLoginCallback(accessToken, refreshToken, expiresIn, tokenType)
                        }
                    } else {
                        android.util.Log.w("MainActivity", "Deep link is missing the access_token parameter.")
                    }
                } else {
                    android.util.Log.w("MainActivity", "Deep link contains no query or fragment parameters.")
                }
            }
        }
    }

    private fun parseFragmentParameters(fragment: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        try {
            val pairs = fragment.split("&")
            for (pair in pairs) {
                val idx = pair.indexOf("=")
                if (idx != -1) {
                    val key = java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                    val value = java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                    params[key] = value
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error parsing deep link parameters", e)
        }
        return params
    }
}

/**
 * A visually stunning and animated Splash Screen featuring the custom app logo.
 * Designed with a subtle gradient background, smooth scale/fade entry animation,
 * and high-fidelity typography matching the dynamic theme.
 */
@Composable
private fun SplashScreen(onTimeout: () -> Unit) {
    val context = LocalContext.current
    var startAnimation by remember { mutableStateOf(false) }

    // Comment: Disable back button and gestures entirely during splash screen startup to prevent user from exiting prematurely
    androidx.activity.compose.BackHandler(enabled = true) {
        // Do nothing to disable back gestures
    }

    val scale by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.8f,
        animationSpec = tween(durationMillis = 1000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "LogoScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 800),
        label = "LogoAlpha"
    )

    LaunchedEffect(Unit) {
        startAnimation = true
        // Comment: Delay for 3 seconds before dismissing the splash screen to allow the user to see the logo
        delay(3000)
        onTimeout()
    }

    val gradientColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.surface
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(gradientColors)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            // Comment: Render the beautiful squircle custom app logo
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .scale(scale)
                    .alpha(alpha),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = rememberAppLogoPainter(),
                    contentDescription = "App Logo",
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Comment: Display the app launcher name with modern display typography
            Text(
                text = "Swanirvor-23",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.alpha(alpha)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Comment: Subtitle displaying a clean description of the app's purpose
            Text(
                text = "A Savings Society by NDBA Batch 2023",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alpha(alpha)
            )
        }

        // Comment: Bottom branding watermark
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .alpha(alpha)
        ) {
            Text(
                text = "Empowering Communities",
                style = MaterialTheme.typography.labelMedium.copy(
                    letterSpacing = 2.sp
                ),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
            )
        }
    }
}

/**
 * Helper composable to load the custom app logo safely.
 * Gracefully handles Adaptive Icons (AdaptiveIconDrawable), XML Vector Drawables,
 * and standard raster formats (BitmapDrawable) across any density to avoid crashes or hanging.
 */
@Composable
private fun rememberAppLogoPainter(): Painter {
    val context = LocalContext.current
    return remember(context) {
        try {
            // Load the drawable safely using ContextCompat (handles adaptive/vector/bitmap automatically)
            val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
            if (drawable != null) {
                // If it's already a BitmapDrawable, use its bitmap directly to save memory/processing
                if (drawable is BitmapDrawable) {
                    BitmapPainter(drawable.bitmap.asImageBitmap())
                } else {
                    // For AdaptiveIconDrawable, VectorDrawable, or other XML drawables, draw it onto a Bitmap Canvas
                    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 512
                    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 512
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, width, height)
                    drawable.draw(canvas)
                    BitmapPainter(bitmap.asImageBitmap())
                }
            } else {
                null
            }
        } catch (e: Throwable) {
            // Log the issue and fallback to the static vector drawable placeholder
            Log.e("MainActivity", "Failed to load app logo drawable securely, falling back", e)
            null
        }
    } ?: painterResource(id = R.drawable.ic_favicon_placeholder)
}

