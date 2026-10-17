package com.dimapp.android.homeywatch.ui.screens

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.AnchorType
import androidx.wear.compose.foundation.CurvedAlignment
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.SwipeToDismissBoxState
import androidx.wear.compose.foundation.curvedRow
import androidx.wear.compose.foundation.edgeSwipeToDismiss
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.curvedText
import com.dimapp.android.homeywatch.R
import com.dimapp.android.homeywatch.repository.models.CAP_DIM
import com.dimapp.android.homeywatch.repository.models.CAP_GARAGEDOOR
import com.dimapp.android.homeywatch.repository.models.CAP_LIGHT_HUE
import com.dimapp.android.homeywatch.repository.models.CAP_LIGHT_SATURATION
import com.dimapp.android.homeywatch.repository.models.CAP_LIGHT_TEMPERATURE
import com.dimapp.android.homeywatch.repository.models.CAP_ONOFF
import com.dimapp.android.homeywatch.repository.models.CAP_TARGET_TEMPERATURE
import com.dimapp.android.homeywatch.repository.models.DeviceHistoryEvent
import com.dimapp.android.homeywatch.repository.models.HomeyDevice
import com.dimapp.android.homeywatch.repository.models.HomeyResult
import com.dimapp.android.homeywatch.repository.models.resolveCapabilityDouble
import com.dimapp.android.homeywatch.repository.models.resolveSliderCapabilities
import com.dimapp.android.homeywatch.ui.theme.HomeyThermostat
import com.dimapp.android.homeywatch.ui.theme.TextPrimary
import com.dimapp.android.homeywatch.ui.theme.TextSecondary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Capability-driven UI template types for Homey devices.
 *
 * Each tab corresponds to one or more capabilities exposed by the device.
 */
enum class DeviceTab(
    val id: String,
    @get:DrawableRes val iconRes: Int,
    @get:StringRes val titleRes: Int
) {
    TOGGLE("toggle", R.drawable.selector_toggle, R.string.tab_onoff),
    THERMOSTAT("thermostat", R.drawable.selector_thermostat, R.string.tab_thermostat),
    SLIDER("slider", R.drawable.selector_slider, R.string.tab_dim),
    COLOR("color", R.drawable.selector_color, R.string.tab_color),
    RECAP("recap", R.drawable.selector_history, R.string.tab_recap)
}

/**
 * Resolves the ordered list of [DeviceTab] templates for a given [HomeyDevice]
 * based on its active capabilities and configuration.
 *
 * @public
 * @param device Target smart device.
 * @return Ordered list of available [DeviceTab]s.
 */
fun resolveDeviceTabs(device: HomeyDevice): List<DeviceTab> = buildList {
    val caps = device.rawCapabilities.keys

    // 1. Toggle Tab (uiComponent: "toggle" / boolean)
    if (device.hasOnOff || caps.contains(CAP_ONOFF) || caps.contains("locked") || caps.contains(CAP_GARAGEDOOR)) {
        add(DeviceTab.TOGGLE)
    }

    // 2. Thermostat Tab (uiComponent: "thermostat" / target_temperature)
    if (device.isThermostat || caps.contains(CAP_TARGET_TEMPERATURE)) {
        add(DeviceTab.THERMOSTAT)
    }

    // 3. Slider Tab (uiComponent: "slider" / continuous number)
    if (device.isDimmable || caps.contains(CAP_DIM) || caps.contains("volume_set") || caps.contains("windowcoverings_set")) {
        add(DeviceTab.SLIDER)
    }

    // 4. Color Tab (uiComponent: "color" / RGB & color temperature)
    if (device.hasColor || device.hasColorTemp || caps.contains(CAP_LIGHT_HUE) || caps.contains(CAP_LIGHT_TEMPERATURE)) {
        add(DeviceTab.COLOR)
    }

    // Fallback if no specific tab was matched but device has a quick action
    if (isEmpty() && device.hasQuickAction) {
        add(DeviceTab.TOGGLE)
    }

    // 5. Recap / History Tab (always available)
    add(DeviceTab.RECAP)
}

