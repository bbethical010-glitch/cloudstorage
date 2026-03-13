package com.pratham.cloudstorage.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlue,
    secondary = HighlightPurple,
    tertiary = SuccessGreen,
    background = DarkBackground,
    surface = DarkSurface,
    onPrimary = TextTitle,
    onSecondary = TextTitle,
    onTertiary = TextTitle,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline = DarkDivider,
    surfaceVariant = DarkPanel
)

@Composable
fun CloudStorageTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
