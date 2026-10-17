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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.dimapp.android.homeywatch.R
import com.dimapp.android.homeywatch.repository.models.HomeyDevice
import com.dimapp.android.homeywatch.ui.components.DeviceToggleChip
import com.dimapp.android.homeywatch.ui.theme.HomeyBlue
import com.dimapp.android.homeywatch.ui.theme.TextPrimary
import com.dimapp.android.homeywatch.ui.theme.TextSecondary

/**
 * Dedicated Home screen displaying only favorite / quick action devices.
 *
 * Provides instant glanceable access to prioritized home hardware without
 * requiring navigation through full multi-room inventories.
 *
 * @public
 * @param quickDevices List of resolved favorite [HomeyDevice] instances.
 * @param isLoading Whether device data is currently being fetched from network.
 * @param operatingMap Map tracking in-flight toggle mutations for optimistic feedback.
 * @param onToggleDevice Callback invoked when toggling a device state.
 * @param onOpenDetail Callback invoked to open the detailed control view.
 * @param modifier Layout modifier.
 * @example
 * HomeScreen(
 *     quickDevices = favoritesList,
 *     isLoading = false,
 *     operatingMap = activeMutations,
 *     onToggleDevice = { device, nextState -> viewModel.toggle(device, nextState) },
 *     onOpenDetail = { device -> navigateToDetail(device) }
 * )
 */
@Composable
fun HomeScreen(
    quickDevices: List<HomeyDevice>,
    isLoading: Boolean,
    operatingMap: Map<String, Boolean>,
    onToggleDevice: (HomeyDevice, Boolean) -> Unit,
    onOpenDetail: (HomeyDevice) -> Unit,
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
            // Section title aligned directly beneath curved hub header
            item {
                Text(
                    text = stringResource(id = R.string.home_favorites_title),
                    style = MaterialTheme.typography.title3,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            if (isLoading && quickDevices.isEmpty()) {
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
            } else if (quickDevices.isEmpty()) {
                // Empty favorites state
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF1C1C1E))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(id = R.string.home_no_favorites),
                            style = MaterialTheme.typography.caption2,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                // Favorite devices list
                items(quickDevices.size) { index ->
                    val device = quickDevices[index]
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