/**
 * Universal capability-driven detail screen for Homey devices on Wear OS.
 *
 * Dynamically resolves controls based on the device's actual capabilities,
 * providing:
 * - On/Off and Lock toggle controls
 * - Thermostat temperature setpoint stepper and rotary crown tuning
 * - Vertical capsule slider with drag and rotary bezel control
 * - RGB color wheel and white color temperature presets
 * - Time-series history and state change recap from Homey Insights
 *
 * @public
 * @param device Target [HomeyDevice].
 * @param swipeToDismissBoxState Optional state for managing native swipe-to-dismiss.
 * @param onNavigateBack Callback invoked to navigate back to the dashboard.
 * @param isOperating Whether a mutation is currently in flight.
 * @param onToggleOnOff Callback to toggle the primary binary capability.
 * @param onSetDim Callback to set brightness / level between 0.0 and 1.0.
 * @param onSetSliderValue Callback to set any continuous slider capability (dim, volume_set, windowcoverings_set, etc.).
 * @param onSetTargetTemperature Callback to update target temperature in Celsius.
 * @param onSetColor Callback to update color hue and saturation.
 * @param onSetColorTemperature Callback to update light temperature between 0.0 and 1.0.
 * @param onRefreshDeviceState Callback to refresh device state from server.
 * @param onLoadHistory Callback to load historical transitions from Insights.
 * @param modifier Layout modifier.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeviceDetailScreen(
    device: HomeyDevice,
    swipeToDismissBoxState: SwipeToDismissBoxState? = null,
    onNavigateBack: (() -> Unit)? = null,
    isOperating: Boolean = false,
    onToggleOnOff: (Boolean) -> Unit,
    onSetDim: (Double) -> Unit = {},
    onSetSliderValue: (capabilityId: String, value: Double) -> Unit = { _, v -> onSetDim(v) },
    onSetTargetTemperature: (Double) -> Unit = {},
    onSetColor: (hue: Double, saturation: Double) -> Unit = { _, _ -> },
    onSetColorTemperature: (Double) -> Unit = {},
    onRefreshDeviceState: (suspend () -> Unit)? = null,
    onLoadHistory: (suspend () -> HomeyResult<List<DeviceHistoryEvent>>)? = null,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val dimmerFocusRequester = remember { FocusRequester() }
    val thermostatFocusRequester = remember { FocusRequester() }
    val recapFocusRequester = remember { FocusRequester() }

    val availableTabs = remember(
        device.rawCapabilities,
        device.isDimmable,
        device.hasColor,
        device.hasColorTemp,
        device.isThermostat,
        device.hasOnOff
    ) {
        resolveDeviceTabs(device)
    }

    val pagerState = rememberPagerState(pageCount = { availableTabs.size })
    val indicatorAlpha = remember { Animatable(if (availableTabs.size > 1) 1f else 0f) }

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

    // On-demand background refresh of the device state
    LaunchedEffect(device.id) {
        onRefreshDeviceState?.invoke()
    }

    // Rotary focus management based on the active tab
    LaunchedEffect(pagerState.currentPage) {
        when (availableTabs.getOrNull(pagerState.currentPage)) {
            DeviceTab.SLIDER -> dimmerFocusRequester.requestFocus()
            DeviceTab.THERMOSTAT -> thermostatFocusRequester.requestFocus()
            DeviceTab.RECAP -> recapFocusRequester.requestFocus()
            else -> {}
        }
    }

    // Lazy loading for history when the Recap tab is entered
    var historyEvents by remember(device.id) { mutableStateOf<List<DeviceHistoryEvent>?>(null) }
    var isHistoryLoading by remember(device.id) { mutableStateOf(false) }
    var historyLoadError by remember(device.id) { mutableStateOf(false) }

    val isRecapActive = availableTabs.getOrNull(pagerState.currentPage) == DeviceTab.RECAP

    LaunchedEffect(isRecapActive, device.id) {
        if (isRecapActive && historyEvents == null && onLoadHistory != null) {
            isHistoryLoading = true
            historyLoadError = false
            try {
                when (val result = onLoadHistory()) {
                    is HomeyResult.Success -> {
                        historyEvents = result.data
                        historyLoadError = false
                    }
                    is HomeyResult.Error -> {
                        historyLoadError = true
                    }
                }
            } catch (e: Exception) {
                historyLoadError = true
            } finally {
                isHistoryLoading = false
            }
        }
    }

    // Continuous slider capabilities resolution
    val sliderCapabilities = remember(device.rawCapabilities, device.capabilitiesMetadata) {
        resolveSliderCapabilities(device)
    }

    var activeSliderCap by remember(device.id) {
        mutableStateOf(sliderCapabilities.firstOrNull() ?: CAP_DIM)
    }

    LaunchedEffect(sliderCapabilities) {
        if (!sliderCapabilities.contains(activeSliderCap) && sliderCapabilities.isNotEmpty()) {
            activeSliderCap = sliderCapabilities.first()
        }
    }

    val currentDeviceSliderValue = remember(activeSliderCap, device.rawCapabilities, device.dimValue) {
        resolveCapabilityDouble(device, activeSliderCap) ?: 0.5
    }

    // Local slider tracking with debounce
    var currentSliderValue by remember(activeSliderCap, currentDeviceSliderValue) {
        mutableDoubleStateOf(currentDeviceSliderValue)
    }
    var sliderDebounceJob by remember { mutableStateOf<Job?>(null) }

    fun _applySliderValue(newVal: Double) {
        val clamped = (newVal.coerceIn(0.0, 1.0) * 100).roundToInt() / 100.0
        if (clamped != currentSliderValue) {
            currentSliderValue = clamped
            sliderDebounceJob?.cancel()
            sliderDebounceJob = coroutineScope.launch {
                delay(350)
                onSetSliderValue(activeSliderCap, clamped)
            }
        }
    }

    var showPickerOverlay by remember { mutableStateOf(false) }

    // Local target temperature tracking with debounce
    var currentTargetTemp by remember(device.targetTemperature) {
        mutableDoubleStateOf(device.targetTemperature ?: 20.0)
    }
    var tempDebounceJob by remember { mutableStateOf<Job?>(null) }

    fun _applyTemperatureChange(newVal: Double) {
        val clamped = (round(newVal * 2.0) / 2.0).coerceIn(4.0, 35.0)
        if (clamped != currentTargetTemp) {
            currentTargetTemp = clamped
            tempDebounceJob?.cancel()
            tempDebounceJob = coroutineScope.launch {
                delay(400)
                onSetTargetTemperature(clamped)
            }
        }
    }

    // Swipe-to-dismiss is active ONLY on the first page across the entire screen
    val edgeDismissModifier = if (swipeToDismissBoxState != null && pagerState.currentPage == 0) {
        Modifier.edgeSwipeToDismiss(
            swipeToDismissBoxState = swipeToDismissBoxState,
            edgeWidth = 200.dp
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .then(edgeDismissModifier)
        ) { page ->
            when (availableTabs[page]) {
                DeviceTab.TOGGLE -> {
                    _DeviceToggleTab(
                        device = device,
                        isActive = device.isActive,
                        isOperating = isOperating,
                        onToggle = { onToggleOnOff(!device.isActive) }
                    )
                }
                DeviceTab.THERMOSTAT -> {
                    _DeviceThermostatTab(
                        targetTemperature = currentTargetTemp,
                        currentTemperature = device.currentTemperature ?: device.temperature,
                        onTemperatureChange = { _applyTemperatureChange(it) },
                        focusRequester = thermostatFocusRequester
                    )
                }
                DeviceTab.SLIDER -> {
                    _DeviceSliderTab(
                        device = device,
                        sliderCapabilities = sliderCapabilities,
                        activeCapabilityId = activeSliderCap,
                        currentValue = currentSliderValue,
                        onOpenPicker = { showPickerOverlay = true },
                        onValueChange = { _applySliderValue(it) },
                        focusRequester = dimmerFocusRequester
                    )
                }
                DeviceTab.COLOR -> {
                    _DeviceColorTab(
                        device = device,
                        onSetColor = onSetColor,
                        onSetColorTemperature = onSetColorTemperature
                    )
                }
                DeviceTab.RECAP -> {
                    _DeviceRecapTab(
                        historyEvents = historyEvents,
                        isLoading = isHistoryLoading,
                        isError = historyLoadError,
                        focusRequester = recapFocusRequester
                    )
                }
            }
        }

        // Top curved header showing device name, active tab icon & auto-fading page indicator
        val currentTabIcon = availableTabs.getOrNull(pagerState.currentPage)?.iconRes
        _DeviceHeader(
            name = device.displayName,
            activeTabIconRes = currentTabIcon,
            selectedPage = pagerState.currentPage,
            pageCount = availableTabs.size,
            indicatorAlpha = indicatorAlpha.value,
            onBack = onNavigateBack,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // Modal picker overlay for switching continuous slider capability
        if (showPickerOverlay) {
            _CapabilityPickerOverlay(
                device = device,
                capabilities = sliderCapabilities,
                selectedCapabilityId = activeSliderCap,
                onSelectCapability = { chosen ->
                    activeSliderCap = chosen
                    showPickerOverlay = false
                },
                onDismiss = { showPickerOverlay = false }
            )
        }
    }
}

/**
 * Top persistent curved header displaying device name along the watch bezel on round displays,
 * the active tab icon, and an auto-fading page indicator dots overlay (taking zero layout space),
 * with AMOLED black gradient scrim and top back tap shortcut.
 */
