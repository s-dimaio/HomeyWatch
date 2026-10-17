package com.dimapp.android.homeywatch.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.SplitToggleChip
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChipDefaults
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.dimapp.android.homeywatch.R
import com.dimapp.android.homeywatch.repository.models.HomeyDevice
import com.dimapp.android.homeywatch.ui.screens.resolveDeviceTabs
import com.dimapp.android.homeywatch.ui.theme.HomeyBlue
import com.dimapp.android.homeywatch.ui.theme.HomeyLightActive
import com.dimapp.android.homeywatch.ui.theme.HomeyLightActiveBgDark
import com.dimapp.android.homeywatch.ui.theme.HomeyLightActiveIcon
import com.dimapp.android.homeywatch.ui.theme.HomeyLightInactiveBgDark
import com.dimapp.android.homeywatch.ui.theme.HomeyLightInactiveIconDark
import com.dimapp.android.homeywatch.ui.theme.HomeyLockLockedBgDark
import com.dimapp.android.homeywatch.ui.theme.HomeyLockLockedIcon
import com.dimapp.android.homeywatch.ui.theme.HomeyLockUnlockedBgDark
import com.dimapp.android.homeywatch.ui.theme.HomeyLockUnlockedIconDark
import com.dimapp.android.homeywatch.ui.theme.HomeyStatusDotAmber
import com.dimapp.android.homeywatch.ui.theme.HomeyThermostat
import com.dimapp.android.homeywatch.ui.theme.TextSecondary

/**
 * Data holder for resolved Quick Action button styling.
 *
 * @property iconRes Drawable resource ID for the quick action icon.
 * @property circleBgColor Background color of the quick action circular button.
 * @property iconTintColor Tint color applied to the quick action icon.
 */
private data class QuickActionStyle(
    val iconRes: Int,
    val circleBgColor: Color,
    val iconTintColor: Color
)

/**
 * Wear OS component for interacting with Homey smart home devices.
 *
 * Conditional display architecture:
 * - If [device.hasQuickAction] is true: Renders a [SplitToggleChip] with dual-tap areas:
 *   left side for opening detailed controls or primary action, right circular button
 *   dedicated to the official Homey quick action.
 * - If [device.hasQuickAction] is false: Renders a standard [Chip] across the full width,
 *   seamlessly opening the detail view upon single tap (no toggle control on the right).
 *
 * @public
 * @param device Target [HomeyDevice] to display and interact with.
 * @param isOperating Whether an asynchronous state mutation is currently in flight.
 * @param onToggle Callback invoked when the quick action or primary toggle is triggered.
 * @param onOpenDetail Callback invoked to navigate to the device's full detail screen.
 * @param modifier Optional [Modifier] for positioning and layout adjustments.
 * @example
 * DeviceToggleChip(
 *     device = homeyDevice,
 *     isOperating = isOperating,
 *     onToggle = { targetState -> viewModel.toggleDevice(device, targetState) },
 *     onOpenDetail = { dev -> navController.navigate("device_detail/${dev.id}") }
 * )
 */
@Composable
fun DeviceToggleChip(
    device: HomeyDevice,
    isOperating: Boolean = false,
    onToggle: (Boolean) -> Unit,
    onOpenDetail: (HomeyDevice) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (device.hasQuickAction) {
        _DeviceSplitToggleChip(
            device = device,
            isOperating = isOperating,
            onToggle = onToggle,
            onOpenDetail = onOpenDetail,
            modifier = modifier
        )
    } else {
        _DeviceStandardChip(
            device = device,
            onOpenDetail = onOpenDetail,
            modifier = modifier
        )
    }
}

/**
 * Wear OS standard full-width Chip for devices without a dedicated quick action (e.g. pure thermostats).
 *
 * @private
 * @param device Target [HomeyDevice].
 * @param onOpenDetail Callback invoked when clicking the chip.
 * @param modifier Optional [Modifier].
 */
