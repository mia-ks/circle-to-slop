package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
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
    primary = ElectricCyanDeep,
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
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = QuickCircleTypography,
        content = content
    )
}
