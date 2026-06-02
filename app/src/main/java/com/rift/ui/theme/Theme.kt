package com.rift.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// WiFi signal strength colors
val SignalExcellent = Color(0xFF00E676)
val SignalGood = Color(0xFF69F0AE)
val SignalFair = Color(0xFFFFD740)
val SignalPoor = Color(0xFFFF6D00)
val SignalVeryPoor = Color(0xFFD50000)

// Brand palette
val NeonCyan = Color(0xFF00E5FF)
val NeonBlue = Color(0xFF2979FF)
val DarkSurface = Color(0xFF0D1117)
val DarkSurfaceVariant = Color(0xFF161B22)
val DarkOutline = Color(0xFF21262D)

private val RiftDarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Color(0xFF003039),
    primaryContainer = Color(0xFF004E5A),
    onPrimaryContainer = Color(0xFF97F0FF),
    secondary = NeonBlue,
    onSecondary = Color(0xFF00315F),
    secondaryContainer = Color(0xFF004880),
    onSecondaryContainer = Color(0xFFD1E4FF),
    background = DarkSurface,
    onBackground = Color(0xFFE6EDF3),
    surface = DarkSurface,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFF8B949E),
    outline = DarkOutline,
    error = Color(0xFFFF5555),
    onError = Color(0xFF690005)
)

@Composable
fun RiftTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RiftDarkColorScheme,
        typography = Typography,
        content = content
    )
}
