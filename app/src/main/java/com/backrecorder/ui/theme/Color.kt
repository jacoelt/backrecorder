package com.backrecorder.ui.theme

import androidx.compose.ui.graphics.Color

// ======================
// Base palette
// ======================

// Primary palette (blue tones)
val BaseBlue = Color(0xFF1976D2)
val BaseLightBlue = Color(0xFF64B5F6)

// Secondary palette (cyan / turquoise tones)
val BaseCyan = Color(0xFF00BCD4)
val BaseLightCyan = Color(0xFF4DD0E1)

// Tertiary palette (salmon / orange tones)
val BaseSalmon = Color(0xFFFF7043)
val BaseSoftSalmon = Color(0xFFFF8A65)

// Backgrounds & surfaces
val BaseLightBackground = Color(0xFFF0F7FF)
val BaseLightSurface = Color(0xFFF0F7FF)
val BaseLightSurfaceVariant = Color(0xFFDBEDFF)

val BaseDarkBackground = Color(0xFF0E1525)
val BaseDarkSurface = Color(0xFF162238)
val BaseDarkSurfaceVariant = Color(0xFF1E3350)

// Texts and contrast tones
val BaseLightTextPrimary = Color(0xFF0D1B2A)
val BaseLightTextSecondary = Color(0xFF415A77)
val BaseDarkTextPrimary = Color(0xFFEAF2FF)
val BaseDarkTextSecondary = Color(0xFFB0C4DE)

// Outline & error
val BaseLightOutline = Color(0xFF90A4AE)
val BaseDarkOutline = Color(0xFF4A5C7A)
val BaseLightError = Color(0xFFD32F2F)
val BaseDarkError = Color(0xFFFF6659)


// ======================
// Light theme colors
// ======================
val LightPrimary = BaseBlue
val LightOnPrimary = Color.White

val LightSecondary = BaseCyan
val LightOnSecondary = Color.White

val LightTertiary = BaseSalmon
val LightOnTertiary = Color.White

val LightBackground = BaseLightBackground
val LightOnBackground = BaseLightTextPrimary

val LightSurface = BaseLightSurface
val LightOnSurface = BaseLightTextPrimary

val LightSurfaceVariant = BaseLightSurfaceVariant
val LightOnSurfaceVariant = BaseLightTextSecondary

val LightError = BaseLightError
val LightOnError = Color.White

val LightOutline = BaseLightOutline


// ======================
// Dark theme colors
// ======================
val DarkPrimary = BaseLightBlue
val DarkOnPrimary = Color.Black

val DarkSecondary = BaseLightCyan
val DarkOnSecondary = Color.Black

val DarkTertiary = BaseSoftSalmon
val DarkOnTertiary = Color.Black

val DarkBackground = BaseDarkBackground
val DarkOnBackground = BaseDarkTextPrimary

val DarkSurface = BaseDarkSurface
val DarkOnSurface = BaseDarkTextPrimary

val DarkSurfaceVariant = BaseDarkSurfaceVariant
val DarkOnSurfaceVariant = BaseDarkTextSecondary

val DarkError = BaseDarkError
val DarkOnError = Color.Black

val DarkOutline = BaseDarkOutline
