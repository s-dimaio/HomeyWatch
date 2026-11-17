package com.dimapp.android.homeywatch.ui.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

private val _HomeyColorPalette = Colors(
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

/**
 * Root Wear OS Material theme wrapper for HomeyWatch using AMOLED-friendly colors and typography.
 *
 * @public
 * @param content Composable UI tree rendered within the theme.
 * @example
 * ```kotlin
 * HomeyWatchTheme {
 *     MainScreen()
 * }
 * ```
 */
@Composable
fun HomeyWatchTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colors = _HomeyColorPalette,
        typography = HomeyTypography,
        content = content
    )
}
