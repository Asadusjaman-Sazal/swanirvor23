package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.example.util.Constants

/**
 * The effective additive step in sp for the whole app: the shipped +1 plus the user's -2..+6.
 * Screen code reads this only for the few text sizes that are set outside MaterialTheme.typography.
 */
val LocalFontStep = staticCompositionLocalOf { Constants.FONT_SIZE_STEP_DEFAULT }

/**
 * Adds the same number of sp to every Material style. A 12 sp label and a 30 sp heading each
 * gain one step; this is deliberately additive, not a multiplier.
 */
private fun TextStyle.stepped(step: Int): TextStyle {
    if (step == 0) return this
    return copy(
        fontSize = if (fontSize.isSpecified) (fontSize.value + step).sp else fontSize,
        lineHeight = if (lineHeight.isSpecified) (lineHeight.value + step).sp else lineHeight
    )
}

/**
 * Builds the app Typography from the Material defaults with the step applied once, at the root.
 * Every screen, dialog, sheet and overlay inherits it through MaterialTheme.
 */
fun appTypography(step: Int): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.stepped(step),
        displayMedium = base.displayMedium.stepped(step),
        displaySmall = base.displaySmall.stepped(step),
        headlineLarge = base.headlineLarge.stepped(step),
        headlineMedium = base.headlineMedium.stepped(step),
        headlineSmall = base.headlineSmall.stepped(step),
        titleLarge = base.titleLarge.stepped(step),
        titleMedium = base.titleMedium.stepped(step),
        titleSmall = base.titleSmall.stepped(step),
        bodyLarge = base.bodyLarge.stepped(step),
        bodyMedium = base.bodyMedium.stepped(step),
        bodySmall = base.bodySmall.stepped(step),
        labelLarge = base.labelLarge.stepped(step),
        labelMedium = base.labelMedium.stepped(step),
        labelSmall = base.labelSmall.stepped(step)
    )
}
