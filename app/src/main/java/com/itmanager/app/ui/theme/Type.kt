package com.itmanager.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * System font (Google Sans / Roboto Flex on Pixel), with tracking and leading tuned per size:
 * large text gets negative tracking and tight leading, small labels a touch of positive tracking.
 */
private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
)

val AppTypography = Typography(
    displayLarge = style(52, 56, FontWeight.Bold, -0.025),
    displayMedium = style(42, 46, FontWeight.Bold, -0.022),
    displaySmall = style(34, 40, FontWeight.Bold, -0.02),
    headlineLarge = style(30, 36, FontWeight.Bold, -0.018),
    headlineMedium = style(26, 32, FontWeight.SemiBold, -0.015),
    headlineSmall = style(22, 28, FontWeight.SemiBold, -0.012),
    titleLarge = style(20, 26, FontWeight.SemiBold, -0.008),
    titleMedium = style(16, 22, FontWeight.SemiBold, -0.004),
    titleSmall = style(14, 20, FontWeight.SemiBold, 0.0),
    bodyLarge = style(16, 24, FontWeight.Normal, 0.0),
    bodyMedium = style(14, 20, FontWeight.Normal, 0.0),
    bodySmall = style(12, 16, FontWeight.Normal, 0.008),
    labelLarge = style(14, 18, FontWeight.Medium, 0.004),
    labelMedium = style(12, 16, FontWeight.Medium, 0.012),
    labelSmall = style(11, 14, FontWeight.Medium, 0.02),
)

/** Monospace for IPs, MACs and interface names, so columns of addresses line up. */
val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, letterSpacing = 0.em)
