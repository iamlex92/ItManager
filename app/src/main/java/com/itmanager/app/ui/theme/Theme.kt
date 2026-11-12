package com.itmanager.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = AccentContainer,
    onPrimaryContainer = Color(0xFFD5E6FF),
    secondary = Ink200,
    onSecondary = Ink950,
    secondaryContainer = Ink700,
    onSecondaryContainer = Ink50,
    tertiary = StatusColors.Online,
    background = Ink950,
    onBackground = Ink50,
    surface = Ink900,
    onSurface = Ink50,
    surfaceVariant = Ink800,
    onSurfaceVariant = TextMuted,
    surfaceContainerLowest = Ink950,
    surfaceContainerLow = Ink850,
    surfaceContainer = Ink800,
    surfaceContainerHigh = Ink700,
    surfaceContainerHighest = Ink600,
    outline = LineStrong,
    outlineVariant = Ink600,
    error = StatusColors.Offline,
    onError = StatusColors.OnDanger,
    errorContainer = StatusColors.DangerContainer,
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5FBF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E5FF),
    onPrimaryContainer = Color(0xFF0B2A66),
    secondary = Color(0xFF4A5565),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E8F0),
    onSecondaryContainer = Color(0xFF0F1620),
    tertiary = Color(0xFF1B7F3A),
    background = Color(0xFFEDF1F5),
    onBackground = Color(0xFF0F1620),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F1620),
    surfaceVariant = Color(0xFFE9EDF3),
    onSurfaceVariant = Color(0xFF4A5565),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F4F9),
    surfaceContainer = Color(0xFFEBEFF5),
    surfaceContainerHigh = Color(0xFFE3E8F0),
    surfaceContainerHighest = Color(0xFFDAE0E9),
    outline = Color(0xFF6B7686),
    outlineVariant = Color(0xFFC5CDD8),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

/**
 * Dark by default: the app is used in server rooms and on the shop floor. Light is a choice in the settings; the
 * map and the port panels draw with their own colours and are made for the dark theme.
 */
@Composable
fun ItManagerTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
