package com.backrecorderapp.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Blue500,
    onPrimary = White,
    primaryContainer = Blue700,
    secondary = Cyan500,
    background = Gray900,
    surface = Gray800,
    surfaceVariant = Gray700,
    onBackground = Gray100,
    onSurface = Gray200,
    onSurfaceVariant = Gray400,
    error = Red500
)

private val LightColorScheme = lightColorScheme(
    primary = Blue700,
    onPrimary = White,
    primaryContainer = Blue500,
    secondary = Cyan500,
    background = Gray100,
    surface = White,
    surfaceVariant = Gray200,
    onBackground = Gray900,
    onSurface = Gray800,
    onSurfaceVariant = Gray600,
    error = Red500
)

@Composable
fun BackRecorderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // No window.statusBarColor here. Edge-to-edge is enforced from API 36 (the
            // windowOptOutEdgeToEdgeEnforcement attribute is ignored), the system bars are
            // drawn behind the app, and Window.setStatusBarColor is deprecated and a no-op
            // once the app targets 35+. Only the icon tint is still ours to set.
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
