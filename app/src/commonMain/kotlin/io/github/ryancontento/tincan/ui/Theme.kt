package io.github.ryancontento.tincan.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Pulled from the app icon so the window and its icon read as one thing: the
// slate ground, the steel of the cans, and the cyan of the line between them.
private val Cyan = Color(0xFF22D3EE)
private val Teal = Color(0xFF5EEAD4)
private val Slate900 = Color(0xFF0B1220)
private val Slate800 = Color(0xFF111C2E)
private val Slate700 = Color(0xFF1E293B)
private val Steel = Color(0xFFCBD5E1)

private val Dark = darkColorScheme(
    primary = Cyan,
    onPrimary = Slate900,
    secondary = Teal,
    background = Slate900,
    onBackground = Steel,
    surface = Slate800,
    onSurface = Steel,
    surfaceVariant = Slate700,
    error = Color(0xFFF87171),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0E7490),
    secondary = Color(0xFF0F766E),
    background = Color(0xFFF6F8FB),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFB91C1C),
)

@Composable
fun TinCanTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
