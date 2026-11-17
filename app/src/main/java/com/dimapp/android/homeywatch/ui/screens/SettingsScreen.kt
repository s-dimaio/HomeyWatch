package com.dimapp.android.homeywatch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.dimapp.android.homeywatch.BuildConfig
import com.dimapp.android.homeywatch.R
import com.dimapp.android.homeywatch.storage.TokenStorage
import com.dimapp.android.homeywatch.tile.TileSlotManager
import com.dimapp.android.homeywatch.ui.theme.BackgroundDark
import com.dimapp.android.homeywatch.ui.theme.CardBackground
import com.dimapp.android.homeywatch.ui.theme.HomeyBlue
import com.dimapp.android.homeywatch.ui.theme.HomeyLockLockedBgDark
import com.dimapp.android.homeywatch.ui.theme.SurfaceDark
import com.dimapp.android.homeywatch.ui.theme.TextPrimary
import com.dimapp.android.homeywatch.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Data item representing a Homey hub shown in the settings hub list.
 *
 * @property id Unique Homey ID or `"demo"`.
 * @property name Display name of the hub.
 */
private data class _HubDisplayItem(
    val id: String,
    val name: String
)

/**
 * Subtle desaturated coral tint for hub removal icons on dark surfaces.
 */
private val _RemoveIconTint = Color(0xFFE57373)

/**
 * Settings and connection management screen on Wear OS.
 *
 * Supports:
 * - Viewing all authenticated Homey Pro hubs (up to 4) with high-contrast active hub highlighting
 * - Single-tap hub switching and immediate single-tap individual hub removal
 * - Adding a new hub via [onNavigateAddHub]
 * - Cycling background foreground sync interval (`OFF`, `15s`, `30s`, `60s`)
 * - Displaying the installed application version and build code
 * - Rotary bezel/crown scrolling synchronized with pager visibility
 *
 * @public
 * @param storage Secure preference storage managing hub tokens and preferences.
 * @param onNavigateAddHub Callback triggered when the user taps to pair an additional hub.
 * @param onHubSwitched Callback triggered when the active hub changes.
 * @param onLoggedOut Callback triggered when the last remaining hub is removed.
 * @param isVisible Whether this pager tab is currently visible (used to request rotary scroll focus).
 * @param modifier Optional [Modifier] for layout adjustments.
 * @example
 * SettingsScreen(
 *     storage = tokenStorage,
 *     onNavigateAddHub = { navigateToSetup() },
 *     onHubSwitched = { refreshDashboard() },
 *     onLoggedOut = { navigateToSetup() },
 *     isVisible = pagerState.currentPage == 3
 * )
 */
