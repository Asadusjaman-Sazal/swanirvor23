package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.example.util.Constants

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontStep: Int = 0,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val density = LocalDensity.current

    // Comment: The OS accessibility font scale is deliberately ignored for this app: layouts were measured at
    // one size, and Settings > View Settings is the only way to enlarge text. The step is additive and applied
    // once in Typography, so a label and a heading each gain the same amount.
    val effectiveStep = Constants.FONT_SIZE_STEP_DEFAULT + Constants.clampFontSizeStep(fontStep)

    CompositionLocalProvider(
        LocalDensity provides Density(density = density.density, fontScale = 1f),
        LocalFontStep provides effectiveStep
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = appTypography(effectiveStep),
            content = content
        )
    }
}
