package io.github.ryancontento.tincan.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryancontento.tincan.data.ThemePreference

// Graphite rather than navy, so the window reads as a tool and not as a
// Material demo. The cyan is the line between the cans on the app icon, and is
// spent only on the selected item and the primary action.
private val Ink = Color(0xFF16181D)
private val Panel = Color(0xFF1A1D23)
private val Raised = Color(0xFF21252C)
private val Line = Color(0xFF2E333B)
private val FaintLine = Color(0xFF24282F)
private val Text = Color(0xFFD6DAE0)
private val MutedText = Color(0xFF8C939F)
private val Cyan = Color(0xFF3FC2D7)
private val Rust = Color(0xFFE0736B)

private val Dark = darkColorScheme(
    primary = Cyan,
    onPrimary = Ink,
    secondary = Cyan,
    background = Ink,
    onBackground = Text,
    surface = Panel,
    onSurface = Text,
    surfaceVariant = Raised,
    onSurfaceVariant = MutedText,
    surfaceContainer = Raised,
    surfaceContainerHigh = Color(0xFF262B33),
    outline = Line,
    outlineVariant = FaintLine,
    error = Rust,
    onError = Ink,
    tertiaryContainer = Color(0xFF243840),
    onTertiaryContainer = Text,
)

// Warm neutrals in the Adwaita register, not the blue-white of a Mac window.
private val Light = lightColorScheme(
    primary = Color(0xFF00707F),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF00707F),
    background = Color(0xFFF4F3F1),
    onBackground = Color(0xFF1F2227),
    surface = Color(0xFFFAFAF9),
    onSurface = Color(0xFF1F2227),
    surfaceVariant = Color(0xFFEBEAE7),
    onSurfaceVariant = Color(0xFF63686F),
    surfaceContainer = Color(0xFFEFEEEC),
    surfaceContainerHigh = Color(0xFFE6E5E2),
    outline = Color(0xFFC9C6C1),
    outlineVariant = Color(0xFFDEDCD8),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD9EDF1),
    onTertiaryContainer = Color(0xFF1F2227),
)

/**
 * Smaller than Material's defaults throughout: those are sized for a thumb on a
 * phone, and a desktop window that borrows them wastes most of its space.
 */
private val DesktopType = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontSize = 13.sp, lineHeight = 19.sp),
        bodyMedium = bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp),
        bodySmall = bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
        labelLarge = labelLarge.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.copy(fontSize = 11.sp, lineHeight = 15.sp),
        labelSmall = labelSmall.copy(fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.2.sp),
    )
}

/** Corners this tight read as a desktop widget; a pill reads as a phone button. */
private val DesktopShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(3.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(5.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
)

/** Technical values line up and stay distinguishable from prose in monospace. */
val MonoStyle: TextStyle = TextStyle(fontFamily = FontFamily.Monospace)

@Composable
fun TinCanTheme(preference: ThemePreference = ThemePreference.SYSTEM, content: @Composable () -> Unit) {
    // Remembered so the platform lookup happens once, not on every recomposition.
    val systemDark = remember { systemPrefersDark() }
    val dark = when (preference) {
        ThemePreference.SYSTEM -> systemDark
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
    }

    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = DesktopType,
        shapes = DesktopShapes,
        content = content,
    )
}
