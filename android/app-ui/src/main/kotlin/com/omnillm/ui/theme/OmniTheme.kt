package com.omnillm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Risk and degraded states must not use default "success green" (UX-SAFETY-COPY).
 * Severity uses semantic roles; color is never the sole signal (UX-A11Y-I18N).
 */
private val OmniLightColors = lightColorScheme(
    primary = Color(0xFF1B4F72),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF5D6D7E),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF7D6608),
    error = Color(0xFF922B21),
    onError = Color(0xFFFFFFFF),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF1B2631),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B2631),
    surfaceVariant = Color(0xFFE5E8EB),
    onSurfaceVariant = Color(0xFF424949),
    outline = Color(0xFF7F8C8D),
)

private val OmniDarkColors = darkColorScheme(
    primary = Color(0xFF85C1E9),
    onPrimary = Color(0xFF0B1C2C),
    secondary = Color(0xFFB2BABB),
    onSecondary = Color(0xFF1B2631),
    tertiary = Color(0xFFF7DC6F),
    error = Color(0xFFF1948A),
    onError = Color(0xFF2C0A06),
    background = Color(0xFF12171C),
    onBackground = Color(0xFFECF0F1),
    surface = Color(0xFF1B242B),
    onSurface = Color(0xFFECF0F1),
    surfaceVariant = Color(0xFF2C3E50),
    onSurfaceVariant = Color(0xFFD5D8DC),
    outline = Color(0xFF95A5A6),
)

val LocalDensityMode = staticCompositionLocalOf { DensityMode.STANDARD }

@Composable
fun OmniTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    densityMode: DensityMode = DensityMode.STANDARD,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDensityMode provides densityMode) {
        MaterialTheme(
            colorScheme = if (darkTheme) OmniDarkColors else OmniLightColors,
            content = content,
        )
    }
}
