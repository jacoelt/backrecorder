package com.backrecorder.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ThemeColorBox(name: String, color: Color, textColor: Color = Color.White) {
    Surface(
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = "$name (${color.toHexString()})",
                color = textColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp)
            )
        }
    }
}

private fun Color.toHexString(): String {
    return String.format("#%02X%02X%02X",
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt()
    )
}

@Composable
fun ThemePreviewScreen() {
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Theme Color Preview",
            style = MaterialTheme.typography.titleLarge,
            color = colors.onBackground,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        ThemeColorBox("primary", colors.primary, colors.onPrimary)
        ThemeColorBox("onPrimary", colors.onPrimary, colors.primary)
        ThemeColorBox("secondary", colors.secondary, colors.onSecondary)
        ThemeColorBox("onSecondary", colors.onSecondary, colors.secondary)
        ThemeColorBox("tertiary", colors.tertiary, colors.onTertiary)
        ThemeColorBox("onTertiary", colors.onTertiary, colors.tertiary)
        ThemeColorBox("background", colors.background, colors.onBackground)
        ThemeColorBox("onBackground", colors.onBackground, colors.background)
        ThemeColorBox("surface", colors.surface, colors.onSurface)
        ThemeColorBox("onSurface", colors.onSurface, colors.surface)
        ThemeColorBox("surfaceVariant", colors.surfaceVariant, colors.onSurfaceVariant)
        ThemeColorBox("onSurfaceVariant", colors.onSurfaceVariant, colors.surfaceVariant)
        ThemeColorBox("error", colors.error, colors.onError)
        ThemeColorBox("onError", colors.onError, colors.error)
        ThemeColorBox("outline", colors.outline, colors.onBackground)
    }
}

@Preview(showBackground = true)
@Composable
fun LightThemePreview() {
    BackRecorderTheme(darkTheme = false) {
        ThemePreviewScreen()
    }
}

@Preview(showBackground = true)
@Composable
fun DarkThemePreview() {
    BackRecorderTheme(darkTheme = true) {
        ThemePreviewScreen()
    }
}