@Composable
fun SettingsScreen(
    storage: TokenStorage,
    onNavigateAddHub: () -> Unit,
    onHubSwitched: () -> Unit,
    onLoggedOut: () -> Unit,
    isVisible: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }

    val isDemoMode = remember { storage.isDemoMode() }
    var activeHubId by remember { mutableStateOf(if (isDemoMode) "demo" else (storage.getSelectedHomeyId() ?: "")) }
    var allHubsMap by remember { mutableStateOf(storage.getAllHubTokens()) }
    var syncInterval by remember { mutableStateOf(storage.getSyncInterval()) }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            focusRequester.requestFocus()
        }
    }

    val hubList = remember(allHubsMap, activeHubId, isDemoMode) {
        val list = mutableListOf<_HubDisplayItem>()
        if (isDemoMode) {
            list.add(_HubDisplayItem("demo", context.getString(R.string.demo_hub_title)))
        }
        if (allHubsMap.isNotEmpty()) {
            allHubsMap.forEach { (id, entry) ->
                if (!isDemoMode || id != "demo") {
                    list.add(_HubDisplayItem(id, entry.name ?: "Homey"))
                }
            }
        } else if (!isDemoMode) {
            val selId = storage.getSelectedHomeyId()
            if (!selId.isNullOrBlank()) {
                list.add(_HubDisplayItem(selId, storage.getSelectedHomeyName() ?: "Homey"))
            }
        }
        list
    }

    /**
     * Switches the active hub and synchronizes Wear OS tile slots.
     *
     * @private
     * @param hubId Identifier of the hub to activate.
     */
    fun _switchHub(hubId: String) {
        if (hubId == activeHubId) return
        if (hubId == "demo") {
            storage.setDemoMode(true)
        } else {
            storage.switchActiveHomey(hubId)
        }
        activeHubId = hubId
        TileSlotManager.syncSlotsAsync(context)
        onHubSwitched()
    }

    /**
     * Immediately removes an authenticated hub and refreshes tile slots.
     *
     * @private
     * @param hubId Identifier of the hub to remove.
     */
    fun _removeHub(hubId: String) {
        val hasRemaining = storage.removeHubToken(hubId)
        TileSlotManager.syncSlotsAsync(context)
        if (hasRemaining) {
            allHubsMap = storage.getAllHubTokens()
            activeHubId = storage.getSelectedHomeyId() ?: ""
            onHubSwitched()
        } else {
            onLoggedOut()
        }
    }

    Scaffold(
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
        modifier = modifier
    ) {
        ScalingLazyColumn(
            state = listState,
            autoCentering = null,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(top = 46.dp, bottom = 32.dp, start = 8.dp, end = 8.dp),
            modifier = Modifier
                .fillMaxSize()
                .background(BackgroundDark)
                .focusRequester(focusRequester)
                .focusable()
                .onRotaryScrollEvent { event ->
                    coroutineScope.launch {
                        listState.scrollBy(event.verticalScrollPixels)
                    }
                    true
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            item {
                Text(
                    text = stringResource(id = R.string.settings_title),
                    style = MaterialTheme.typography.title1,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }

            // ── Section: Connected Hubs ──────────────────────────────────────
            item {
                Text(
                    text = stringResource(id = R.string.settings_hubs_header, hubList.size, TileSlotManager.MAX_SLOTS),
                    style = MaterialTheme.typography.caption1,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                )
            }

            hubList.forEach { hub ->
                item(key = hub.id) {
                    val isActive = hub.id == activeHubId
                    val slotNumber = (1..TileSlotManager.MAX_SLOTS).firstOrNull { storage.getTileSlotHubId(it) == hub.id }
                        ?: (hubList.indexOf(hub) + 1).takeIf { it in 1..TileSlotManager.MAX_SLOTS }

                    _HubItemCard(
                        hub = hub,
                        slotNumber = slotNumber,
                        isActive = isActive,
                        onSelect = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            _switchHub(hub.id)
                        },
                        onRemove = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            _removeHub(hub.id)
                        }
                    )
                }
            }

            // Button to add another hub (if a Tile slot is available)
            if (hubList.size < TileSlotManager.MAX_SLOTS) {
                item {
                    Chip(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onNavigateAddHub()
                        },
                        label = {
                            Text(
                                text = stringResource(id = R.string.settings_btn_add_hub),
                                style = MaterialTheme.typography.button,
                                color = TextPrimary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        icon = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_add),
                                contentDescription = null,
                                tint = HomeyBlue,
                                modifier = Modifier.size(ChipDefaults.IconSize)
                            )
                        },
                        colors = ChipDefaults.primaryChipColors(
                            backgroundColor = CardBackground,
                            contentColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth(0.94f)
                    )
                }
            } else {
                item {
                    Text(
                        text = stringResource(id = R.string.settings_hubs_max_reached),
                        style = MaterialTheme.typography.caption2,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }

            // ── Section: Sync Interval ───────────────────────────────────────
            item {
                _SyncIntervalRow(
                    syncInterval = syncInterval,
                    onCycleInterval = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val next = when (syncInterval) {
                            0 -> 15
                            15 -> 30
                            30 -> 60
                            else -> 0
                        }
                        syncInterval = next
                        storage.saveSyncInterval(next)
                    }
                )
            }

            // ── Section: App Version ─────────────────────────────────────────
            item {
                _AppVersionRow(
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE
                )
            }
        }
    }
}