@Composable
private fun _DeviceStandardChip(
    device: HomeyDevice,
    onOpenDetail: (HomeyDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val deviceIconRes = _resolveDeviceIcon(device)

    Chip(
        onClick = {
            if (device.isAvailable) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onOpenDetail(device)
            }
        },
        enabled = device.isAvailable,
        icon = {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                val fallbackPainter = painterResource(id = deviceIconRes)
                val iconPainter = if (!device.iconUrl.isNullOrBlank()) {
                    rememberAsyncImagePainter(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(device.iconUrl)
                            .decoderFactory(SvgDecoder.Factory())
                            .crossfade(true)
                            .build(),
                        placeholder = fallbackPainter,
                        error = fallbackPainter,
                        fallback = fallbackPainter
                    )
                } else {
                    fallbackPainter
                }

                Icon(
                    painter = iconPainter,
                    contentDescription = device.name,
                    tint = if (device.isAvailable) Color.White else Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        label = {
            Text(
                text = device.name,
                style = MaterialTheme.typography.button,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        secondaryLabel = {
            _DeviceSecondaryLabel(device = device)
        },
        colors = ChipDefaults.primaryChipColors(
            backgroundColor = Color(0xFF2C2C2E)
        ),
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * Wear OS SplitToggleChip component for devices with a dedicated quick action.
 *
 * @private
 * @param device Target [HomeyDevice].
 * @param isOperating Whether an asynchronous state mutation is currently in flight.
 * @param onToggle Callback invoked when the quick action is triggered.
 * @param onOpenDetail Callback invoked to navigate to the device's full detail screen.
 * @param modifier Optional [Modifier].
 */
@Composable
private fun _DeviceSplitToggleChip(
    device: HomeyDevice,
    isOperating: Boolean = false,
    onToggle: (Boolean) -> Unit,
    onOpenDetail: (HomeyDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val hasAdvancedControls = !device.isButtonAction && !device.isQuickActionButtonAction &&
        (resolveDeviceTabs(device).size > 1 || device.isLight || device.isThermostat || device.isDimmable || device.hasColor)
    val deviceIconRes = _resolveDeviceIcon(device)
    val quickActionStyle = _resolveQuickActionStyle(device)

    SplitToggleChip(
        checked = device.isQuickActionActive,
        onCheckedChange = {
            if (!isOperating && device.isAvailable) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (device.isQuickActionButtonAction) {
                    onToggle(true)
                } else {
                    onToggle(!device.isQuickActionActive)
                }
            }
        },
        onClick = {
            if (!isOperating && device.isAvailable) {
                if (hasAdvancedControls) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpenDetail(device)
                } else if (device.isQuickActionButtonAction) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle(true)
                } else {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle(!device.isQuickActionActive)
                }
            }
        },
        label = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Device Icon (always crisp white matching Homey design)
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val fallbackPainter = painterResource(id = deviceIconRes)
                    val iconPainter = if (!device.iconUrl.isNullOrBlank()) {
                        rememberAsyncImagePainter(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(device.iconUrl)
                                .decoderFactory(SvgDecoder.Factory())
                                .crossfade(true)
                                .build(),
                            placeholder = fallbackPainter,
                            error = fallbackPainter,
                            fallback = fallbackPainter
                        )
                    } else {
                        fallbackPainter
                    }

                    Icon(
                        painter = iconPainter,
                        contentDescription = device.name,
                        tint = if (device.isAvailable) Color.White else Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Device Name & Status Text
                Column(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.button,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    _DeviceSecondaryLabel(device = device)
                }
            }
        },
        toggleControl = {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(quickActionStyle.circleBgColor),
                contentAlignment = Alignment.Center
            ) {
                if (isOperating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        indicatorColor = quickActionStyle.iconTintColor
                    )
                } else {
                    Icon(
                        painter = painterResource(id = quickActionStyle.iconRes),
                        contentDescription = device.name,
                        tint = quickActionStyle.iconTintColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        enabled = device.isAvailable && !isOperating,
        colors = ToggleChipDefaults.splitToggleChipColors(),
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * Renders the secondary label with official Homey status indicators (e.g. amber dot for active light).
 *
 * @private
 * @param device Target [HomeyDevice].
 * @param modifier Optional [Modifier].
 */
@Composable
private fun _DeviceSecondaryLabel(
    device: HomeyDevice,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val secondaryText = _buildSecondaryLabelText(device, context)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (device.isLight && device.isActive) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(HomeyStatusDotAmber)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = secondaryText,
            style = MaterialTheme.typography.caption2,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Resolves the Quick Action visual style (icon resource, circle background, icon tint)
 * according to official Homey specifications.
 *
 * @private
 * @param device Target [HomeyDevice].
 * @return [QuickActionStyle] containing the resolved icon, background color, and tint color.
 */
private fun _resolveQuickActionStyle(device: HomeyDevice): QuickActionStyle {
    val qaCap = device.quickActionCapability ?: device.primaryCapability
    val isQaActive = device.isQuickActionActive

    return when {
        // 1. Lights and Plugs (onoff capability)
        device.isLight || qaCap == "onoff" -> {
            if (isQaActive) {
                QuickActionStyle(
                    iconRes = R.drawable.ic_homey_power,
                    circleBgColor = HomeyLightActiveBgDark,
                    iconTintColor = HomeyLightActiveIcon
                )
            } else {
                QuickActionStyle(
                    iconRes = R.drawable.ic_homey_power,
                    circleBgColor = HomeyLightInactiveBgDark,
                    iconTintColor = HomeyLightInactiveIconDark
                )
            }
        }
        // 2. Locks (locked capability: isQaActive == true means unlocked in Homey)
        device.deviceClass == "lock" || qaCap == "locked" -> {
            val isLocked = !isQaActive
            if (isLocked) {
                QuickActionStyle(
                    iconRes = R.drawable.ic_homey_door_locked,
                    circleBgColor = HomeyLockLockedBgDark,
                    iconTintColor = HomeyLockLockedIcon
                )
            } else {
                QuickActionStyle(
                    iconRes = R.drawable.ic_homey_door_unlocked,
                    circleBgColor = HomeyLockUnlockedBgDark,
                    iconTintColor = HomeyLockUnlockedIconDark
                )
            }
        }
        // 2b. Momentary unlatch actions (button.unlatch, unlatch)
        qaCap == "unlatch" || qaCap.startsWith("unlatch.") || qaCap == "button.unlatch" -> {
            QuickActionStyle(
                iconRes = R.drawable.ic_homey_door_unlocked,
                circleBgColor = HomeyLockUnlockedBgDark,
                iconTintColor = HomeyLockUnlockedIconDark
            )
        }
        // 3. Garage doors and gates (garagedoor_closed capability: isQaActive == true means open in Homey)
        device.deviceClass == "garagedoor" || qaCap == "garagedoor_closed" -> {
            val isClosed = !isQaActive
            if (isClosed) {
                QuickActionStyle(
                    iconRes = R.drawable.ic_homey_garage_close,
                    circleBgColor = HomeyLockLockedBgDark,
                    iconTintColor = HomeyLockLockedIcon
                )
            } else {
                QuickActionStyle(
                    iconRes = R.drawable.ic_homey_garage_open,
                    circleBgColor = HomeyLockUnlockedBgDark,
                    iconTintColor = HomeyLockUnlockedIconDark
                )
            }
        }
        // 4. Other categories, scene triggers, and uncategorized push buttons
        else -> {
            QuickActionStyle(
                iconRes = R.drawable.ic_homey_general_button,
                circleBgColor = HomeyLightInactiveBgDark,
                iconTintColor = if (isQaActive) HomeyBlue else HomeyLightInactiveIconDark
            )
        }
    }
}

/**
 * Builds secondary label text indicating current state, temperature, or zone.
 *
 * @private
 * @param device Target [HomeyDevice].
 * @param context Application [Context] for localized strings.
 * @return Localized status string.
 */
private fun _buildSecondaryLabelText(device: HomeyDevice, context: Context): String {
    return when {
        device.isThermostat -> {
            val target = device.targetTemperature
            val current = device.currentTemperature
            when {
                target != null && current != null -> {
                    "${String.format("%.1f", current)}°C • ${String.format("%.1f", target)}°C"
                }
                target != null -> {
                    context.getString(R.string.device_thermostat_target, "${String.format("%.1f", target)}°C")
                }
                current != null -> {
                    context.getString(R.string.device_thermostat_current, "${String.format("%.1f", current)}°C")
                }
                else -> device.zoneName
            }
        }
        device.isLight -> {
            if (device.isDimmable && device.isActive && device.dimValue != null) {
                val pct = (device.dimValue * 100).toInt().coerceIn(0, 100)
                "${context.getString(R.string.device_state_on)} • $pct%"
            } else if (device.isActive) {
                context.getString(R.string.device_state_on)
            } else {
                context.getString(R.string.device_state_off)
            }
        }
        device.isButtonAction -> {
            context.getString(R.string.device_action_push)
        }
        device.deviceClass == "garagedoor" -> {
            if (device.isActive) context.getString(R.string.device_state_open) else context.getString(R.string.device_state_closed)
        }
        device.deviceClass == "lock" -> {
            if (device.isActive) context.getString(R.string.device_state_unlocked) else context.getString(R.string.device_state_locked)
        }
        else -> device.zoneName
    }
}

/**
 * Resolves drawable icon resource for the device's primary icon on the left of the chip.
 *
 * @private
 * @param device Target [HomeyDevice].
 * @return Drawable resource ID.
 */
private fun _resolveDeviceIcon(device: HomeyDevice): Int {
    return when {
        device.isThermostat -> R.drawable.ic_thermostat
        device.isLight -> R.drawable.ic_light_on
        device.deviceClass == "garagedoor" -> {
            if (device.isActive) R.drawable.ic_garage_open else R.drawable.ic_garage_closed
        }
        device.deviceClass == "lock" -> {
            if (device.isActive) R.drawable.ic_lock_open else R.drawable.ic_lock_closed
        }
        else -> R.drawable.ic_device_generic
    }
}
