package com.dimapp.android.homeywatch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.dimapp.android.homeywatch.R
import com.dimapp.android.homeywatch.auth.HomeyAuthRepository
import com.dimapp.android.homeywatch.storage.TokenStorage
import com.dimapp.android.homeywatch.tile.TileSlotManager
import com.dimapp.android.homeywatch.ui.theme.HomeyBlue
import com.dimapp.android.homeywatch.ui.theme.TextPrimary
import com.dimapp.android.homeywatch.ui.theme.TextSecondary

/**
 * Data item representing a Homey hub shown in the settings hub list.
 *
 * @property id Unique Homey ID or "demo".
 * @property name Display name of the hub.
 */
private data class HubDisplayItem(
    val id: String,
    val name: String
)

/**
 * Settings and connection management screen on Wear OS.
 *
 * Supports:
 * - Viewing all authenticated Homey Pro hubs (up to 4)
 * - Tapping a hub to switch the active dashboard
 * - Removing individual hubs
 * - Adding a new hub via [onNavigateAddHub]
 * - Background dashboard sync interval toggle
 * - Disconnecting all hubs
 *
 * @public
 * @param authRepo Authentication repository.
 * @param storage Secure preference storage.
 * @param onNavigateAddHub Callback triggered when user wants to pair an additional hub.
 * @param onHubSwitched Callback triggered when user switches active hub.
 * @param onLoggedOut Callback triggered when all hubs are removed/logged out.
 */
