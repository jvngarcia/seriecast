package com.seriecast.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF4FC3F7)
private val AccentDark = Color(0xFF0288D1)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00202B),
    primaryContainer = Color(0xFF00394D),
    onPrimaryContainer = Color(0xFFB9E9FF),
    secondary = Color(0xFF82CFA0),
    background = Color(0xFF0E1418),
    surface = Color(0xFF141B21),
    surfaceVariant = Color(0xFF1D262D),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = AccentDark,
    onPrimary = Color.White,
    secondary = Color(0xFF2E7D32),
)

@Composable
fun SerieCastTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
