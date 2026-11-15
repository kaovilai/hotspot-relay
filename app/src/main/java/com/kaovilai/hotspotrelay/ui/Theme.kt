package com.kaovilai.hotspotrelay.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Amber = Color(0xFFD97706)

private val LightScheme = lightColorScheme(
    primary = Amber,
    secondary = Color(0xFF92400E),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFF59E0B),
    secondary = Color(0xFFFCD34D),
)

@Composable
fun HotspotRelayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
