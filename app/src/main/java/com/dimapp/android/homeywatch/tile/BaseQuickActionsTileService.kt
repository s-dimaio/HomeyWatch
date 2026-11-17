package com.dimapp.android.homeywatch.tile

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonColors
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.dimapp.android.homeywatch.MainActivity
import com.dimapp.android.homeywatch.R
import com.dimapp.android.homeywatch.api.SanitizedHttpLogger
import com.dimapp.android.homeywatch.core.DependencyManager
import com.dimapp.android.homeywatch.repository.models.CAP_ONOFF
import com.dimapp.android.homeywatch.repository.models.CAP_TARGET_TEMPERATURE
import com.dimapp.android.homeywatch.repository.models.HomeyDevice
import com.dimapp.android.homeywatch.repository.models.HomeyResult
import com.dimapp.android.homeywatch.storage.TokenStorage
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

private const val _ID_IC_HOMEY_LOGO = "tile_ic_homey_logo"
private const val _ID_IC_LIGHT = "tile_ic_light"
private const val _ID_IC_LOCK = "tile_ic_lock"
private const val _ID_IC_GARAGE = "tile_ic_garage"
private const val _ID_IC_THERMOSTAT = "tile_ic_thermostat"
private const val _ID_IC_DEFAULT = "tile_ic_default"
private const val _TAG = "BaseQuickActionsTile"

private const val _COLOR_BUTTON_NEUTRAL = 0xFF2C2C2E.toInt()
private const val _COLOR_BUTTON_EMPTY_BG = 0xFF1C1C1E.toInt()
private const val _COLOR_BUTTON_EMPTY_FG = 0xFF555555.toInt()
private const val _COLOR_ICON_WHITE = 0xFFFFFFFF.toInt()
private const val _COLOR_LABEL_TEXT = 0xFFCCCCCC.toInt()

/** Monotonic counter combined with boot-elapsed seed to guarantee unique anti-replay click nonces. */
private val _clickNonceCounter = AtomicLong(SystemClock.elapsedRealtime())

/** Dedicated coroutine scope for TileService asynchronous operations. */
private val _tileSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Base [TileService] rendering a stateless 2x2 quick-switch grid for a dedicated Homey hub slot.
 *
 * Buttons maintain a uniform neutral appearance without displaying live device state, eliminating
 * background polling (`freshnessIntervalMillis = 0L`). When a switch button is tapped, the live
 * state of that single device is read on demand and toggled to its opposite value, followed by
 * tactile haptic confirmation.
 *
 * @property slotNumber 1-based slot index (1..4) managed by [TileSlotManager].
 */