@Composable
private fun _DeviceHeader(
    name: String,
    @DrawableRes activeTabIconRes: Int? = null,
    selectedPage: Int = 0,
    pageCount: Int = 1,
    indicatorAlpha: Float = 0f,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isRound = LocalConfiguration.current.isScreenRound

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        // Vertical gradient scrim to smoothly dissolve scrolling items into black behind the header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black,
                        0.75f to Color.Black,
                        1.0f to Color.Transparent
                    )
                )
        )

        if (isRound) {
            CurvedLayout(
                anchor = 270f,
                anchorType = AnchorType.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                curvedRow(
                    modifier = CurvedModifier.padding(radial = 5.dp),
                    radialAlignment = CurvedAlignment.Radial.Center
                ) {
                    curvedText(
                        text = name,
                        style = CurvedTextStyle(
                            TextStyle(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                    )
                }
            }

            // Destination tab icon and auto-fading indicator dots
            if (pageCount > 1 && activeTabIconRes != null) {
                Box(
                    modifier = Modifier.padding(top = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Crossfade(
                        targetState = activeTabIconRes,
                        label = "DeviceTabIconCrossfade"
                    ) { iconRes ->
                        Icon(
                            painter = painterResource(id = iconRes),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Auto-fading page indicator dots (takes ZERO layout space from the screen)
                if (indicatorAlpha > 0f) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(top = 43.dp)
                            .graphicsLayer { alpha = indicatorAlpha }
                    ) {
                        for (i in 0 until pageCount) {
                            val isSelected = i == selectedPage
                            Box(
                                modifier = Modifier
                                    .size(if (isSelected) 4.5.dp else 3.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) Color.White else Color.White.copy(alpha = 0.45f)
                                    )
                            )
                        }
                    }
                }
            }
        } else {
            // Fallback for square / rectangular screens
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    text = name,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (pageCount > 1 && activeTabIconRes != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Crossfade(
                        targetState = activeTabIconRes,
                        label = "DeviceTabIconCrossfade"
                    ) { iconRes ->
                        Icon(
                            painter = painterResource(id = iconRes),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    if (indicatorAlpha > 0f) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.graphicsLayer { alpha = indicatorAlpha }
                        ) {
                            for (i in 0 until pageCount) {
                                val isSelected = i == selectedPage
                                Box(
                                    modifier = Modifier
                                        .size(if (isSelected) 5.dp else 3.5.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) Color.White else Color.White.copy(alpha = 0.45f)
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Clickable hit-target for navigating back: restricted only to top header region
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .align(Alignment.TopCenter)
                .clickable(
                    enabled = onBack != null,
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                ) {
                    onBack?.invoke()
                }
        )
    }
}

/**
 * Tab 1: OnOff / Lock / Garage door toggle control matching the official Athom Homey layout.
 */
@Composable
private fun _DeviceToggleTab(
    device: HomeyDevice,
    isActive: Boolean,
    isOperating: Boolean,
    onToggle: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    val iconRes = when {
        device.deviceClass == "lock" || device.primaryCapability == "locked" -> {
            if (isActive) R.drawable.ic_homey_door_unlocked else R.drawable.ic_homey_door_locked
        }
        device.deviceClass == "garagedoor" || device.primaryCapability == CAP_GARAGEDOOR -> {
            if (isActive) R.drawable.ic_homey_garage_open else R.drawable.ic_homey_garage_close
        }
        else -> R.drawable.ic_homey_power
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        // Big circular button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(if (isActive) Color(0xFFFFFFFF) else Color(0xFF1C1C1E))
                .clickable(enabled = !isOperating) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle()
                }
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = if (isActive) {
                    stringResource(R.string.device_state_on)
                } else {
                    stringResource(R.string.device_state_off)
                },
                tint = if (isActive) Color(0xFF00E676) else Color(0xFFFFFFFF),
                modifier = Modifier.size(48.dp)
            )
        }
    }
}

/**
 * Tab 2: Thermostat target temperature control with crown rotary scroll, +/- stepper buttons
 * and live current temperature reading.
 */
@Composable
private fun _DeviceThermostatTab(
    targetTemperature: Double,
    currentTemperature: Double?,
    onTemperatureChange: (Double) -> Unit,
    focusRequester: FocusRequester
) {
    val haptic = LocalHapticFeedback.current
    val currentTargetTempState by rememberUpdatedState(targetTemperature)
    val onTemperatureChangeState by rememberUpdatedState(onTemperatureChange)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onRotaryScrollEvent { event ->
                val delta = if (event.verticalScrollPixels > 0) 0.5 else -0.5
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onTemperatureChangeState(currentTargetTempState + delta)
                true
            }
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        // Target Temperature Value
        Text(
            text = "%.1f°".format(targetTemperature),
            color = HomeyThermostat,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        // Measured Current Temperature
        if (currentTemperature != null) {
            Text(
                text = stringResource(R.string.detail_thermostat_current_label, "%.1f°".format(currentTemperature)),
                color = TextSecondary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        } else {
            Spacer(modifier = Modifier.height(14.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Stepper Buttons (- and +)
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onTemperatureChange(targetTemperature - 0.5)
                },
                colors = ButtonDefaults.secondaryButtonColors()
            ) {
                Text(text = "−", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            CompactButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onTemperatureChange(targetTemperature + 0.5)
                },
                colors = ButtonDefaults.secondaryButtonColors()
            ) {
                Text(text = "+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * Tab 3: Vertical capsule slider with touch drag and rotary bezel scroll,
 * supporting multiple continuous capabilities via dropdown selector.
 */
@Composable
private fun _DeviceSliderTab(
    device: HomeyDevice,
    sliderCapabilities: List<String>,
    activeCapabilityId: String,
    currentValue: Double,
    onOpenPicker: () -> Unit,
    onValueChange: (Double) -> Unit,
    focusRequester: FocusRequester
) {
    val haptic = LocalHapticFeedback.current
    val pct = (currentValue * 100).roundToInt()
    val context = LocalContext.current
    val currentTitle = _resolveCapabilityTitle(context, device, activeCapabilityId)

    val currentValState by rememberUpdatedState(currentValue)
    val onValueChangeState by rememberUpdatedState(onValueChange)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onRotaryScrollEvent { event ->
                val delta = if (event.verticalScrollPixels > 0) 0.05 else -0.05
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onValueChangeState((currentValState + delta).coerceIn(0.0, 1.0))
                true
            }
    ) {
        Spacer(modifier = Modifier.height(18.dp))

        // Dropdown pill when device exposes multiple continuous slider capabilities
        if (sliderCapabilities.size > 1) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2C2C2E))
                    .clickable { onOpenPicker() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = currentTitle,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "▾",
                        color = Color(0xFF8E8E93),
                        fontSize = 10.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Capsule Track Container with expanded touch target (72 dp wide x 104 dp high)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(72.dp)
                .height(104.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val heightPx = size.height.toFloat()
                        val newProgress = (1f - (down.position.y / heightPx)).coerceIn(0f, 1f).toDouble()
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onValueChangeState(newProgress)

                        val dragPointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val dragPointer = event.changes.firstOrNull { it.id == dragPointerId } ?: break
                            if (dragPointer.isConsumed) break
                            if (!dragPointer.pressed) break

                            dragPointer.consume()
                            val dragProgress = (1f - (dragPointer.position.y / heightPx)).coerceIn(0f, 1f).toDouble()
                            onValueChangeState(dragProgress)
                        }
                    }
                }
        ) {
            // Visual Capsule
            Box(
                modifier = Modifier
                    .width(52.dp)
                    .height(104.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color(0xFF2C2C2E))
            ) {
                // Fill level from bottom
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(currentValue.toFloat().coerceIn(0f, 1f))
                        .align(Alignment.BottomCenter)
                        .background(Color(0xFF00E676))
                )

                // Percentage indicator at the center
                Text(
                    text = "$pct%",
                    color = if (currentValue > 0.45) Color.Black else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        Spacer(modifier = Modifier.height(26.dp))
    }
}

/**
 * Resolves a human-readable localized title for a device capability.
 *
 * @private
 * @param context Android context for string resources.
 * @param device Target smart device.
 * @param capabilityId Capability identifier.
 * @return Localized title string.
 */
private fun _resolveCapabilityTitle(
    context: Context,
    device: HomeyDevice,
    capabilityId: String
): String {
    val metaTitle = device.capabilitiesMetadata[capabilityId]?.title
    if (!metaTitle.isNullOrBlank()) {
        return metaTitle
    }
    return when {
        capabilityId == CAP_DIM || capabilityId.startsWith("dim.") -> {
            context.getString(R.string.tab_dim)
        }
        capabilityId == "volume_set" || capabilityId.startsWith("volume_set.") -> {
            context.getString(R.string.tab_volume)
        }
        capabilityId == "windowcoverings_set" || capabilityId.startsWith("windowcoverings_set.") -> {
            context.getString(R.string.tab_position)
        }
        else -> {
            capabilityId.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }
}

/**
 * Full-screen modal overlay for selecting active capability among multiple continuous sliders.
 *
 * @private
 * @param device Target smart device.
 * @param capabilities List of available capability IDs.
 * @param selectedCapabilityId Currently active capability ID.
 * @param onSelectCapability Callback invoked when a capability is selected.
 * @param onDismiss Callback to dismiss the overlay.
 */
@Composable
private fun _CapabilityPickerOverlay(
    device: HomeyDevice,
    capabilities: List<String>,
    selectedCapabilityId: String,
    onSelectCapability: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f))
            .clickable(onClick = onDismiss)
    ) {
        ScalingLazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onRotaryScrollEvent { event ->
                    coroutineScope.launch {
                        listState.scrollBy(event.verticalScrollPixels)
                    }
                    true
                }
                .padding(horizontal = 14.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(20.dp))
            }
            item {
                Text(
                    text = stringResource(R.string.cap_select_title),
                    color = Color(0xFF8E8E93),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            itemsIndexed(capabilities) { _, capId ->
                val isSelected = capId == selectedCapabilityId
                val title = _resolveCapabilityTitle(context, device, capId)

                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) Color(0xFF00E676) else Color(0xFF2C2C2E))
                        .clickable {
                            onSelectCapability(capId)
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            color = if (isSelected) Color.Black else Color.White,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isSelected) {
                            Text(
                                text = "✓",
                                color = Color.Black,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            item {
                Spacer(modifier = Modifier.height(28.dp))
            }
        }
    }
}

/**
 * Tab 4: Color picker with preset wheel / temperature selector.
 */
@Composable
private fun _DeviceColorTab(
    device: HomeyDevice,
    onSetColor: (hue: Double, saturation: Double) -> Unit,
    onSetColorTemperature: (Double) -> Unit
) {
    val haptic = LocalHapticFeedback.current

    val lightMode = device.rawCapabilities["light_mode"] as? String
    var selectedMode by remember(device.id, lightMode) {
        mutableStateOf(
            if (lightMode == "temperature" || (!device.hasColor && device.hasColorTemp)) 1 else 0
        )
    }

    val currentHue = device.lightHue ?: (device.rawCapabilities[CAP_LIGHT_HUE] as? Number)?.toDouble()
    val currentSat = device.lightSaturation ?: (device.rawCapabilities[CAP_LIGHT_SATURATION] as? Number)?.toDouble()
    val currentTemp = device.lightTemperature ?: (device.rawCapabilities[CAP_LIGHT_TEMPERATURE] as? Number)?.toDouble()

    var localHue by remember(currentHue) { mutableStateOf(currentHue) }
    var localSat by remember(currentSat) { mutableStateOf(currentSat) }
    var localTemp by remember(currentTemp) { mutableStateOf(currentTemp) }

    val presetColors = remember {
        listOf(
            Triple(0.0, 1.0, Color(0xFFFF3B30)),      // Red
            Triple(0.08, 1.0, Color(0xFFFF9500)),     // Orange
            Triple(0.16, 1.0, Color(0xFFFFCC00)),     // Yellow
            Triple(0.33, 1.0, Color(0xFF34C759)),     // Green
            Triple(0.5, 1.0, Color(0xFF00C7BE)),      // Cyan
            Triple(0.60, 1.0, Color(0xFF007AFF)),     // Blue
            Triple(0.78, 1.0, Color(0xFFAF52DE)),     // Purple
            Triple(0.92, 0.7, Color(0xFFFF2D55))      // Pink
        )
    }

    val whiteTemps = remember {
        listOf(
            0.0 to R.string.detail_temp_warm,
            0.5 to R.string.detail_temp_natural,
            1.0 to R.string.detail_temp_cold
        )
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        // Mode Switcher (RGB vs White Tone)
        if (device.hasColor && device.hasColorTemp) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2C2C2E))
                    .padding(2.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selectedMode == 0) Color.White else Color.Transparent)
                        .clickable { selectedMode = 0 }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.color_mode_rgb),
                        color = if (selectedMode == 0) Color.Black else Color(0xFF8E8E93),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selectedMode == 1) Color.White else Color.Transparent)
                        .clickable { selectedMode = 1 }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.color_mode_temperature),
                        color = if (selectedMode == 1) Color.Black else Color(0xFF8E8E93),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (selectedMode == 0 && device.hasColor) {
            // 2x4 Color Swatch Grid
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presetColors.take(4).forEach { (h, s, color) ->
                        val isSelected = if (selectedMode == 0 && localHue != null) {
                            val diff = kotlin.math.abs(localHue!! - h)
                            val circularDiff = kotlin.math.min(diff, 1.0 - diff)
                            circularDiff < 0.055
                        } else false

                        _ColorSwatch(
                            color = color,
                            isSelected = isSelected,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                localHue = h
                                localSat = s
                                onSetColor(h, s)
                            }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presetColors.drop(4).forEach { (h, s, color) ->
                        val isSelected = if (selectedMode == 0 && localHue != null) {
                            val diff = kotlin.math.abs(localHue!! - h)
                            val circularDiff = kotlin.math.min(diff, 1.0 - diff)
                            circularDiff < 0.055
                        } else false

                        _ColorSwatch(
                            color = color,
                            isSelected = isSelected,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                localHue = h
                                localSat = s
                                onSetColor(h, s)
                            }
                        )
                    }
                }
            }
        } else {
            // White Temperature Presets
            val closestTemp = if (selectedMode == 1 && localTemp != null) {
                whiteTemps.minByOrNull { kotlin.math.abs(it.first - localTemp!!) }?.first
            } else null

            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                whiteTemps.forEach { (tempValue, labelRes) ->
                    val isSelected = closestTemp == tempValue
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(26.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(if (isSelected) Color(0xFF00E676) else Color(0xFF2C2C2E))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                localTemp = tempValue
                                onSetColorTemperature(tempValue)
                            }
                    ) {
                        Text(
                            text = stringResource(labelRes),
                            color = if (isSelected) Color.Black else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * Clickable color circle swatch with selection ring and central indicator.
 */
@Composable
private fun _ColorSwatch(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (isSelected) 2.5.dp else 1.dp,
                color = if (isSelected) Color.White else Color(0x33FFFFFF),
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

/**
 * Tab 5: Real-time status recap and vertical time-series event timeline.
 */
@Composable
private fun _DeviceRecapTab(
    historyEvents: List<DeviceHistoryEvent>?,
    isLoading: Boolean,
    isError: Boolean,
    focusRequester: FocusRequester
) {
    val listState = rememberScalingLazyListState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        ScalingLazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 50.dp, bottom = 24.dp),
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onRotaryScrollEvent { event ->
                    coroutineScope.launch {
                        listState.scrollBy(event.verticalScrollPixels)
                    }
                    true
                }
        ) {
            // Loading or content states
            if (isLoading) {
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.recap_loading_history),
                            color = Color(0xFF8E8E93),
                            fontSize = 10.sp
                        )
                    }
                }
            } else if (isError) {
                item {
                    Text(
                        text = stringResource(R.string.recap_error_history),
                        color = Color(0xFFFF5252),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else if (historyEvents.isNullOrEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.recap_no_history),
                        color = Color(0xFF8E8E93),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                itemsIndexed(historyEvents) { index, event ->
                    _TimelineNodeItem(
                        event = event,
                        isLast = index == historyEvents.lastIndex,
                        relativeTime = _formatRelativeTime(context, event.timestamp)
                    )
                }
            }
        }

        // Wear OS native position indicator scrollbar
        PositionIndicator(
            scalingLazyListState = listState,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

/**
 * Single historical timeline node with dot and connecting vertical line,
 * centered horizontally on circular Wear OS displays.
 */
@Composable
private fun _TimelineNodeItem(
    event: DeviceHistoryEvent,
    isLast: Boolean,
    relativeTime: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.width(136.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(18.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (event.isOn) Color(0xFF00E676) else Color(0xFF636366))
                )
                if (!isLast) {
                    Box(
                        modifier = Modifier
                            .width(1.5.dp)
                            .height(24.dp)
                            .background(Color(0xFF3A3A3C))
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = event.stateText,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = relativeTime,
                    color = Color(0xFF8E8E93),
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}


/**
 * Formats an epoch millisecond timestamp into a human-readable relative time string.
 */
private fun _formatRelativeTime(context: Context, timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diffMs = (now - timestamp).coerceAtLeast(0L)
    val diffMinutes = diffMs / (60 * 1000L)
    val diffHours = diffMs / (3600 * 1000L)
    val diffDays = diffMs / (24 * 3600 * 1000L)

    return when {
        diffMinutes < 2 -> context.getString(R.string.time_just_now)
        diffMinutes < 60 -> context.getString(R.string.time_minutes_ago, diffMinutes.toInt())
        diffHours < 24 -> context.getString(R.string.time_hours_ago, diffHours.toInt())
        diffDays < 2 -> context.getString(R.string.time_yesterday)
        else -> context.getString(R.string.time_days_ago, diffDays.toInt())
    }
}
