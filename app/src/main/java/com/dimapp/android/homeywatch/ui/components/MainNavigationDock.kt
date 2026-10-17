package com.dimapp.android.homeywatch.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Icon
import com.dimapp.android.homeywatch.R
import com.dimapp.android.homeywatch.ui.theme.HomeyBlue

/**
 * Top-level application destinations for Wear OS.
 *
 * @property id Unique identifier of the destination.
 * @property iconRes Drawable resource representing the tab.
 * @property titleRes Localized title string resource.
 */
enum class MainNavTab(
    val id: String,
    @get:DrawableRes val iconRes: Int,
    @get:StringRes val titleRes: Int
) {
    HOME("home", R.drawable.selector_home, R.string.nav_home),
    DEVICES("devices", R.drawable.selector_devices, R.string.nav_devices),
    FLOWS("flows", R.drawable.selector_flow, R.string.nav_flows),
    SETTINGS("settings", R.drawable.selector_settings, R.string.nav_settings)
}

/**
 * Centered bottom carousel dock displaying the 4 main application destinations.
 *
 * Mirrors the ergonomics and visual design of the device detail carousel dock:
 * - Positioned at bottom center with a protective gradient scrim.
 * - Highlights active tab icon while dimming inactive slots.
 * - Dispatches haptic cues and transitions on single tap.
 *
 * @public
 * @param selectedIndex Index of the currently active page (0..3).
 * @param onSelectTab Callback invoked when a tab slot is clicked.
 * @param modifier Layout modifier.
 * @example
 * MainNavigationDock(
 *     selectedIndex = pagerState.currentPage,
 *     onSelectTab = { index -> coroutineScope.launch { pagerState.animateScrollToPage(index) } }
 * )
 */
@Composable
fun MainNavigationDock(
    selectedIndex: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = MainNavTab.values()
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Bottom gradient scrim protecting list items beneath the dock
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.75f),
                            Color.Black
                        )
                    )
                )
        )

        // Rounded pill dock
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(bottom = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0x801C1C1E))
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            tabs.forEachIndexed { index, tab ->
                val isSelected = index == selectedIndex
                _NavDockSlot(
                    tab = tab,
                    isSelected = isSelected,
                    onClick = {
                        if (!isSelected) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectTab(index)
                        }
                    }
                )
            }
        }
    }
}

/**
 * Single clickable icon slot in the bottom navigation dock.
 *
 * @private
 * @param tab Destination definition.
 * @param isSelected Whether this slot corresponds to the current active page.
 * @param onClick Callback triggered on click.
 */
@Composable
private fun _NavDockSlot(
    tab: MainNavTab,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(24.dp)
            .clickable(onClick = onClick)
    ) {
        Icon(
            painter = painterResource(id = tab.iconRes),
            contentDescription = stringResource(id = tab.titleRes),
            tint = if (isSelected) HomeyBlue else Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(if (isSelected) 18.dp else 14.dp)
        )
    }
}