abstract class BaseQuickActionsTileService(
    protected val slotNumber: Int
) : TileService() {

    private val _serviceScope: CoroutineScope
        get() = _tileSyncScope

    /**
     * Handles Wear OS Tile layout requests and stateless quick-switch button actuations.
     *
     * @public
     * @param requestParams System request parameters including optional `lastClickableId`.
     * @return [ListenableFuture] resolving to the rendered [TileBuilders.Tile] with zero periodic freshness.
     * @example
     * // Invoked by the Wear OS Tile host when rendering or tapping a Tile button.
     */
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        return _serviceScope.future {
            val appCtx = applicationContext
            val storage = DependencyManager.getTokenStorage(appCtx)

            val (targetHubId, displayName) = _resolveSlotHub(storage, appCtx)
            val clickedId = requestParams.currentState?.lastClickableId
            Log.d(_TAG, "[BaseQuickActionsTileService:onTileRequest] Slot $slotNumber request, hasClick=${!clickedId.isNullOrBlank()}, targetHubId=${SanitizedHttpLogger.maskId(targetHubId)}")

            if (!clickedId.isNullOrBlank() && TileSnapshotStore.markClickConsumedIfNew(appCtx, clickedId)) {
                _handleButtonClick(appCtx, clickedId, targetHubId)
            }

            val deviceConfig = requestParams.deviceConfiguration
                ?: DeviceParametersBuilders.DeviceParameters.Builder().build()

            if (targetHubId.isNullOrBlank()) {
                return@future _buildEmptyTile(appCtx, deviceConfig, "slot_${slotNumber}_empty")
            }

            val tileDevices = _resolveOrInitializeTileDevices(appCtx, targetHubId, displayName)
            _preloadMissingTileIcons(appCtx, tileDevices, targetHubId)

            val iconFingerprint = tileDevices.joinToString(";") { "${it.id}:${it.iconUrl}" }.hashCode().toString()
            val nameFingerprint = tileDevices.joinToString(";") { "${it.id}:${it.displayName}" }.hashCode().toString()
            val cachedIconsCount = tileDevices.count {
                !it.iconUrl.isNullOrBlank() && TileIconHelper.getCachedIconBytes(appCtx, it.iconUrl) != null
            }
            val resourcesVersion = "slot_${slotNumber}_${iconFingerprint}_${nameFingerprint}_${cachedIconsCount}_v3"

            _buildTile(
                context = appCtx,
                devices = tileDevices,
                deviceParameters = deviceConfig,
                hubId = targetHubId,
                hubName = displayName,
                resourcesVersion = resourcesVersion
            )
        }
    }

    /**
     * Supplies static vector icons and cached inline device icons for the Tile.
     *
     * @public
     * @param requestParams System resource request specifying the target `resourcesVersion`.
     * @return [ListenableFuture] resolving to [ResourceBuilders.Resources].
     * @example
     * // Invoked by Wear OS SysUI when resourcesVersion changes.
     */
    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return _serviceScope.future {
            val appCtx = applicationContext
            val storage = DependencyManager.getTokenStorage(appCtx)
            val (targetHubId, displayName) = _resolveSlotHub(storage, appCtx)

            val resourcesBuilder = ResourceBuilders.Resources.Builder()
                .setVersion(requestParams.version)
                .addIdToImageMapping(
                    _ID_IC_HOMEY_LOGO,
                    _drawableImageResource(R.drawable.ic_homey_tile_logo)
                )
                .addIdToImageMapping(
                    _ID_IC_LIGHT,
                    _drawableImageResource(R.drawable.ic_light_on)
                )
                .addIdToImageMapping(
                    _ID_IC_LOCK,
                    _drawableImageResource(R.drawable.ic_lock_closed)
                )
                .addIdToImageMapping(
                    _ID_IC_GARAGE,
                    _drawableImageResource(R.drawable.ic_garage_closed)
                )
                .addIdToImageMapping(
                    _ID_IC_THERMOSTAT,
                    _drawableImageResource(R.drawable.ic_thermostat)
                )
                .addIdToImageMapping(
                    _ID_IC_DEFAULT,
                    _drawableImageResource(R.drawable.ic_device_generic)
                )

            if (!targetHubId.isNullOrBlank()) {
                val tileDevices = _resolveOrInitializeTileDevices(appCtx, targetHubId, displayName)
                for (dev in tileDevices) {
                    if (!dev.iconUrl.isNullOrBlank()) {
                        val bytes = TileIconHelper.getCachedIconBytes(appCtx, dev.iconUrl)
                            ?: TileIconHelper.loadDeviceIconBytes(appCtx, dev.iconUrl, hubId = targetHubId)
                        if (bytes != null) {
                            val inline = ResourceBuilders.InlineImageResource.Builder()
                                .setData(bytes)
                                .setWidthPx(TileIconHelper.TILE_ICON_SIZE_PX)
                                .setHeightPx(TileIconHelper.TILE_ICON_SIZE_PX)
                                .setFormat(ResourceBuilders.IMAGE_FORMAT_ARGB_8888)
                                .build()

                            val imageResource = ResourceBuilders.ImageResource.Builder()
                                .setInlineResource(inline)
                                .build()

                            resourcesBuilder.addIdToImageMapping("tile_dev_icon_${dev.id}", imageResource)
                        }
                    }
                }
            }

            resourcesBuilder.build()
        }
    }

    /**
     * Resolves the Homey hub ID and display label assigned to this Tile slot.
     *
     * @private
     * @param storage Persistent [TokenStorage].
     * @param context Application context.
     * @return Pair of `(hubId, displayName)` where `hubId` is null if unconfigured.
     */
    private fun _resolveSlotHub(storage: TokenStorage, context: Context): Pair<String?, String> {
        var targetHubId = storage.getTileSlotHubId(slotNumber)
        var targetHubName = storage.getTileSlotHubName(slotNumber)

        if (targetHubId == null && slotNumber == 1) {
            if (storage.isDemoMode()) {
                targetHubId = "demo"
                targetHubName = context.getString(R.string.demo_hub_title)
            } else {
                targetHubId = storage.getSelectedHomeyId()
                targetHubName = storage.getSelectedHomeyName() ?: "Homey"
            }
        } else if (targetHubId == null && slotNumber == 2 && storage.isDemoMode()) {
            targetHubId = "demo_2"
            targetHubName = context.getString(R.string.demo_hub_2_title)
        }

        val displayName = targetHubName ?: when (targetHubId) {
            "demo" -> context.getString(R.string.demo_hub_title)
            "demo_2" -> context.getString(R.string.demo_hub_2_title)
            else -> "Homey"
        }
        return Pair(targetHubId, displayName)
    }

    /**
     * Processes a stateless button tap or legacy toggle click, executing read-before-toggle actuation
     * and emitting haptic confirmation.
     *
     * @private
     * @param context Application context.
     * @param clickedId Consumed clickable ID string.
     * @param defaultHubId Slot hub ID fallback when not encoded in the clickable ID.
     */
    private suspend fun _handleButtonClick(
        context: Context,
        clickedId: String,
        defaultHubId: String?
    ) {
        val deviceRepo = DependencyManager.getDeviceRepository(context)
        val parts = clickedId.split("|")

        if (clickedId.startsWith("press|") && parts.size >= 6) {
            val devId = parts[1]
            val capId = parts[2]
            val toggleHub = parts[3].ifBlank { defaultHubId }
            val isButton = parts[4].toBooleanStrictOrNull() ?: false
            val fallbackActive = parts[5].toBooleanStrictOrNull() ?: false

            Log.d(_TAG, "[BaseQuickActionsTileService:_handleButtonClick] Slot $slotNumber pressing dev=${SanitizedHttpLogger.maskId(devId)}, cap=$capId, isButton=$isButton, hub=${SanitizedHttpLogger.maskId(toggleHub)}")
            if (isButton) {
                val res = deviceRepo.toggleDeviceState(
                    deviceId = devId,
                    capabilityId = capId,
                    makeActive = true,
                    hubId = toggleHub
                )
                _triggerHapticFeedback(context, isSuccess = res is HomeyResult.Success)
            } else {
                val res = deviceRepo.smartToggleDevice(
                    deviceId = devId,
                    capabilityId = capId,
                    fallbackIsActive = fallbackActive,
                    hubId = toggleHub
                )
                if (res is HomeyResult.Success && !toggleHub.isNullOrBlank()) {
                    TileSnapshotStore.updateFallbackActive(context, toggleHub, devId, res.data)
                }
                _triggerHapticFeedback(context, isSuccess = res is HomeyResult.Success)
            }
        } else if (clickedId.startsWith("toggle|") && parts.size >= 4) {
            // Backward compatibility for Tiles rendered before stateless upgrade
            val devId = parts[1]
            val capId = parts[2]
            val targetActive = parts[3].toBooleanStrictOrNull() ?: true
            val toggleHub = if (parts.size >= 5 && parts[4].isNotBlank()) parts[4] else defaultHubId

            Log.d(_TAG, "[BaseQuickActionsTileService:_handleButtonClick] Slot $slotNumber legacy toggle dev=${SanitizedHttpLogger.maskId(devId)}, cap=$capId, hub=${SanitizedHttpLogger.maskId(toggleHub)}")
            val res = deviceRepo.smartToggleDevice(
                deviceId = devId,
                capabilityId = capId,
                fallbackIsActive = !targetActive,
                hubId = toggleHub
            )
            if (res is HomeyResult.Success && !toggleHub.isNullOrBlank()) {
                TileSnapshotStore.updateFallbackActive(context, toggleHub, devId, res.data)
            }
            _triggerHapticFeedback(context, isSuccess = res is HomeyResult.Success)
        }
    }

    /**
     * Loads quick-action devices for [hubId] from [TileSnapshotStore], falling back to a one-time
     * network resolution and persisting the snapshot if none exists yet.
     *
     * @private
     * @param context Application context.
     * @param hubId Target Homey hub ID.
     * @param hubName Display name of the hub.
     * @return List of up to 4 [TileDeviceSnapshot] entries.
     */
    private suspend fun _resolveOrInitializeTileDevices(
        context: Context,
        hubId: String,
        hubName: String
    ): List<TileDeviceSnapshot> {
        val existingSnapshot = TileSnapshotStore.getHubSnapshot(context, hubId)
        if (existingSnapshot != null) {
            return existingSnapshot.devices
        }

        val deviceRepo = DependencyManager.getDeviceRepository(context)
        val dashboardRepo = DependencyManager.getDashboardRepository(context)

        val fetchedDevices: List<HomeyDevice>? = withTimeoutOrNull(2500L) {
            val devicesResult = deviceRepo.getDevicesForHub(hubId, forceRefresh = false)
            val allDevices = if (devicesResult is HomeyResult.Success) devicesResult.data else return@withTimeoutOrNull null
            dashboardRepo.getQuickDevicesForHub(allDevices, hubId).take(4)
        }

        if (fetchedDevices != null) {
            TileSnapshotStore.saveHubSnapshotIfChanged(context, hubId, hubName, fetchedDevices)
            return TileSnapshotStore.getHubSnapshot(context, hubId)?.devices ?: emptyList()
        }

        Log.w(_TAG, "[BaseQuickActionsTileService:_resolveOrInitializeTileDevices] Slot $slotNumber initial snapshot fetch timed out; scheduling one-shot background init")
        _serviceScope.launch {
            val res = deviceRepo.getDevicesForHub(hubId, forceRefresh = false)
            if (res is HomeyResult.Success && res.data.isNotEmpty()) {
                val quick = dashboardRepo.getQuickDevicesForHub(res.data, hubId).take(4)
                val saved = TileSnapshotStore.saveHubSnapshotIfChanged(context, hubId, hubName, quick)
                if (saved) {
                    try {
                        TileService.getUpdater(context).requestUpdate(TileSlotManager.getServiceClass(slotNumber))
                    } catch (e: Exception) {
                        Log.e(_TAG, "[BaseQuickActionsTileService:_resolveOrInitializeTileDevices] Failed to request update after init: ${e.message}")
                    }
                }
            }
        }
        return emptyList()
    }

    /**
     * Downloads any missing icons exclusively for the 4 devices rendered in this Tile.
     *
     * @private
     * @param context Application context.
     * @param devices Devices displayed in this Tile.
     * @param hubId Target Homey hub ID for relative icon URL resolution.
     */
    private fun _preloadMissingTileIcons(
        context: Context,
        devices: List<TileDeviceSnapshot>,
        hubId: String
    ) {
        val uncached = devices.filter {
            !it.iconUrl.isNullOrBlank() && TileIconHelper.getCachedIconBytes(context, it.iconUrl) == null
        }
        if (uncached.isEmpty()) return

        _serviceScope.launch {
            var anyLoaded = false
            for (dev in uncached) {
                val bytes = TileIconHelper.loadDeviceIconBytes(context, dev.iconUrl, hubId = hubId)
                if (bytes != null) {
                    anyLoaded = true
                }
            }
            if (anyLoaded) {
                try {
                    TileService.getUpdater(context).requestUpdate(TileSlotManager.getServiceClass(slotNumber))
                } catch (e: Exception) {
                    Log.e(_TAG, "[BaseQuickActionsTileService:_preloadMissingTileIcons] Failed to request update after icon cache: ${e.message}")
                }
            }
        }
    }

    /**
     * Triggers tactile haptic feedback on the watch after a Tile button actuation.
     *
     * @private
     * @param context Application context.
     * @param isSuccess True for a crisp confirmation click; false for a double pulse error cue.
     */
    private fun _triggerHapticFeedback(context: Context, isSuccess: Boolean) {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator == null || !vibrator.hasVibrator()) return

            if (isSuccess) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 55L, 80L, 55L), -1))
            }
        } catch (e: Exception) {
            Log.w(_TAG, "[BaseQuickActionsTileService:_triggerHapticFeedback] Unable to vibrate: ${e.message}")
        }
    }

    /**
     * Creates a ProtoLayout [ResourceBuilders.ImageResource] from a local Android drawable ID.
     *
     * @private
     * @param resId Drawable resource identifier.
     * @return Configured [ResourceBuilders.ImageResource].
     */
    private fun _drawableImageResource(resId: Int): ResourceBuilders.ImageResource {
        return ResourceBuilders.ImageResource.Builder()
            .setAndroidResourceByResId(
                ResourceBuilders.AndroidImageResourceByResId.Builder()
                    .setResourceId(resId)
                    .build()
            ).build()
    }

    /**
     * Builds the unconfigured placeholder Tile layout with zero periodic freshness.
     *
     * @private
     * @param context Application context.
     * @param deviceParameters Watch screen parameters.
     * @param resourcesVersion Resource version tag.
     * @return Rendered [TileBuilders.Tile].
     */
    private fun _buildEmptyTile(
        context: Context,
        deviceParameters: DeviceParametersBuilders.DeviceParameters,
        resourcesVersion: String = "slot_${slotNumber}_empty"
    ): TileBuilders.Tile {
        val openAppAction = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(context.packageName)
                    .setClassName(MainActivity::class.java.name)
                    .build()
            ).build()

        val rootColumn = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        rootColumn.addContent(
            CompactChip.Builder(
                context,
                context.getString(R.string.tile_open_app),
                ModifiersBuilders.Clickable.Builder().setOnClick(openAppAction).build(),
                deviceParameters
            ).build()
        )

        val layout = LayoutElementBuilders.Layout.Builder().setRoot(rootColumn.build()).build()
        val entry = TimelineBuilders.TimelineEntry.Builder().setLayout(layout).build()
        val timeline = TimelineBuilders.Timeline.Builder().addTimelineEntry(entry).build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(resourcesVersion)
            .setTileTimeline(timeline)
            .setFreshnessIntervalMillis(0L)
            .build()
    }

    /**
     * Builds the stateless 2x2 quick-switch grid layout inside a Wear OS Tile.
     *
     * All toggle buttons render with a uniform neutral color so the Tile acts strictly as a
     * stateless remote control without implying live on/off status, and sets `freshnessIntervalMillis`
     * to `0L` to avoid background wakeups.
     *
     * @private
     * @param context Application context.
     * @param devices Up to 4 quick-action device snapshots.
     * @param deviceParameters Watch screen parameters.
     * @param hubId Target Homey hub ID.
     * @param hubName Display hub name in header.
     * @param resourcesVersion Resource fingerprint string.
     * @return Rendered [TileBuilders.Tile].
     */
    private fun _buildTile(
        context: Context,
        devices: List<TileDeviceSnapshot>,
        deviceParameters: DeviceParametersBuilders.DeviceParameters,
        hubId: String,
        hubName: String,
        resourcesVersion: String
    ): TileBuilders.Tile {
        val defaultOpenAppAction = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(context.packageName)
                    .setClassName(MainActivity::class.java.name)
                    .addKeyToExtraMapping("EXTRA_HUB_ID", ActionBuilders.stringExtra(hubId))
                    .build()
            ).build()

        val rootColumn = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        val headerRow = LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setOnClick(defaultOpenAppAction)
                            .build()
                    )
                    .build()
            )

        headerRow.addContent(
            LayoutElementBuilders.Image.Builder()
                .setResourceId(_ID_IC_HOMEY_LOGO)
                .setWidth(dp(18f))
                .setHeight(dp(18f))
                .build()
        )

        headerRow.addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(5f)).build())

        headerRow.addContent(
            Text.Builder(context, hubName)
                .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                .setColor(argb(_COLOR_ICON_WHITE))
                .setMaxLines(1)
                .build()
        )

        rootColumn.addContent(headerRow.build())
        rootColumn.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())

        if (devices.isEmpty()) {
            rootColumn.addContent(
                CompactChip.Builder(
                    context,
                    context.getString(R.string.tile_open_app),
                    ModifiersBuilders.Clickable.Builder().setOnClick(defaultOpenAppAction).build(),
                    deviceParameters
                ).build()
            )
        } else {
            val layoutNonce = _clickNonceCounter.incrementAndGet().toString(36)
            val row1 = LayoutElementBuilders.Row.Builder()
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            val row2 = LayoutElementBuilders.Row.Builder()
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)

            for (i in 0 until 4) {
                val dev = devices.getOrNull(i)
                val targetRow = if (i < 2) row1 else row2

                if (i == 1 || i == 3) {
                    targetRow.addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(8f)).build())
                }

                if (dev != null) {
                    val hasRapidToggle = (!dev.isThermostat && dev.primaryCapability != CAP_TARGET_TEMPERATURE) ||
                        dev.hasOnOff ||
                        dev.isButtonAction

                    val clickableModifier = if (hasRapidToggle) {
                        val cap = if (dev.isThermostat && dev.primaryCapability == CAP_TARGET_TEMPERATURE && dev.hasOnOff) {
                            CAP_ONOFF
                        } else {
                            dev.primaryCapability
                        }
                        val clickableId = "press|${dev.id}|$cap|$hubId|${dev.isButtonAction}|${dev.fallbackIsActive}|$layoutNonce"

                        ModifiersBuilders.Clickable.Builder()
                            .setId(clickableId)
                            .setOnClick(ActionBuilders.LoadAction.Builder().build())
                            .build()
                    } else {
                        val openDetailAction = ActionBuilders.LaunchAction.Builder()
                            .setAndroidActivity(
                                ActionBuilders.AndroidActivity.Builder()
                                    .setPackageName(context.packageName)
                                    .setClassName(MainActivity::class.java.name)
                                    .addKeyToExtraMapping("EXTRA_DEVICE_ID", ActionBuilders.stringExtra(dev.id))
                                    .addKeyToExtraMapping("EXTRA_HUB_ID", ActionBuilders.stringExtra(hubId))
                                    .build()
                            ).build()
                        ModifiersBuilders.Clickable.Builder()
                            .setOnClick(openDetailAction)
                            .build()
                    }

                    val hasCustomIcon = !dev.iconUrl.isNullOrBlank() &&
                        TileIconHelper.getCachedIconBytes(context, dev.iconUrl) != null
                    val iconId = if (hasCustomIcon) {
                        "tile_dev_icon_${dev.id}"
                    } else {
                        when {
                            dev.isThermostat -> _ID_IC_THERMOSTAT
                            dev.deviceClass == "light" || dev.deviceClass == "lamp" -> _ID_IC_LIGHT
                            dev.deviceClass == "lock" -> _ID_IC_LOCK
                            dev.deviceClass == "garagedoor" -> _ID_IC_GARAGE
                            else -> _ID_IC_DEFAULT
                        }
                    }

                    val btn = Button.Builder(context, clickableModifier)
                        .setSize(dp(44f))
                        .setButtonColors(ButtonColors(argb(_COLOR_BUTTON_NEUTRAL), argb(_COLOR_ICON_WHITE)))
                        .setIconContent(iconId)
                        .build()

                    val quadrantCol = LayoutElementBuilders.Column.Builder()
                        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                        .setWidth(dp(58f))

                    quadrantCol.addContent(btn)
                    quadrantCol.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(2f)).build())
                    quadrantCol.addContent(
                        Text.Builder(context, dev.displayName)
                            .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                            .setColor(argb(_COLOR_LABEL_TEXT))
                            .setMaxLines(1)
                            .build()
                    )

                    targetRow.addContent(quadrantCol.build())
                } else {
                    val btn = Button.Builder(
                        context,
                        ModifiersBuilders.Clickable.Builder().setOnClick(defaultOpenAppAction).build()
                    )
                        .setSize(dp(44f))
                        .setButtonColors(ButtonColors(argb(_COLOR_BUTTON_EMPTY_BG), argb(_COLOR_BUTTON_EMPTY_FG)))
                        .setIconContent(_ID_IC_DEFAULT)
                        .build()

                    val quadrantCol = LayoutElementBuilders.Column.Builder()
                        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                        .setWidth(dp(58f))

                    quadrantCol.addContent(btn)
                    quadrantCol.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(14f)).build())

                    targetRow.addContent(quadrantCol.build())
                }
            }

            rootColumn.addContent(row1.build())
            rootColumn.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
            rootColumn.addContent(row2.build())
        }

        val layout = LayoutElementBuilders.Layout.Builder().setRoot(rootColumn.build()).build()
        val entry = TimelineBuilders.TimelineEntry.Builder().setLayout(layout).build()
        val timeline = TimelineBuilders.Timeline.Builder().addTimelineEntry(entry).build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(resourcesVersion)
            .setTileTimeline(timeline)
            .setFreshnessIntervalMillis(0L)
            .build()
    }
}
