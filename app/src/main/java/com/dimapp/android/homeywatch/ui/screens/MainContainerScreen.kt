package com.dimapp.android.homeywatch.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dimapp.android.homeywatch.R
import com.dimapp.android.homeywatch.auth.HomeyAuthRepository
import com.dimapp.android.homeywatch.repository.DashboardRepository
import com.dimapp.android.homeywatch.repository.DeviceRepository
import com.dimapp.android.homeywatch.repository.FlowRepository
import com.dimapp.android.homeywatch.repository.models.HomeyDevice
import com.dimapp.android.homeywatch.repository.models.HomeyResult
import com.dimapp.android.homeywatch.storage.TokenStorage
import com.dimapp.android.homeywatch.tile.TileSlotManager
import com.dimapp.android.homeywatch.ui.components.HubCurvedHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Root multi-tab container screen for HomeyWatch on Wear OS.
 *
 * Implements:
 * - 4-page [HorizontalPager] (Home, Devices, Flows, Settings) with horizontal swipe navigation.
 * - Concentric curved hub header adhering to the top bezel with active tab icon indicator and protective AMOLED scrim.
 * - Shared foreground state synchronization across all destinations.
 *
 * @public
 * @param deviceRepo Repository managing smart home device states and cache.
 * @param dashboardRepo Repository resolving quick action and virtual dashboard devices.
 * @param flowRepo Repository handling flow triggers and execution.
 * @param authRepo Repository handling authentication and session state.
 * @param storage Persistent secure settings storage.
 * @param onOpenDetail Callback invoked when a device is tapped to open its detailed controls.
 * @param onNavigateAddHub Callback to navigate to setup screen for adding an additional hub.
 * @param onLoggedOut Callback invoked when user logs out.
 * @param modifier Layout modifier.
 * @example
 * MainContainerScreen(
 *     deviceRepo = deviceRepository,
 *     dashboardRepo = dashboardRepository,
 *     flowRepo = flowRepository,
 *     authRepo = authRepository,
 *     storage = tokenStorage,
 *     onOpenDetail = { device -> navigateToDetail(device) },
 *     onNavigateAddHub = { navigateToSetup() },
 *     onLoggedOut = { navigateToSetup() }
 * )
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainContainerScreen(
    deviceRepo: DeviceRepository,
    dashboardRepo: DashboardRepository,
    flowRepo: FlowRepository,
    authRepo: HomeyAuthRepository,
    storage: TokenStorage,
    onOpenDetail: (HomeyDevice) -> Unit,
    onNavigateAddHub: () -> Unit,
    onLoggedOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 4 })

    var allDevices by remember { mutableStateOf<List<HomeyDevice>>(emptyList()) }
    var quickDevices by remember { mutableStateOf<List<HomeyDevice>>(emptyList()) }
    var zoneTemperatures by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val operatingMap = remember { mutableStateMapOf<String, Boolean>() }

    val hubName = if (storage.isDemoMode()) {
        stringResource(id = R.string.demo_hub_title)
    } else {
        storage.getSelectedHomeyName() ?: "Homey"
    }

    /**
     * Refreshes devices and virtual dashboard from network.
     *
     * @private
     */
    fun _refreshData(force: Boolean = false) {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null

            if (force) {
                dashboardRepo.invalidateCache()
            }

            val devicesResult = deviceRepo.getDevices(forceRefresh = force)
            if (devicesResult is HomeyResult.Success) {
                allDevices = devicesResult.data
                zoneTemperatures = deviceRepo.getZoneTemperatures()
                val dashResult = dashboardRepo.getQuickDevices(devicesResult.data, forceRefresh = force)
                quickDevices = dashResult
            } else if (devicesResult is HomeyResult.Error) {
                errorMessage = devicesResult.message
            }

            isLoading = false
        }
    }

    // Collect reactive updates from DeviceRepository flow
    LaunchedEffect(lifecycleOwner) {
        deviceRepo.devicesFlow.collect { flowDevices ->
            if (flowDevices.isNotEmpty()) {
                allDevices = flowDevices
                zoneTemperatures = deviceRepo.getZoneTemperatures()
                quickDevices = dashboardRepo.getQuickDevices(flowDevices)
                isLoading = false
            }
        }
    }

    // Initial load and periodic foreground synchronization loop
    LaunchedEffect(lifecycleOwner) {
        _refreshData(force = false)
        TileSlotManager.syncSlots(context)

        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            suspend fun syncStates() {
                val now = System.currentTimeMillis()
                val lastSync = deviceRepo.getLastSyncTimestamp()
                val syncInterval = storage.getSyncInterval().coerceAtLeast(10)
                val intervalMillis = syncInterval * 1000L

                if (now - lastSync >= intervalMillis || allDevices.isEmpty()) {
                    val currentList = allDevices.ifEmpty {
                        val initial = deviceRepo.getDevices(forceRefresh = false)
                        if (initial is HomeyResult.Success) initial.data else emptyList()
                    }
                    if (currentList.isNotEmpty()) {
                        val result = deviceRepo.syncDeviceStates(currentList)
                        if (result is HomeyResult.Success) {
                            val previousQuick = quickDevices
                            val updatedQuick = dashboardRepo.getQuickDevices(result.data, forceRefresh = true)
                            quickDevices = updatedQuick

                            val hasChanged = previousQuick.size != updatedQuick.size || previousQuick.zip(updatedQuick).any { (old, new) ->
                                old.id != new.id ||
                                old.isActive != new.isActive ||
                                old.isAvailable != new.isAvailable ||
                                old.dimValue != new.dimValue ||
                                old.displayName != new.displayName ||
                                old.primaryCapability != new.primaryCapability ||
                                old.iconUrl != new.iconUrl
                            }
                            if (hasChanged) {
                                TileSlotManager.updateAllSlots(context)
                            }
                        }
                    }
                }
            }

            syncStates()

            while (true) {
                val syncInterval = storage.getSyncInterval().coerceAtLeast(10)
                if (storage.isDemoMode()) {
                    delay(15000L)
                    continue
                }
                delay(syncInterval * 1000L)
                syncStates()
            }
        }
    }

    /**
     * Executes device state toggle with optimistic UI updates.
     *
     * @private
     */
    fun _onToggleDevice(device: HomeyDevice, targetActive: Boolean) {
        val isButton = device.isButtonAction
        val optimisticDevice = if (isButton) device else device.copy(isActive = targetActive)

        allDevices = allDevices.map { if (it.id == optimisticDevice.id) optimisticDevice else it }
        quickDevices = quickDevices.map { if (it.id == optimisticDevice.id) optimisticDevice else it }
        operatingMap[device.id] = true

        coroutineScope.launch {
            val result = deviceRepo.toggleDeviceState(
                deviceId = device.id,
                capabilityId = device.quickActionCapability ?: device.primaryCapability,
                makeActive = targetActive
            )
            operatingMap.remove(device.id)

            if (result is HomeyResult.Error) {
                allDevices = allDevices.map { if (it.id == device.id) device else it }
                quickDevices = quickDevices.map { if (it.id == device.id) device else it }
            }
        }
    }

    val indicatorAlpha = remember { Animatable(0f) }

    // Auto-fading page indicator: visible during swipe/page change, then smoothly fades out
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress) {
            indicatorAlpha.animateTo(1f, animationSpec = tween(durationMillis = 150))
        } else {
            indicatorAlpha.animateTo(1f, animationSpec = tween(durationMillis = 150))
            delay(1200)
            indicatorAlpha.animateTo(0f, animationSpec = tween(durationMillis = 400))
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 4-page Horizontal Pager Carousel
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> {
                    HomeScreen(
                        quickDevices = quickDevices,
                        isLoading = isLoading,
                        operatingMap = operatingMap,
                        onToggleDevice = { device, nextChecked -> _onToggleDevice(device, nextChecked) },
                        onOpenDetail = onOpenDetail
                    )
                }
                1 -> {
                    MainDashboardScreen(
                        allDevices = allDevices,
                        zoneTemperatures = zoneTemperatures,
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        operatingMap = operatingMap,
                        onToggleDevice = { device, nextChecked -> _onToggleDevice(device, nextChecked) },
                        onOpenDetail = onOpenDetail,
                        onRetry = { _refreshData(force = true) }
                    )
                }
                2 -> {
                    FlowsScreen(flowRepo = flowRepo)
                }
                3 -> {
                    SettingsScreen(
                        authRepo = authRepo,
                        storage = storage,
                        onNavigateAddHub = onNavigateAddHub,
                        onHubSwitched = {
                            deviceRepo.invalidateCache()
                            flowRepo.invalidateCache()
                            dashboardRepo.invalidateCache()
                            _refreshData(force = true)
                        },
                        onLoggedOut = {
                            deviceRepo.invalidateCache()
                            flowRepo.invalidateCache()
                            dashboardRepo.invalidateCache()
                            onLoggedOut()
                        }
                    )
                }
            }
        }

        val currentTabIcon = when (pagerState.currentPage) {
            0 -> R.drawable.selector_home
            1 -> R.drawable.selector_devices
            2 -> R.drawable.selector_flow
            else -> R.drawable.selector_settings
        }

        // Top curved header showing Hub Name, active Tab icon, and auto-fading 4-page dots indicator
        HubCurvedHeader(
            hubName = hubName,
            tabIconRes = currentTabIcon,
            selectedPage = pagerState.currentPage,
            pageCount = 4,
            indicatorAlpha = indicatorAlpha.value,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}
