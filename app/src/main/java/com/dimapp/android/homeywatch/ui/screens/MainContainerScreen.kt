package com.dimapp.android.homeywatch.ui.screens

import android.util.Log
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
import com.dimapp.android.homeywatch.tile.TileSnapshotStore
import com.dimapp.android.homeywatch.ui.components.HubCurvedHeader
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val _TAG = "MainContainerScreen"
private const val _MAX_IN_VIEW_TIMEOUT_MS = 45_000L

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
 * @param onNavigateReauth Callback to navigate to setup screen to re-authenticate the active hub.
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
    onNavigateReauth: () -> Unit,
    onLoggedOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 4 })

    var allDevices by remember { mutableStateOf<List<HomeyDevice>>(emptyList()) }
    var zoneTemperatures by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var lastSyncEpochMs by remember { mutableStateOf(0L) }

    val favoriteDevices = remember(allDevices) {
        allDevices
            .filter { it.isFavorite }
            .sortedWith(compareBy<HomeyDevice> { it.favoriteOrder }.thenBy { it.name })
    }

    val operatingMap = remember { mutableStateMapOf<String, Boolean>() }

    val hubName = if (storage.isDemoMode()) {
        stringResource(id = R.string.demo_hub_title)
    } else {
        storage.getSelectedHomeyName() ?: "Homey"
    }

    /**
     * Persists the current quick-action device metadata for stateless Tiles and requests a redraw
     * only when the Tile structure (devices, labels, icons, or capabilities) changed.
     *
     * @private
     */
    fun _syncQuickTileSnapshot(devices: List<HomeyDevice>) {
        val activeHubId = if (storage.isDemoMode()) "demo" else storage.getSelectedHomeyId()
        if (!activeHubId.isNullOrBlank()) {
            val changed = TileSnapshotStore.saveHubSnapshotIfChanged(context, activeHubId, hubName, devices)
            if (changed) {
                TileSlotManager.updateAllSlots(context)
            }
        }
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

            coroutineScope {
                val devicesDeferred = async { deviceRepo.getDevices(forceRefresh = force) }
                val dashboardDeferred = async { dashboardRepo.prefetchDashboardConfig(forceRefresh = force) }

                val devicesResult = devicesDeferred.await()
                if (devicesResult is HomeyResult.Success) {
                    allDevices = devicesResult.data
                    zoneTemperatures = deviceRepo.getZoneTemperatures()
                    lastSyncEpochMs = System.currentTimeMillis()
                    dashboardDeferred.await()
                    val tileDevices = dashboardRepo.getQuickDevices(devicesResult.data, forceRefresh = false)
                    _syncQuickTileSnapshot(tileDevices)
                } else if (devicesResult is HomeyResult.Error) {
                    dashboardDeferred.cancel()
                    errorMessage = devicesResult.message
                }
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
                isLoading = false
            }
        }
    }

    /**
     * Synchronizes device states from Homey in the foreground for in-app screens,
     * skipping redundant network calls if the repository cache was just refreshed.
     *
     * @private
     */
    suspend fun _syncForegroundStates() {
        val currentList = allDevices.ifEmpty {
            val initial = deviceRepo.getDevices(forceRefresh = false)
            if (initial is HomeyResult.Success) initial.data else emptyList()
        }
        val configuredInterval = storage.getSyncInterval()
        val syncIntervalMillis = (configuredInterval.coerceAtLeast(10)) * 1000L
        val lastRepoSync = deviceRepo.getLastSyncTimestamp()
        if (lastRepoSync > 0L && (System.currentTimeMillis() - lastRepoSync) < syncIntervalMillis) {
            lastSyncEpochMs = lastRepoSync
            if (allDevices.isEmpty() && currentList.isNotEmpty()) {
                allDevices = currentList
            }
            return
        }
        if (currentList.isNotEmpty()) {
            val result = deviceRepo.syncDeviceStates(currentList)
            if (result is HomeyResult.Success) {
                allDevices = result.data
                lastSyncEpochMs = System.currentTimeMillis()
            }
        }
    }

    // Initial load when main container enters composition (slots are synced at app startup & hub config changes)
    LaunchedEffect(lifecycleOwner) {
        _refreshData(force = false)
    }

    // Periodic foreground sync loop with technical timeout (45s).
    // Only runs on device-centric tabs (Favorites & Dashboard) and respects cooldown on rapid swipes.
    LaunchedEffect(lifecycleOwner, pagerState.currentPage) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val isDeviceTab = pagerState.currentPage == 0 || pagerState.currentPage == 1
            if (!isDeviceTab) {
                Log.d(_TAG, "[MainContainerScreen:syncLoop] Tab ${pagerState.currentPage} does not display live device states. Foreground device sync paused.")
                return@repeatOnLifecycle
            }

            val configuredInterval = storage.getSyncInterval()
            if (configuredInterval <= 0) {
                Log.d(_TAG, "[MainContainerScreen:syncLoop] Sync interval is OFF. Periodic sync disabled.")
                return@repeatOnLifecycle
            }

            val syncIntervalMillis = (configuredInterval.coerceAtLeast(10)) * 1000L
            val startTime = System.currentTimeMillis()

            // Debounce brief intermediate pages while swiping across tabs
            delay(250)
            if (!isActive) return@repeatOnLifecycle

            Log.d(_TAG, "[MainContainerScreen:syncLoop] Tab ${pagerState.currentPage} active. Starting foreground sync (interval=${syncIntervalMillis}ms, timeout=${_MAX_IN_VIEW_TIMEOUT_MS}ms).")

            // Trigger immediate sync only when cached state is older than the sync interval
            // (prevents duplicate full-device fetches during cold start or rapid tab switching)
            val elapsedSinceLastSync = System.currentTimeMillis() - lastSyncEpochMs
            if (allDevices.isNotEmpty() && elapsedSinceLastSync >= syncIntervalMillis) {
                _syncForegroundStates()
            }

            // Periodic sync until the 45-second technical timeout is reached
            while (isActive) {
                if (System.currentTimeMillis() - startTime >= _MAX_IN_VIEW_TIMEOUT_MS) {
                    Log.d(_TAG, "[MainContainerScreen:syncLoop] Tab ${pagerState.currentPage} reached 45s timeout. Stopping periodic sync until screen changes.")
                    break
                }

                delay(syncIntervalMillis)
                if (!isActive) break

                if (System.currentTimeMillis() - startTime >= _MAX_IN_VIEW_TIMEOUT_MS) {
                    Log.d(_TAG, "[MainContainerScreen:syncLoop] Tab ${pagerState.currentPage} reached 45s timeout. Stopping periodic sync until screen changes.")
                    break
                }

                _syncForegroundStates()
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
        val needsReauth = storage.isReauthRequired() || errorMessage?.contains("401") == true
        val isCompanionUpdated = storage.getReauthReason() == "COMPANION_UPDATED"

        // 4-page Horizontal Pager Carousel
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> {
                    HomeScreen(
                        favoriteDevices = favoriteDevices,
                        isLoading = isLoading,
                        isReauthRequired = needsReauth,
                        isCompanionUpdated = isCompanionUpdated,
                        operatingMap = operatingMap,
                        onToggleDevice = { device, nextChecked -> _onToggleDevice(device, nextChecked) },
                        onOpenDetail = onOpenDetail,
                        onNavigateReauth = onNavigateReauth,
                        isVisible = pagerState.currentPage == 0
                    )
                }
                1 -> {
                    MainDashboardScreen(
                        allDevices = allDevices,
                        zoneTemperatures = zoneTemperatures,
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        isReauthRequired = needsReauth,
                        isCompanionUpdated = isCompanionUpdated,
                        operatingMap = operatingMap,
                        onToggleDevice = { device, nextChecked -> _onToggleDevice(device, nextChecked) },
                        onOpenDetail = onOpenDetail,
                        onRetry = { _refreshData(force = true) },
                        onNavigateReauth = onNavigateReauth,
                        isVisible = pagerState.currentPage == 1
                    )
                }
                2 -> {
                    val activeHubId = if (storage.isDemoMode()) "demo" else storage.getSelectedHomeyId()
                    FlowsScreen(
                        flowRepo = flowRepo,
                        activeHubId = activeHubId,
                        isVisible = pagerState.currentPage == 2
                    )
                }
                3 -> {
                    SettingsScreen(
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
                        },
                        isVisible = pagerState.currentPage == 3
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