/**
 * Compact card representing a paired Homey hub in the Settings list.
 *
 * Highlights the active hub via [HomeyLockLockedBgDark] background and a [HomeyBlue] border,
 * keeping text in high-contrast [TextPrimary] and displaying a compact 4-character ID suffix.
 *
 * @private
 * @param hub Hub item to render.
 * @param slotNumber Optional Wear OS tile slot index (1..4).
 * @param isActive Whether this hub is currently selected.
 * @param onSelect Callback invoked when tapping the card to switch hub.
 * @param onRemove Callback invoked when tapping the remove button.
 */
@Composable
private fun _HubItemCard(
    hub: _HubDisplayItem,
    slotNumber: Int?,
    isActive: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit
) {
    val cardShape = RoundedCornerShape(16.dp)
    val cardBackground = if (isActive) HomeyLockLockedBgDark else SurfaceDark
    val borderModifier = if (isActive) {
        Modifier.border(width = 1.5.dp, color = HomeyBlue, shape = cardShape)
    } else {
        Modifier
    }

    Row(
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .defaultMinSize(minHeight = 48.dp)
            .clip(cardShape)
            .background(cardBackground)
            .then(borderModifier)
            .clickable(onClick = onSelect)
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = hub.name,
                style = MaterialTheme.typography.title2,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val compactId = _formatCompactId(hub.id)
            val idSubtitle = if (slotNumber != null) {
                stringResource(id = R.string.settings_hub_slot_id, slotNumber, compactId)
            } else {
                stringResource(id = R.string.settings_homey_id, compactId)
            }
            Text(
                text = idSubtitle,
                style = MaterialTheme.typography.caption2,
                color = if (isActive) TextPrimary.copy(alpha = 0.78f) else TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        CompactButton(
            onClick = onRemove,
            colors = ButtonDefaults.secondaryButtonColors(
                backgroundColor = if (isActive) Color.Black.copy(alpha = 0.30f) else CardBackground,
                contentColor = _RemoveIconTint
            ),
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_close),
                contentDescription = stringResource(id = R.string.settings_btn_delete_hub),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Full-width clickable row displaying and cycling the foreground refresh interval.
 *
 * @private
 * @param syncInterval Current interval in seconds (`0` indicates disabled).
 * @param onCycleInterval Callback invoked when the user taps the row to cycle values.
 */
@Composable
private fun _SyncIntervalRow(
    syncInterval: Int,
    onCycleInterval: () -> Unit
) {
    val isSyncEnabled = syncInterval > 0

    Row(
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .clickable(onClick = onCycleInterval)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_refresh),
            contentDescription = null,
            tint = if (isSyncEnabled) HomeyBlue else TextSecondary,
            modifier = Modifier.size(18.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = stringResource(id = R.string.settings_sync_interval),
            style = MaterialTheme.typography.button,
            color = TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (isSyncEnabled) HomeyLockLockedBgDark else CardBackground)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isSyncEnabled) "${syncInterval}s" else stringResource(id = R.string.settings_sync_off),
                style = MaterialTheme.typography.caption1,
                fontWeight = FontWeight.SemiBold,
                color = if (isSyncEnabled) TextPrimary else TextSecondary,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * Read-only row displaying the installed application version name and build code.
 *
 * @private
 * @param versionName Semantic version string from [BuildConfig.VERSION_NAME].
 * @param versionCode Numeric build code from [BuildConfig.VERSION_CODE].
 */
@Composable
private fun _AppVersionRow(
    versionName: String,
    versionCode: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .defaultMinSize(minHeight = 44.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(id = R.string.settings_app_version),
            style = MaterialTheme.typography.button,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(CardBackground)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(id = R.string.settings_app_version_value, versionName, versionCode),
                style = MaterialTheme.typography.caption1,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * Formats a Homey hub identifier for compact display on small Wear OS screens.
 *
 * Keeps the last 4 characters prefixed with an ellipsis when the ID exceeds 6 characters.
 *
 * @private
 * @param id Full Homey hub identifier.
 * @return Compact identifier string suitable for subtitle labels.
 */
private fun _formatCompactId(id: String): String {
    return if (id.length > 6) "\u2026${id.takeLast(4)}" else id
}
