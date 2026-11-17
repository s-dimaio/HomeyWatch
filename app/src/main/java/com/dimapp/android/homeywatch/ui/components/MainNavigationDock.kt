package com.dimapp.android.homeywatch.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.dimapp.android.homeywatch.R

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
