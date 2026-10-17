package com.dimapp.android.homeywatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.wear.compose.material.SwipeToDismissBox
import com.dimapp.android.homeywatch.core.DependencyManager
import com.dimapp.android.homeywatch.repository.models.CAP_DIM
import com.dimapp.android.homeywatch.repository.models.CAP_LIGHT_HUE
import com.dimapp.android.homeywatch.repository.models.CAP_LIGHT_SATURATION
import com.dimapp.android.homeywatch.repository.models.CAP_LIGHT_TEMPERATURE
import com.dimapp.android.homeywatch.repository.models.CAP_ONOFF
import com.dimapp.android.homeywatch.repository.models.CAP_TARGET_TEMPERATURE
import com.dimapp.android.homeywatch.repository.models.HomeyDevice
import com.dimapp.android.homeywatch.ui.screens.DeviceDetailScreen
import com.dimapp.android.homeywatch.ui.screens.MainContainerScreen
import com.dimapp.android.homeywatch.ui.screens.SetupScreen
import com.dimapp.android.homeywatch.ui.theme.HomeyWatchTheme
import kotlinx.coroutines.launch

/** Navigation destination state. */
private enum class AppScreen {
    SETUP,
    MAIN,
    DEVICE_DETAIL
}

/**
 * Main Activity for the HomeyWatch Wear OS application.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val storage = DependencyManager.getTokenStorage(this)
        val authRepo = DependencyManager.getAuthRepository(this)
        val deviceRepo = DependencyManager.getDeviceRepository(this)
        val dashboardRepo = DependencyManager.getDashboardRepository(this)
        val flowRepo = DependencyManager.getFlowRepository(this)

        setContent {
            HomeyWatchTheme {
                val coroutineScope = rememberCoroutineScope()
                var currentScreen by remember {
                    mutableStateOf(
                        if (authRepo.isAuthenticated()) AppScreen.MAIN else AppScreen.SETUP
                    )
                }

                var selectedDetailDevice by remember { mutableStateOf<HomeyDevice?>(null) }

                val targetDeviceId = intent?.getStringExtra("EXTRA_DEVICE_ID")
                val targetHubId = intent?.getStringExtra("EXTRA_HUB_ID")
                androidx.compose.runtime.LaunchedEffect(targetDeviceId, targetHubId) {
                    if (!targetHubId.isNullOrBlank() && targetHubId != storage.getSelectedHomeyId()) {
                        if (targetHubId == "demo") {
                            storage.setDemoMode(true)
                        } else {
                            storage.switchActiveHomey(targetHubId)
                        }
                    }
                    if (!targetDeviceId.isNullOrBlank() && authRepo.isAuthenticated()) {
                        val devResult = deviceRepo.getDevices(forceRefresh = false)
                        if (devResult is com.dimapp.android.homeywatch.repository.models.HomeyResult.Success) {
                            val targetDev = devResult.data.find { it.id == targetDeviceId }
                            if (targetDev != null) {
                                selectedDetailDevice = targetDev
                                currentScreen = AppScreen.DEVICE_DETAIL
                            }
                        }
                    }
                }

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    deviceRepo.devicesFlow.collect { devices ->
                        if (selectedDetailDevice != null) {
                            val updated = devices.find { it.id == selectedDetailDevice?.id }
                            if (updated != null && updated != selectedDetailDevice) {
                                selectedDetailDevice = updated
                            }
                        }
                    }
                }

                when (currentScreen) {
                    AppScreen.SETUP -> {
                        val isAlreadyAuth = authRepo.isAuthenticated()
                        val setupContent = @Composable {
                            SetupScreen(
                                authRepo = authRepo,
                                storage = storage,
                                onAuthSuccess = {
                                    deviceRepo.invalidateCache()
                                    flowRepo.invalidateCache()
                                    dashboardRepo.invalidateCache()
                                    com.dimapp.android.homeywatch.tile.TileSlotManager.syncSlots(this@MainActivity)
                                    currentScreen = AppScreen.MAIN
                                },
                                onCancel = if (isAlreadyAuth) {
                                    { currentScreen = AppScreen.MAIN }
                                } else null
                            )
                        }
                        if (isAlreadyAuth) {
                            SwipeToDismissBox(
                                onDismissed = { currentScreen = AppScreen.MAIN }
                            ) { isBackground ->
                                if (!isBackground) {
                                    setupContent()
                                }
                            }
                        } else {
                            setupContent()
                        }
                    }
                    AppScreen.MAIN -> {
                        MainContainerScreen(
                            deviceRepo = deviceRepo,
                            dashboardRepo = dashboardRepo,
                            flowRepo = flowRepo,
                            authRepo = authRepo,
                            storage = storage,
                            onOpenDetail = { device ->
                                selectedDetailDevice = device
                                currentScreen = AppScreen.DEVICE_DETAIL
                            },
                            onNavigateAddHub = { currentScreen = AppScreen.SETUP },
                            onLoggedOut = {
                                deviceRepo.invalidateCache()
                                flowRepo.invalidateCache()
                                dashboardRepo.invalidateCache()
                                currentScreen = AppScreen.SETUP
                            }
                        )
                    }
                    AppScreen.DEVICE_DETAIL -> {
                        val swipeToDismissBoxState = androidx.wear.compose.foundation.rememberSwipeToDismissBoxState()
                        SwipeToDismissBox(
                            state = swipeToDismissBoxState,
                            onDismissed = { currentScreen = AppScreen.MAIN }
                        ) { isBackground ->
                            if (!isBackground && selectedDetailDevice != null) {
                                DeviceDetailScreen(
                                    device = selectedDetailDevice!!,
                                    swipeToDismissBoxState = swipeToDismissBoxState,
                                    onNavigateBack = { currentScreen = AppScreen.MAIN },
                                    onToggleOnOff = { nextChecked ->
                                        coroutineScope.launch {
                                            val cap = if (selectedDetailDevice!!.hasOnOff) CAP_ONOFF else selectedDetailDevice!!.primaryCapability
                                            deviceRepo.toggleDeviceState(
                                                deviceId = selectedDetailDevice!!.id,
                                                capabilityId = cap,
                                                makeActive = nextChecked
                                            )
                                        }
                                    },
                                    onSetSliderValue = { capabilityId, value ->
                                        coroutineScope.launch {
                                            deviceRepo.setDeviceCapabilityValue(
                                                deviceId = selectedDetailDevice!!.id,
                                                capabilityId = capabilityId,
                                                value = value
                                            )
                                        }
                                    },
                                    onSetDim = { dimValue ->
                                        coroutineScope.launch {
                                            deviceRepo.setDeviceCapabilityValue(
                                                deviceId = selectedDetailDevice!!.id,
                                                capabilityId = CAP_DIM,
                                                value = dimValue
                                            )
                                        }
                                    },
                                    onSetTargetTemperature = { targetTemp ->
                                        coroutineScope.launch {
                                            deviceRepo.setDeviceCapabilityValue(
                                                deviceId = selectedDetailDevice!!.id,
                                                capabilityId = CAP_TARGET_TEMPERATURE,
                                                value = targetTemp
                                            )
                                        }
                                    },
                                    onSetColor = { hue, saturation ->
                                        coroutineScope.launch {
                                            deviceRepo.setDeviceCapabilityValue(
                                                deviceId = selectedDetailDevice!!.id,
                                                capabilityId = CAP_LIGHT_HUE,
                                                value = hue
                                            )
                                            deviceRepo.setDeviceCapabilityValue(
                                                deviceId = selectedDetailDevice!!.id,
                                                capabilityId = CAP_LIGHT_SATURATION,
                                                value = saturation
                                            )
                                        }
                                    },
                                    onSetColorTemperature = { colorTemp ->
                                        coroutineScope.launch {
                                            deviceRepo.setDeviceCapabilityValue(
                                                deviceId = selectedDetailDevice!!.id,
                                                capabilityId = CAP_LIGHT_TEMPERATURE,
                                                value = colorTemp
                                            )
                                        }
                                    },
                                    onRefreshDeviceState = {
                                        deviceRepo.refreshSingleDevice(selectedDetailDevice!!.id)
                                    },
                                    onLoadHistory = {
                                        deviceRepo.getDeviceStateHistory(selectedDetailDevice!!.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
