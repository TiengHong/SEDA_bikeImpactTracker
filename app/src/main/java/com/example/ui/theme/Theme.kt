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
    primary = BluePrimary,
    onPrimary = Slate100,
    primaryContainer = Slate800,
    onPrimaryContainer = Slate100,
    secondary = BlueSecondary,
    onSecondary = Slate900,
    tertiary = AmberWarning,
    onTertiary = Slate900,
    background = Slate900,
    onBackground = Slate100,
    surface = Slate800,
    onSurface = Slate100,
    surfaceVariant = Slate700,
    onSurfaceVariant = Slate200,
    outline = Slate600,
    outlineVariant = Slate700,
    error = CoralCritical,
    onError = Slate100
)

private val LightColorScheme = lightColorScheme(
    primary = BluePrimary,
    onPrimary = Slate100,
    primaryContainer = Slate100,
    onPrimaryContainer = Slate900,
    secondary = BlueSecondary,
    onSecondary = Slate100,
    tertiary = AmberWarning,
    onTertiary = Slate900,
    background = OffWhiteBg,
    onBackground = Slate900,
    surface = CardWhite,
    onSurface = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate800,
    outline = BorderLight,
    outlineVariant = BorderLight,
    error = CoralCritical,
    onError = Slate100
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Let's use our custom brand identity colors by default for high-contrast safety telemetry!
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
