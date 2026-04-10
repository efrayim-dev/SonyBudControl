package com.budcontrol.sony.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = AmberPrimary,
    onPrimary = SonyBlack,
    primaryContainer = AmberDim,
    onPrimaryContainer = AmberLight,
    secondary = AmbientBlue,
    onSecondary = SonyBlack,
    background = SonyBlack,
    onBackground = TextPrimary,
    surface = SonyDarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = SonyCardSurface,
    onSurfaceVariant = TextSecondary,
    outline = DividerColor,
    outlineVariant = TextTertiary
)

@Composable
fun SonyBudControlTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = SonyBlack.toArgb()
            window.navigationBarColor = SonyBlack.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = SonyTypography,
        content = content
    )
}
