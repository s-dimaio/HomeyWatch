package com.dimapp.android.homeywatch.tile

import android.content.Context
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
import androidx.wear.protolayout.material.ButtonDefaults
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.dimapp.android.homeywatch.MainActivity
import com.dimapp.android.homeywatch.R
import com.dimapp.android.homeywatch.core.DependencyManager
import com.dimapp.android.homeywatch.repository.models.CAP_ONOFF
import com.dimapp.android.homeywatch.repository.models.CAP_TARGET_TEMPERATURE
import com.dimapp.android.homeywatch.repository.models.HomeyDevice
import com.dimapp.android.homeywatch.repository.models.HomeyResult
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val ID_IC_HOMEY_LOGO = "tile_ic_homey_logo"
private const val ID_IC_LIGHT = "tile_ic_light"
private const val ID_IC_LOCK = "tile_ic_lock"
private const val ID_IC_GARAGE = "tile_ic_garage"
private const val ID_IC_THERMOSTAT = "tile_ic_thermostat"
private const val ID_IC_DEFAULT = "tile_ic_default"
private const val _TAG = "BaseQuickActionsTile"

/** Cooldown tracker for background sync: slotNumber -> lastSyncEpochMillis */
private val _lastSyncTimestamps = java.util.concurrent.ConcurrentHashMap<Int, Long>()

/**
 * Base TileService rendering a 2x2 glanceable quick actions grid for a dedicated Homey hub slot.
 *
 * Header contains the official Homey circular logo alongside the dynamic name of the configured hub.
 *
 * @property slotNumber 1-based slot index (1..4) managed by [TileSlotManager].
 */
