package com.serowcam.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
    primary = Color(0xFFD7A15A),
    onPrimary = Color(0xFF1C1408),
    background = Color(0xFF090A0B),
    surface = Color(0xFF161513),
    onBackground = Color(0xFFF3EFE6),
    onSurface = Color(0xFFF3EFE6),
    error = Color(0xFFFF3B30),
)

@Composable
fun SerowCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