@Composable
fun SettingsScreen(
    authRepo: HomeyAuthRepository,
    storage: TokenStorage,
    onNavigateAddHub: () -> Unit,
    onHubSwitched: () -> Unit,
    onLoggedOut: () -> Unit
) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()

    val isDemoMode = remember { storage.isDemoMode() }
    var activeHubId by remember { mutableStateOf(if (isDemoMode) "demo" else (storage.getSelectedHomeyId() ?: "")) }
    var allHubsMap by remember { mutableStateOf(storage.getAllHubTokens()) }
    var syncInterval by remember { mutableStateOf(storage.getSyncInterval()) }

    val hubList = remember(allHubsMap, activeHubId, isDemoMode) {
        val list = mutableListOf<HubDisplayItem>()
        if (isDemoMode) {
            list.add(HubDisplayItem("demo", context.getString(R.string.demo_hub_title)))
        }
        if (allHubsMap.isNotEmpty()) {
            allHubsMap.forEach { (id, entry) ->
                if (!isDemoMode || id != "demo") {
                    list.add(HubDisplayItem(id, entry.name ?: "Homey"))
                }
            }
        } else if (!isDemoMode) {
            val selId = storage.getSelectedHomeyId()
            if (!selId.isNullOrBlank()) {
                list.add(HubDisplayItem(selId, storage.getSelectedHomeyName() ?: "Homey"))
            }
        }
        list
    }

    /**
     * Switches the active hub and synchronizes tiles.
     *
     * @private
     */
    fun _switchHub(hubId: String) {
        if (hubId == activeHubId) return
        if (hubId == "demo") {
            storage.setDemoMode(true)
        } else {
            storage.switchActiveHomey(hubId)
        }
        activeHubId = hubId
        TileSlotManager.syncSlots(context)
        onHubSwitched()
    }

    /**
     * Removes an authenticated hub and refreshes tile slots.
     *
     * @private
     */
    fun _removeHub(hubId: String) {
        val hasRemaining = storage.removeHubToken(hubId)
        TileSlotManager.syncSlots(context)
        if (hasRemaining) {
            allHubsMap = storage.getAllHubTokens()
            activeHubId = storage.getSelectedHomeyId() ?: ""
            onHubSwitched()
        } else {
            onLoggedOut()
        }
    }

    Scaffold(
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            state = listState,
            autoCentering = null,
            contentPadding = PaddingValues(top = 46.dp, bottom = 24.dp, start = 8.dp, end = 8.dp),
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            item {
                Text(
                    text = stringResource(id = R.string.settings_title),
                    style = MaterialTheme.typography.title1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            // ── Section: Connected Hubs ──────────────────────────────────────
            item {
                Text(
                    text = stringResource(id = R.string.settings_hubs_header, hubList.size, TileSlotManager.MAX_SLOTS),
                    style = MaterialTheme.typography.caption1,
                    color = HomeyBlue,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )
            }

            hubList.forEach { hub ->
                item {
                    val isActive = hub.id == activeHubId
                    val slotNumber = (1..TileSlotManager.MAX_SLOTS).firstOrNull { storage.getTileSlotHubId(it) == hub.id }
                        ?: (hubList.indexOf(hub) + 1).takeIf { it in 1..TileSlotManager.MAX_SLOTS }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isActive) Color(0xFF243048) else Color(0xFF1E1E22))
                            .clickable { _switchHub(hub.id) }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = hub.name,
                                        style = MaterialTheme.typography.title3,
                                        color = if (isActive) HomeyBlue else TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (isActive) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(id = R.string.settings_hub_active_badge),
                                            style = MaterialTheme.typography.caption2,
                                            color = HomeyBlue
                                        )
                                    }
                                }
                                val idSubtitle = if (slotNumber != null) {
                                    stringResource(id = R.string.settings_hub_slot_id, slotNumber, hub.id)
                                } else {
                                    stringResource(id = R.string.settings_homey_id, hub.id)
                                }
                                Text(
                                    text = idSubtitle,
                                    style = MaterialTheme.typography.caption2,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!isActive) {
                                    Text(
                                        text = stringResource(id = R.string.settings_hub_tap_to_switch),
                                        style = MaterialTheme.typography.caption2,
                                        color = Color(0xFF888888),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // Delete button for this hub
                            CompactButton(
                                onClick = { _removeHub(hub.id) },
                                colors = ButtonDefaults.secondaryButtonColors(
                                    backgroundColor = Color(0xFF3A1E1E),
                                    contentColor = Color(0xFFFF5252)
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
                }
            }

            // Button to Add Another Hub (if slot available)
            if (hubList.size < TileSlotManager.MAX_SLOTS) {
                item {
                    Chip(
                        onClick = onNavigateAddHub,
                        label = {
                            Text(
                                text = stringResource(id = R.string.settings_btn_add_hub),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        icon = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_add),
                                contentDescription = null,
                                modifier = Modifier.size(ChipDefaults.IconSize)
                            )
                        },
                        colors = ChipDefaults.primaryChipColors(
                            backgroundColor = HomeyBlue
                        ),
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .padding(vertical = 4.dp)
                    )
                }
            } else {
                item {
                    Text(
                        text = stringResource(id = R.string.settings_hubs_max_reached),
                        style = MaterialTheme.typography.caption2,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            // ── Section: Sync Interval ───────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E1E22))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(id = R.string.settings_sync_interval),
                        style = MaterialTheme.typography.body2
                    )

                    Button(
                        onClick = {
                            val next = when (syncInterval) {
                                0 -> 15
                                15 -> 30
                                30 -> 60
                                else -> 0
                            }
                            syncInterval = next
                            storage.saveSyncInterval(next)
                        },
                        colors = ButtonDefaults.secondaryButtonColors(),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            text = if (syncInterval == 0) stringResource(id = R.string.settings_sync_off) else "${syncInterval}s",
                            style = MaterialTheme.typography.caption1
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(10.dp)) }

            // ── Section: Disconnect All Hubs ─────────────────────────────────
            item {
                Button(
                    onClick = {
                        authRepo.logout()
                        TileSlotManager.syncSlots(context)
                        onLoggedOut()
                    },
                    colors = ButtonDefaults.primaryButtonColors(
                        backgroundColor = Color(0xFFC62828),
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .padding(bottom = 12.dp)
                ) {
                    Text(text = stringResource(id = R.string.settings_btn_disconnect_all))
                }
            }
        }
    }
}
