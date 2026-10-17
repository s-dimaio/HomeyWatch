package com.dimapp.android.homeywatch.ui.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

private val HomeyColorPalette = Colors(
    primary = HomeyBlue,
    primaryVariant = HomeyBlueDark,
    secondary = HomeyLightActive,
    background = BackgroundDark,
    surface = SurfaceDark,
    onPrimary = TextPrimary,
    onSecondary = BackgroundDark,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun HomeyWatchTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colors = HomeyColorPalette,
        typography = HomeyTypography,
        content = content
    )
}
