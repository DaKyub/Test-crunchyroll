package com.dakyub.crunchymal.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val CrunchyOrange = Color(0xFFF47521)
val MalBlue = Color(0xFF2E51A2)
val Background = Color(0xFF0F0F12)

private val colors = darkColorScheme(
    primary = CrunchyOrange,
    onPrimary = Color.Black,
    secondary = MalBlue,
    onSecondary = Color.White,
    background = Background,
    onBackground = Color.White,
    surface = Color(0xFF1C1C22),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2A2A33),
    onSurfaceVariant = Color(0xFFB8B8C4),
)

@Composable
fun CrunchyMalTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
