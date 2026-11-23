package com.android.abdullah.voicerecorder.mp3.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val StudioDarkColorScheme = darkColorScheme(
    primary = CoralRecord,
    onPrimary = AlabasterText,
    primaryContainer = CoralRecordContainer,
    onPrimaryContainer = CoralSoft,
    secondary = CyanAccent,
    onSecondary = StudioObsidian,
    secondaryContainer = StudioSurfaceHigh,
    onSecondaryContainer = AlabasterText,
    tertiary = AmberMeter,
    onTertiary = StudioObsidian,
    background = StudioObsidian,
    onBackground = AlabasterText,
    surface = StudioSurface,
    onSurface = AlabasterText,
    surfaceVariant = StudioSurfaceElevated,
    onSurfaceVariant = MutedSlateText,
    surfaceContainerLowest = StudioObsidian,
    surfaceContainerLow = StudioSurface,
    surfaceContainer = StudioSurfaceElevated,
    surfaceContainerHigh = StudioSurfaceHigh,
    outline = StudioOutline,
    outlineVariant = StudioOutlineSubtle,
    error = CoralRecord,
    onError = AlabasterText
)

val StudioShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun VoiceRecorderTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = StudioDarkColorScheme,
        typography = AppTypography,
        shapes = StudioShapes,
        content = content
    )
}
