package com.dimapp.android.homeywatch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.dimapp.android.homeywatch.R
import com.dimapp.android.homeywatch.repository.models.HomeyDevice
import com.dimapp.android.homeywatch.ui.components.DeviceToggleChip
import com.dimapp.android.homeywatch.ui.components.ZoneSectionHeader
import com.dimapp.android.homeywatch.ui.theme.HomeyBlue
import kotlinx.coroutines.launch

/**
 * All Devices screen on Wear OS, displaying the complete smart home inventory grouped by zone.
 *
 * Implements:
 * - Rotary scrolling support for crowns/physical bezels
 * - Two-line zone section headers with measured room temperature badges
 * - SplitToggleChip controls with direct detail screen navigation
 * - Cleaned layout without redundant hub cards or trailing flow buttons
 *
 * @public
 * @param allDevices Full list of smart devices.
 * @param zoneTemperatures Map of zone names to measured room temperatures.
 * @param isLoading Whether devices are currently being fetched.
 * @param errorMessage Optional error message to display if fetch fails.
 * @param isReauthRequired Whether the hub session requires re-authentication.
 * @param isCompanionUpdated Whether re-authentication is caused by a companion app security update.
 * @param operatingMap Map tracking in-flight toggle mutations.
 * @param onToggleDevice Callback invoked when a device toggle is triggered.
 * @param onOpenDetail Callback to navigate to the device detail screen.
 * @param onRetry Callback to retry fetching devices.
 * @param onNavigateReauth Callback invoked when user taps to reconnect the hub.
 * @param isVisible Whether this pager tab is currently visible (used to request rotary scroll focus).
 * @param modifier Layout modifier.
 */
@Composable
fun MainDashboardScreen(
    allDevices: List<HomeyDevice>,
    zoneTemperatures: Map<String, Double>,
    isLoading: Boolean,
    errorMessage: String?,
    isReauthRequired: Boolean = false,
    isCompanionUpdated: Boolean = false,
    operatingMap: Map<String, Boolean>,
    onToggleDevice: (HomeyDevice, Boolean) -> Unit,
    onOpenDetail: (HomeyDevice) -> Unit,
    onRetry: () -> Unit = {},
    onNavigateReauth: () -> Unit = {},
    isVisible: Boolean = true,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            focusRequester.requestFocus()
        }
    }

    Scaffold(
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
        modifier = modifier
    ) {
        ScalingLazyColumn(
            state = listState,
            autoCentering = null,
            contentPadding = PaddingValues(top = 46.dp, bottom = 24.dp, start = 8.dp, end = 8.dp),
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
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

            if (isLoading && allDevices.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 3.dp,
                            indicatorColor = HomeyBlue
                        )
                    }
                }
            } else if (isReauthRequired) {
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = stringResource(
                                id = if (isCompanionUpdated) {
                                    R.string.auth_reauth_companion_updated_title
                                } else {
                                    R.string.auth_reauth_expired_title
                                }
                            ),
                            color = HomeyBlue,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.title3,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                id = if (isCompanionUpdated) {
                                    R.string.auth_reauth_companion_updated_desc
                                } else {
                                    R.string.auth_reauth_expired_desc
                                }
                            ),
                            color = Color.LightGray,
                            style = MaterialTheme.typography.caption2,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = onNavigateReauth,
                            colors = ButtonDefaults.primaryButtonColors(backgroundColor = HomeyBlue)
                        ) {
                            Text(
                                text = stringResource(id = R.string.auth_reauth_btn),
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else if (errorMessage != null && allDevices.isEmpty()) {
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = errorMessage,
                            color = Color.Red,
                            style = MaterialTheme.typography.caption1
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.secondaryButtonColors()
                        ) {
                            Text(text = stringResource(id = R.string.btn_retry))
                        }
                    }
                }
            } else {
                // All Devices grouped by Zone (filtered by visibility & group membership)
                val visibleDevices = allDevices.filter { !it.isHidden && !it.isGroupMember }
                val groupedByZone = visibleDevices.groupBy { it.zoneName ?: "Home" }
                val sortedZoneGroups = groupedByZone.entries.sortedBy { entry ->
                    entry.value.firstOrNull()?.zoneOrder ?: Int.MAX_VALUE
                }

                sortedZoneGroups.forEachIndexed { groupIndex, (zoneName, devicesInZone) ->
                    item {
                        val temp = zoneTemperatures[zoneName]
                        ZoneSectionHeader(
                            title = zoneName,
                            temperature = temp,
                            modifier = Modifier.padding(top = if (groupIndex == 0) 2.dp else 10.dp)
                        )
                    }

                    items(devicesInZone.size) { devIndex ->
                        val device = devicesInZone[devIndex]
                        val isOperating = operatingMap[device.id] == true

                        DeviceToggleChip(
                            device = device,
                            isOperating = isOperating,
                            onToggle = { targetActive -> onToggleDevice(device, targetActive) },
                            onOpenDetail = onOpenDetail,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
