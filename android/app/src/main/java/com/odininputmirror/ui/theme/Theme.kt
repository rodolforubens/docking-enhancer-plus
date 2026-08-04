package com.odininputmirror.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette mirrored from the former React Native theme tokens.
object Palette {
    val screen = Color(0xFF0A0F17)
    val surface = Color(0xFF141B26)
    val surfaceRaised = Color(0xFF1A2331)
    val border = Color(0xFF2B3A52)
    val borderStrong = Color(0xFF4F6EA0)
    val textPrimary = Color(0xFFEEF4FF)
    val textSecondary = Color(0xFFB3C1D8)
    val textMuted = Color(0xFF7F90AC)
    val accent = Color(0xFF55B5FF)
    val accentSoft = Color(0xFF1F344D)
    val danger = Color(0xFFFF7E86)
    val dangerSoft = Color(0xFF4A1F28)
    val focus = Color(0xFF8EC2FF)
    val focusBg = Color(0xFF1E2A3C)
    // Behind a prompt that owns the screen. Dark enough to say "answer this first", sheer enough to
    // leave the row it belongs to legible underneath.
    val scrim = Color(0xCC060A11)
    // Reserved for a controller carrying a mapping the user captured themselves: it marks the card
    // as "yours", distinct from the blue that means "selected".
    val custom = Color(0xFF4ADE80)
    val customSoft = Color(0xFF14301F)
}

private val DarkColors = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Palette.textPrimary,
    background = Palette.screen,
    onBackground = Palette.textPrimary,
    surface = Palette.surface,
    onSurface = Palette.textPrimary,
    surfaceVariant = Palette.surfaceRaised,
    onSurfaceVariant = Palette.textSecondary,
    outline = Palette.border,
    error = Palette.danger,
)

@Composable
fun DockingEnhancerTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content,
    )
}