abstract class BaseQuickActionsTileService(
    protected val slotNumber: Int
) : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        return serviceScope.future {
            val appCtx = applicationContext
            val storage = DependencyManager.getTokenStorage(appCtx)
            val deviceRepo = DependencyManager.getDeviceRepository(appCtx)
            val dashboardRepo = DependencyManager.getDashboardRepository(appCtx)

            // Resolve target hub for this slot
            var targetHubId = storage.getTileSlotHubId(slotNumber)
            var targetHubName = storage.getTileSlotHubName(slotNumber)

            // Fallback for slot 1 or slot 2 if no slot record exists yet
            if (targetHubId == null && slotNumber == 1) {
                if (storage.isDemoMode()) {
                    targetHubId = "demo"
                    targetHubName = appCtx.getString(R.string.demo_hub_title)
                } else {
                    targetHubId = storage.getSelectedHomeyId()
                    targetHubName = storage.getSelectedHomeyName() ?: "Homey"
                }
            } else if (targetHubId == null && slotNumber == 2 && storage.isDemoMode()) {
                targetHubId = "demo_2"
                targetHubName = appCtx.getString(R.string.demo_hub_2_title)
            }

            // Handle in-place toggle if triggered by a LoadAction on this Tile
            val clickedId = requestParams.currentState?.lastClickableId
            Log.d(_TAG, "[BaseQuickActionsTileService:onTileRequest] Slot $slotNumber onTileRequest, lastClickableId=$clickedId, targetHubId=$targetHubId")

            if (!clickedId.isNullOrBlank() && clickedId.startsWith("toggle|")) {
                _lastSyncTimestamps[slotNumber] = 0L // Reset cooldown on explicit user toggle
                val parts = clickedId.split("|")
                if (parts.size >= 4) {
                    val devId = parts[1]
                    val capId = parts[2]
                    val targetActive = parts[3].toBooleanStrictOrNull() ?: true
                    val toggleHub = if (parts.size >= 5 && parts[4].isNotBlank()) parts[4] else targetHubId

                    Log.d(_TAG, "[BaseQuickActionsTileService:onTileRequest] Slot $slotNumber executing toggle: dev=$devId, cap=$capId, targetActive=$targetActive, hub=$toggleHub")
                    val toggleRes = deviceRepo.toggleDeviceState(
                        deviceId = devId,
                        capabilityId = capId,
                        makeActive = targetActive,
                        hubId = toggleHub
                    )
                    Log.d(_TAG, "[BaseQuickActionsTileService:onTileRequest] Slot $slotNumber toggle result: $toggleRes")
                }
            }

            val deviceConfig = requestParams.deviceConfiguration
                ?: DeviceParametersBuilders.DeviceParameters.Builder().build()

            val syncIntervalSeconds = storage.getSyncInterval().coerceAtLeast(10)
            val freshnessIntervalMillis = syncIntervalSeconds * 1000L

            if (targetHubId.isNullOrBlank()) {
                // Unconfigured / empty slot
                return@future _buildEmptyTile(appCtx, deviceConfig, "slot_${slotNumber}_empty", freshnessIntervalMillis)
            }

            val (quickDevices, displayName) = withTimeoutOrNull(2500L) {
                val devicesResult = deviceRepo.getDevicesForHub(targetHubId, forceRefresh = false)
                val allDevices = if (devicesResult is HomeyResult.Success) devicesResult.data else emptyList()
                val devices = dashboardRepo.getQuickDevicesForHub(allDevices, targetHubId).take(4)

                // Pre-load icon bytes for uncached devices asynchronously in a single batch without blocking onTileRequest
                val uncachedDevs = devices.filter { !it.iconUrl.isNullOrBlank() && TileIconHelper.getCachedIconBytes(appCtx, it.iconUrl) == null }
                if (uncachedDevs.isNotEmpty()) {
                    serviceScope.launch {
                        var anyLoaded = false
                        for (dev in uncachedDevs) {
                            val bytes = TileIconHelper.loadDeviceIconBytes(appCtx, dev.iconUrl)
                            if (bytes != null) {
                                anyLoaded = true
                            }
                        }
                        if (anyLoaded) {
                            try {
                                TileService.getUpdater(appCtx).requestUpdate(TileSlotManager.getServiceClass(slotNumber))
                            } catch (e: Exception) {
                                Log.e(_TAG, "[BaseQuickActionsTileService:onTileRequest] Failed to request update after icon cache: ${e.message}")
                            }
                        }
                    }
                }

                val name = targetHubName ?: if (targetHubId == "demo") {
                    appCtx.getString(R.string.demo_hub_title)
                } else if (targetHubId == "demo_2") {
                    appCtx.getString(R.string.demo_hub_2_title)
                } else {
                    "Homey"
                }

                Pair(devices, name)
            } ?: run {
                Log.w(_TAG, "[BaseQuickActionsTileService:onTileRequest] Slot $slotNumber fetch timed out (2500ms limit), scheduling background update")
                serviceScope.launch {
                    val res = deviceRepo.getDevicesForHub(targetHubId, forceRefresh = false)
                    if (res is HomeyResult.Success) {
                        dashboardRepo.getQuickDevicesForHub(res.data, targetHubId)
                    }
                    try {
                        TileService.getUpdater(appCtx).requestUpdate(TileSlotManager.getServiceClass(slotNumber))
                    } catch (e: Exception) {
                        Log.e(_TAG, "[BaseQuickActionsTileService:onTileRequest] Failed to request background update: ${e.message}")
                    }
                }
                val fallbackName = targetHubName ?: "Homey"
                Pair(emptyList<HomeyDevice>(), fallbackName)
            }

            // Stale-While-Revalidate background state synchronization
            val now = System.currentTimeMillis()
            val lastSlotSync = _lastSyncTimestamps[slotNumber] ?: 0L
            val lastGlobalSync = deviceRepo.getLastSyncTimestamp()
            val effectiveLastSync = maxOf(lastSlotSync, lastGlobalSync)
            if (now - effectiveLastSync >= freshnessIntervalMillis) {
                _lastSyncTimestamps[slotNumber] = now
                serviceScope.launch {
                    val allRes = deviceRepo.getDevicesForHub(targetHubId, forceRefresh = false)
                    val currentAll = if (allRes is HomeyResult.Success) allRes.data else emptyList()
                    if (currentAll.isNotEmpty()) {
                        val syncRes = deviceRepo.syncDeviceStates(currentAll, hubId = targetHubId)
                        if (syncRes is HomeyResult.Success) {
                            val updatedAll = syncRes.data
                            val oldQuick = quickDevices
                            val newQuick = dashboardRepo.getQuickDevicesForHub(updatedAll, targetHubId, forceRefresh = true).take(4)

                            // Pre-load icon for any newly added or updated device
                            newQuick.forEach { dev ->
                                if (!dev.iconUrl.isNullOrBlank() && TileIconHelper.getCachedIconBytes(appCtx, dev.iconUrl) == null) {
                                    TileIconHelper.loadDeviceIconBytes(appCtx, dev.iconUrl)
                                }
                            }

                            val hasChanged = oldQuick.size != newQuick.size || oldQuick.zip(newQuick).any { (old, new) ->
                                old.id != new.id ||
                                old.isActive != new.isActive ||
                                old.isAvailable != new.isAvailable ||
                                old.dimValue != new.dimValue ||
                                old.displayName != new.displayName ||
                                old.primaryCapability != new.primaryCapability ||
                                old.iconUrl != new.iconUrl
                            }

                            if (hasChanged) {
                                Log.d(_TAG, "[BaseQuickActionsTileService:onTileRequest] Slot $slotNumber state/config changed via background sync, requesting tile update")
                                try {
                                    TileService.getUpdater(appCtx).requestUpdate(TileSlotManager.getServiceClass(slotNumber))
                                } catch (e: Exception) {
                                    Log.e(_TAG, "[BaseQuickActionsTileService:onTileRequest] Failed to request update after sync: ${e.message}")
                                }
                            }
                        }
                    }
                }
            }

            val iconFingerprint = quickDevices.joinToString(";") { "${it.id}:${it.iconUrl}" }.hashCode().toString()
            val nameFingerprint = quickDevices.joinToString(";") { "${it.id}:${it.displayName}" }.hashCode().toString()
            val cachedIconsCount = quickDevices.count { !it.iconUrl.isNullOrBlank() && TileIconHelper.getCachedIconBytes(appCtx, it.iconUrl) != null }
            val resourcesVersion = "slot_${slotNumber}_${iconFingerprint}_${nameFingerprint}_${cachedIconsCount}_v2"

            _buildTile(
                context = appCtx,
                devices = quickDevices,
                deviceParameters = deviceConfig,
                hubId = targetHubId,
                hubName = displayName,
                resourcesVersion = resourcesVersion,
                freshnessIntervalMillis = freshnessIntervalMillis
            )
        }
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return serviceScope.future {
            val appCtx = applicationContext
            val storage = DependencyManager.getTokenStorage(appCtx)
            val deviceRepo = DependencyManager.getDeviceRepository(appCtx)
            val dashboardRepo = DependencyManager.getDashboardRepository(appCtx)

            var targetHubId = storage.getTileSlotHubId(slotNumber)
            if (targetHubId == null && slotNumber == 1) {
                targetHubId = if (storage.isDemoMode()) "demo" else storage.getSelectedHomeyId()
            } else if (targetHubId == null && slotNumber == 2 && storage.isDemoMode()) {
                targetHubId = "demo_2"
            }

            val resourcesBuilder = ResourceBuilders.Resources.Builder()
                .setVersion(requestParams.version)
                .addIdToImageMapping(
                    ID_IC_HOMEY_LOGO,
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.mipmap.ic_launcher_foreground)
                                .build()
                        ).build()
                )
                .addIdToImageMapping(
                    ID_IC_LIGHT,
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_light_on)
                                .build()
                        ).build()
                )
                .addIdToImageMapping(
                    ID_IC_LOCK,
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_lock_closed)
                                .build()
                        ).build()
                )
                .addIdToImageMapping(
                    ID_IC_GARAGE,
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_garage_closed)
                                .build()
                        ).build()
                )
                .addIdToImageMapping(
                    ID_IC_THERMOSTAT,
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_thermostat)
                                .build()
                        ).build()
                )
                .addIdToImageMapping(
                    ID_IC_DEFAULT,
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_device_generic)
                                .build()
                        ).build()
                )

            if (!targetHubId.isNullOrBlank()) {
                val devicesResult = deviceRepo.getDevicesForHub(targetHubId, forceRefresh = false)
                val allDevices = if (devicesResult is HomeyResult.Success) devicesResult.data else emptyList()
                val quickDevices = dashboardRepo.getQuickDevicesForHub(allDevices, targetHubId).take(4)

                for (dev in quickDevices) {
                    if (!dev.iconUrl.isNullOrBlank()) {
                        val bytes = TileIconHelper.getCachedIconBytes(appCtx, dev.iconUrl)
                            ?: TileIconHelper.loadDeviceIconBytes(appCtx, dev.iconUrl)
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
     * Builds the unconfigured placeholder Tile layout.
     *
     * @private
     */
    private fun _buildEmptyTile(
        context: Context,
        deviceParameters: DeviceParametersBuilders.DeviceParameters,
        resourcesVersion: String = "slot_${slotNumber}_empty",
        freshnessIntervalMillis: Long = 30_000L
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
            .setFreshnessIntervalMillis(freshnessIntervalMillis)
            .build()
    }

    /**
     * Builds the 2x2 grid layout inside a Wear OS Tile.
     *
     * @private
     */
    private fun _buildTile(
        context: Context,
        devices: List<HomeyDevice>,
        deviceParameters: DeviceParametersBuilders.DeviceParameters,
        hubId: String,
        hubName: String,
        resourcesVersion: String,
        freshnessIntervalMillis: Long = 30_000L
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

        // Header: Homey circular logo + Hub Name (clickable to open app for target hub)
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
                .setResourceId(ID_IC_HOMEY_LOGO)
                .setWidth(dp(16f))
                .setHeight(dp(16f))
                .build()
        )

        headerRow.addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(5f)).build())

        headerRow.addContent(
            Text.Builder(context, hubName)
                .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                .setColor(argb(0xFFFFFFFF.toInt()))
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
            // Build 2x2 Grid: Row 1 (items 0, 1) and Row 2 (items 2, 3)
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
                    val isButton = dev.isButtonAction
                    val hasRapidToggle = (!dev.isThermostat && dev.primaryCapability != CAP_TARGET_TEMPERATURE) || dev.hasOnOff || isButton

                    val clickableModifier = if (hasRapidToggle) {
                        val cap = if (dev.isThermostat && dev.primaryCapability == CAP_TARGET_TEMPERATURE && dev.hasOnOff) CAP_ONOFF else dev.primaryCapability
                        val targetActive = if (isButton) true else !dev.isActive
                        val clickableId = "toggle|${dev.id}|$cap|$targetActive|$hubId"

                        ModifiersBuilders.Clickable.Builder()
                            .setId(clickableId)
                            .setOnClick(ActionBuilders.LoadAction.Builder().build())
                            .build()
                    } else {
                        // Open app on device detail for target hub
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

                    val hasCustomIcon = !dev.iconUrl.isNullOrBlank() && TileIconHelper.getCachedIconBytes(context, dev.iconUrl) != null
                    val iconId = if (hasCustomIcon) {
                        "tile_dev_icon_${dev.id}"
                    } else {
                        when {
                            dev.isThermostat -> ID_IC_THERMOSTAT
                            dev.isLight -> ID_IC_LIGHT
                            dev.deviceClass == "lock" -> ID_IC_LOCK
                            dev.deviceClass == "garagedoor" -> ID_IC_GARAGE
                            else -> ID_IC_DEFAULT
                        }
                    }

                    val buttonColor = when {
                        // 1. Lights & Outlets
                        dev.isLight || dev.primaryCapability == "onoff" -> {
                            if (dev.isActive) 0xFF3D3219.toInt() else 0xFF2C2C2E.toInt()
                        }
                        // 2. Locks & Garage doors (isActive == false means locked/closed)
                        dev.deviceClass == "lock" || dev.primaryCapability == "locked" ||
                        dev.deviceClass == "garagedoor" || dev.primaryCapability == "garagedoor_closed" -> {
                            val isProtected = !dev.isActive
                            if (isProtected) 0xFF1E3A5F.toInt() else 0xFF2C2C2E.toInt()
                        }
                        // 3. Thermostats
                        dev.isThermostat -> 0xFF2C2C2E.toInt()
                        // 4. Buttons and other uncategorized devices
                        else -> 0xFF2C2C2E.toInt()
                    }

                    // Device icons in Tile are always crisp white matching official Homey design
                    val iconColor = 0xFFFFFFFF.toInt()

                    val btn = Button.Builder(
                        context,
                        clickableModifier
                    )
                        .setSize(dp(44f))
                        .setButtonColors(ButtonColors(argb(buttonColor), argb(iconColor)))
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
                            .setColor(argb(0xFFCCCCCC.toInt()))
                            .setMaxLines(1)
                            .build()
                    )

                    targetRow.addContent(quadrantCol.build())
                } else {
                    // Empty placeholder button opening app
                    val btn = Button.Builder(
                        context,
                        ModifiersBuilders.Clickable.Builder().setOnClick(defaultOpenAppAction).build()
                    )
                        .setSize(dp(44f))
                        .setButtonColors(ButtonColors(argb(0xFF1C1C1E.toInt()), argb(0xFF555555.toInt())))
                        .setIconContent(ID_IC_DEFAULT)
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

        val layout = LayoutElementBuilders.Layout.Builder()
            .setRoot(rootColumn.build())
            .build()

        val entry = TimelineBuilders.TimelineEntry.Builder()
            .setLayout(layout)
            .build()

        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(entry)
            .build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(resourcesVersion)
            .setTileTimeline(timeline)
            .setFreshnessIntervalMillis(freshnessIntervalMillis)
            .build()
    }
}
