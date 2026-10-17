package com.dimapp.android.homeywatch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
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
 * @param operatingMap Map tracking in-flight toggle mutations.
 * @param onToggleDevice Callback invoked when a device toggle is triggered.
 * @param onOpenDetail Callback to navigate to the device detail screen.
 * @param onRetry Callback to retry fetching devices.
 * @param modifier Layout modifier.
 */
@Composable
fun MainDashboardScreen(
    allDevices: List<HomeyDevice>,
    zoneTemperatures: Map<String, Double>,
    isLoading: Boolean,
    errorMessage: String?,
    operatingMap: Map<String, Boolean>,
    onToggleDevice: (HomeyDevice, Boolean) -> Unit,
    onOpenDetail: (HomeyDevice) -> Unit,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberScalingLazyListState()

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
                .background(Color.Black),
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
