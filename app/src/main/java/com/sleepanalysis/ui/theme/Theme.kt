package com.sleepanalysis.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD1E8),
    onPrimary = Color(0xFF06202B),
    primaryContainer = Color(0xFF1B3A47),
    onPrimaryContainer = Color(0xFFCDEEF8),
    secondary = Color(0xFFB3C4CE),
    secondaryContainer = Color(0xFF2E3B44),
    onSecondaryContainer = Color(0xFFD9E5EC),
    background = Color(0xFF10151C),
    onBackground = Color(0xFFDFE4E9),
    surface = Color(0xFF161C24),
    onSurface = Color(0xFFDFE4E9),
    surfaceVariant = Color(0xFF222B34),
    onSurfaceVariant = Color(0xFFB7C1CA),
    error = Color(0xFFF2B8B5),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF146C86),
    background = Color(0xFFFAFBFC),
)

@Composable
fun SleepAnalysisTheme(content: @Composable () -> Unit) {
    // A sleep app is used at night; default to dark unless the system is explicitly light.
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
