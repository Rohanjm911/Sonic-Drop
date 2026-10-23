package com.sonicdrop.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DarkColorScheme = darkColorScheme(
    primary = DarkPrimaryAccent,
    onPrimary = Color.White,
    primaryContainer = DarkBackgroundSecondary,
    onPrimaryContainer = DarkPrimaryText,
    secondary = DarkWarning,
    onSecondary = Color.Black,
    tertiary = DarkSuccess,
    onTertiary = Color.White,
    error = DarkError,
    onError = Color.White,
    background = DarkBackground,
    onBackground = DarkPrimaryText,
    surface = DarkCardBackground,
    onSurface = DarkPrimaryText,
    surfaceVariant = DarkBackgroundSecondary,
    onSurfaceVariant = DarkSecondaryText,
    outline = DarkBorders
)

val LightColorScheme = lightColorScheme(
    primary = LightPrimaryAccent,
    onPrimary = Color.White,
    primaryContainer = LightBackgroundSecondary,
    onPrimaryContainer = LightPrimaryText,
    secondary = LightWarning,
    onSecondary = Color.White,
    tertiary = LightSuccess,
    onTertiary = Color.White,
    error = LightError,
    onError = Color.White,
    background = LightBackground,
    onBackground = LightPrimaryText,
    surface = LightCardBackground,
    onSurface = LightPrimaryText,
    surfaceVariant = LightBackgroundSecondary,
    onSurfaceVariant = LightSecondaryText,
    outline = LightBorders
)

@Composable
fun SonicDropTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
