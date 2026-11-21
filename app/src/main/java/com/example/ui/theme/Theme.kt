package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = SignalWhite,
    onPrimary = ObsidianBg,
    primaryContainer = GraphiteElevated,
    onPrimaryContainer = SignalWhite,
    secondary = ElectricCyan,
    onSecondary = ObsidianBg,
    secondaryContainer = GraphiteElevated,
    onSecondaryContainer = ElectricCyan,
    tertiary = EmeraldActive,
    background = ObsidianBg,
    onBackground = SignalWhite,
    surface = GraphiteSurface,
    onSurface = SignalWhite,
    surfaceVariant = GraphiteElevated,
    onSurfaceVariant = MutedSlate,
    outline = HairlineBorder,
    error = CoralError
)

private val LightColorScheme = lightColorScheme(
    primary = InkDark,
    onPrimary = PureWhiteSurface,
    primaryContainer = SoftGrayContainer,
    onPrimaryContainer = InkDark,
    secondary = ElectricCyanDeep,
    onSecondary = PureWhiteSurface,
    secondaryContainer = SoftGrayContainer,
    onSecondaryContainer = ElectricCyanDeep,
    tertiary = EmeraldActive,
    background = AlabasterBg,
    onBackground = InkDark,
    surface = PureWhiteSurface,
    onSurface = InkDark,
    surfaceVariant = SoftGrayContainer,
    onSurfaceVariant = InkMuted,
    outline = LightHairline,
    error = CoralError
)

@Composable
fun QuickCircleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = QuickCircleTypography,
        content = content
    )
}
