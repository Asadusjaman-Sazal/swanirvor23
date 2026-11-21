package com.example.util

import androidx.compose.material3.Typography
import androidx.compose.ui.unit.sp
import com.example.ui.theme.appTypography
import org.junit.Assert.assertEquals
import org.junit.Test

class FontSizeStepTest {

    @Test
    fun clampHoldsOnReadAndWrite() {
        assertEquals(-2, Constants.clampFontSizeStep(-99))
        assertEquals(-2, Constants.clampFontSizeStep(-2))
        assertEquals(0, Constants.clampFontSizeStep(0))
        assertEquals(6, Constants.clampFontSizeStep(6))
        assertEquals(6, Constants.clampFontSizeStep(99))
    }

    @Test
    fun stepIsAdditiveForSmallAndLargeStyles() {
        val base = Typography()
        val plusOne = appTypography(1)
        val plusSix = appTypography(6)

        assertEquals(base.labelSmall.fontSize.value + 1, plusOne.labelSmall.fontSize.value, 0.001f)
        assertEquals(base.bodyLarge.fontSize.value + 1, plusOne.bodyLarge.fontSize.value, 0.001f)
        assertEquals(base.headlineLarge.fontSize.value + 1, plusOne.headlineLarge.fontSize.value, 0.001f)

        assertEquals(base.labelSmall.fontSize.value + 6, plusSix.labelSmall.fontSize.value, 0.001f)
        assertEquals(base.headlineLarge.fontSize.value + 6, plusSix.headlineLarge.fontSize.value, 0.001f)
    }

    @Test
    fun lineHeightMovesWithTheStep() {
        val base = Typography()
        val plusTwo = appTypography(2)
        assertEquals(base.bodyLarge.lineHeight.value + 2, plusTwo.bodyLarge.lineHeight.value, 0.001f)
        assertEquals(base.labelSmall.lineHeight.value + 2, plusTwo.labelSmall.lineHeight.value, 0.001f)
    }

    @Test
    fun defaultStepIsTheShippedPlusOne() {
        assertEquals(1, Constants.FONT_SIZE_STEP_DEFAULT)
        assertEquals((-1).sp, (Constants.FONT_SIZE_STEP_MIN + Constants.FONT_SIZE_STEP_DEFAULT).sp)
        assertEquals(7.sp, (Constants.FONT_SIZE_STEP_MAX + Constants.FONT_SIZE_STEP_DEFAULT).sp)
    }
}
