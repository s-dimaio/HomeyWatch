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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.dimapp.android.homeywatch.ui.theme.BackgroundDark
import com.dimapp.android.homeywatch.ui.theme.HomeyBlue
import com.dimapp.android.homeywatch.ui.theme.SurfaceDark
import com.dimapp.android.homeywatch.ui.theme.TextPrimary
import com.dimapp.android.homeywatch.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Dedicated Home screen displaying the user's Homey favorite devices (`favoriteDevices`),
 * completely decoupled from the 2x2 Wear OS Tile widget configuration.
 *
 * Provides instant glanceable access to favorite smart home hardware with rotary crown scrolling,
 * optimistic state updates, and direct detail navigation.
 *
 * @public
 * @param favoriteDevices Ordered list of [HomeyDevice] instances marked as favorites on Homey.
 * @param isLoading Whether device data is currently being fetched from network.
 * @param isReauthRequired Whether the hub session requires re-authentication.
 * @param isCompanionUpdated Whether re-authentication is caused by a companion app security update.
 * @param operatingMap Map tracking in-flight toggle mutations for optimistic feedback.
 * @param onToggleDevice Callback invoked when toggling a device state.
 * @param onOpenDetail Callback invoked to open the detailed control view.
 * @param onNavigateReauth Callback invoked when user taps to reconnect the hub.
 * @param isVisible Whether this pager tab is currently visible (used to request rotary scroll focus).
 * @param modifier Optional [Modifier] for layout adjustments.
 * @example
 * HomeScreen(
 *     favoriteDevices = favoritesList,
 *     isLoading = false,
 *     isReauthRequired = false,
 *     isCompanionUpdated = false,
 *     operatingMap = activeMutations,
 *     onToggleDevice = { device, nextState -> viewModel.toggle(device, nextState) },
 *     onOpenDetail = { device -> navigateToDetail(device) },
 *     onNavigateReauth = { navigateToSetup() },
 *     isVisible = pagerState.currentPage == 0
 * )
 */
@Composable
fun HomeScreen(
    favoriteDevices: List<HomeyDevice>,
    isLoading: Boolean,
    isReauthRequired: Boolean = false,
    isCompanionUpdated: Boolean = false,
    operatingMap: Map<String, Boolean>,
    onToggleDevice: (HomeyDevice, Boolean) -> Unit,
    onOpenDetail: (HomeyDevice) -> Unit,
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

            if (isLoading && favoriteDevices.isEmpty()) {
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
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(SurfaceDark)
                            .padding(14.dp)
                    ) {
                        Text(
                            text = stringResource(
                                id = if (isCompanionUpdated) {
                                    R.string.auth_reauth_companion_updated_title
                                } else {
                                    R.string.auth_reauth_expired_title
                                }
                            ),
                            style = MaterialTheme.typography.title3,
                            fontWeight = FontWeight.Bold,
                            color = HomeyBlue,
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
                            style = MaterialTheme.typography.caption2,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = onNavigateReauth,
                            colors = ButtonDefaults.primaryButtonColors(
                                backgroundColor = HomeyBlue,
                                contentColor = BackgroundDark
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(id = R.string.auth_reauth_btn),
                                color = BackgroundDark,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else if (favoriteDevices.isEmpty()) {
                // Empty favorites state
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(SurfaceDark)
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
                items(favoriteDevices.size) { index ->
                    val device = favoriteDevices[index]
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
