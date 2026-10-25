package com.itmanager.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
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
    onSurfaceVariant = Ink200,
    surfaceContainerLowest = Ink950,
    surfaceContainerLow = Ink850,
    surfaceContainer = Ink800,
    surfaceContainerHigh = Ink700,
    surfaceContainerHighest = Ink600,
    outline = Ink600,
    outlineVariant = Ink700,
    error = StatusColors.Offline,
    onError = Color.White,
    errorContainer = Color(0xFF4A1512),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** The app is dark-only by design: it is used in server rooms and on the shop floor. */
@Composable
fun ItManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
