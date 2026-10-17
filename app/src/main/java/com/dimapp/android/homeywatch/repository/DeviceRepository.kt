package com.dimapp.android.homeywatch.repository

import android.content.Context
import com.dimapp.android.homeywatch.R
import com.dimapp.android.homeywatch.api.HomeyApiClient
import com.dimapp.android.homeywatch.api.models.DeviceDto
import com.dimapp.android.homeywatch.api.models.SetCapabilityBody
import com.dimapp.android.homeywatch.api.models.ZoneDto
import com.dimapp.android.homeywatch.repository.models.CAP_GARAGEDOOR
import com.dimapp.android.homeywatch.repository.models.CAP_ONOFF
import com.dimapp.android.homeywatch.repository.models.CAP_DIM
import com.dimapp.android.homeywatch.repository.models.CAP_TARGET_TEMPERATURE
import com.dimapp.android.homeywatch.repository.models.CAP_MEASURE_TEMPERATURE
import com.dimapp.android.homeywatch.repository.models.CAP_LIGHT_HUE
import com.dimapp.android.homeywatch.repository.models.CAP_LIGHT_SATURATION
import com.dimapp.android.homeywatch.repository.models.CAP_LIGHT_TEMPERATURE
import com.dimapp.android.homeywatch.repository.models.DOOR_CLASSES
import com.dimapp.android.homeywatch.repository.models.LIGHT_CLASSES
import com.dimapp.android.homeywatch.repository.models.THERMOSTAT_CLASSES
import com.dimapp.android.homeywatch.repository.models.DeviceHistoryEvent
import com.dimapp.android.homeywatch.repository.models.HomeyDevice
import com.dimapp.android.homeywatch.repository.models.CapabilityMeta
import com.dimapp.android.homeywatch.repository.models.HomeyResult
import com.dimapp.android.homeywatch.repository.models.isCapabilityActive
import com.dimapp.android.homeywatch.repository.models.isButtonOrActionCapability
import com.dimapp.android.homeywatch.api.models.CapabilityDto
import com.dimapp.android.homeywatch.api.models.InsightLogDto
import com.google.gson.Gson
import com.dimapp.android.homeywatch.auth.HomeyAuthRepository
import com.dimapp.android.homeywatch.storage.TokenStorage
import com.dimapp.android.homeywatch.tile.TileIconHelper
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log

/**
 * Repository handling device-related operations (fetching, toggling, syncing).
 */
class DeviceRepository(
    context: Context,
    storage: TokenStorage,
    authRepo: HomeyAuthRepository
) : HomeyBaseRepository(context, storage, authRepo) {
 
    private val TAG = "DeviceRepository"
    private val cacheMutex = Mutex()
    private var cachedDevices: List<HomeyDevice>? = null
    private var cachedHubId: String? = null
    private var cachedZoneTemperatures: Map<String, Double> = emptyMap()
    private var demoDevices: MutableList<HomeyDevice>? = null
    private var demoDevices2: MutableList<HomeyDevice>? = null
    private val cachedHubDevices = mutableMapOf<String, List<HomeyDevice>>()

    private val _devicesFlow = MutableStateFlow<List<HomeyDevice>>(emptyList())
    val devicesFlow: StateFlow<List<HomeyDevice>> = _devicesFlow.asStateFlow()

    private val _hubDevicesFlow = MutableStateFlow<Map<String, List<HomeyDevice>>>(emptyMap())
    val hubDevicesFlow: StateFlow<Map<String, List<HomeyDevice>>> = _hubDevicesFlow.asStateFlow()

    @Volatile
    private var _lastSyncTimestamp: Long = 0L

    /**
     * Returns the epoch milliseconds of the last successful device state synchronization.
     *
     * @public
     * @return Epoch millisecond timestamp.
     */
    fun getLastSyncTimestamp(): Long = _lastSyncTimestamp

    /**
     * Returns the latest computed average temperatures mapped by zone name.
     *
     * Public method.
     *
     * @return Map of zone name to average temperature in degrees Celsius.
     * @example
     * val temps = repo.getZoneTemperatures()
     */
    fun getZoneTemperatures(): Map<String, Double> {
        return cachedZoneTemperatures
    }


    /**
     * Returns all supported devices, using an in-memory cache for structure to improve performance.
     *
     * The cache is automatically invalidated if the active hub ID changes.
     * To force a network refresh, set [forceRefresh] to true.
     */
    suspend fun getDevices(forceRefresh: Boolean = false): HomeyResult<List<HomeyDevice>> = cacheMutex.withLock {
        // Handle Demo Mode directly without network interaction
        if (storage.isDemoMode()) {
            cachedZoneTemperatures = mapOf(
                context.getString(R.string.demo_zone_ground_floor) to 21.5,
                context.getString(R.string.demo_zone_outside) to 18.0
            )
            if (demoDevices == null || forceRefresh) {
                demoDevices = _createDemoDevices().toMutableList()
                Log.d(TAG, "Initialized demo devices list with ${demoDevices?.size} mock devices.")
            }
            _devicesFlow.value = demoDevices!!.toList()
            _lastSyncTimestamp = System.currentTimeMillis()
            return HomeyResult.Success(demoDevices!!.toList())
        }

        val currentHubId = storage.getSelectedHomeyId() ?: return HomeyResult.Error(context.getString(R.string.repo_error_not_configured))
        
        // 1. Check if we have valid cached data for the current hub
        // If forceRefresh is true, we skip the cache and go straight to network.
        if (!forceRefresh && cachedHubId == currentHubId && cachedDevices != null) {
            Log.d(TAG, "Returning ${cachedDevices?.size} devices from in-memory cache.")
            _devicesFlow.value = cachedDevices!!
            return HomeyResult.Success(cachedDevices!!)
        }

        // 2. Fetch from network if cache is missing, stale, or forced
        val reason = when {
            forceRefresh -> "Forced refresh"
            cachedHubId != currentHubId -> "Hub changed ($cachedHubId -> $currentHubId)"
            else -> "Cache empty"
        }
        Log.d(TAG, "$reason. Fetching from network...")

        val service = _buildService()
        if (service == null) {
            Log.e(TAG, "Cannot fetch devices: HomeyApiService is null (check if Hub is configured).")
            return HomeyResult.Error(context.getString(R.string.repo_error_not_configured))
        }

        return try {
            val devicesResponse = service.getDevices()
            val zonesResponse = service.getZones()
            val userResponse = service.getUserMe()

            if (!devicesResponse.isSuccessful) {
                val errorBody = devicesResponse.errorBody()?.string()
                Log.e(TAG, "Devices fetch failed: HTTP ${devicesResponse.code()} - $errorBody")
                return HomeyResult.Error(context.getString(R.string.repo_error_devices_http, devicesResponse.code()))
            }
            if (!zonesResponse.isSuccessful) {
                val errorBody = zonesResponse.errorBody()?.string()
                Log.e(TAG, "Zones fetch failed: HTTP ${zonesResponse.code()} - $errorBody")
                return HomeyResult.Error(context.getString(R.string.repo_error_zones_http, zonesResponse.code()))
            }

            val devices = devicesResponse.body() ?: emptyMap()
            val zones = zonesResponse.body() ?: emptyMap()
            val favoritesList = userResponse.body()?.properties?.favoriteDevices ?: emptyList()

            // Compute average room temperatures across all devices reporting measure_temperature in each zone
            val zoneTempReadings = mutableMapOf<String, MutableList<Double>>()
            for (dto in devices.values) {
                val zoneId = dto.zone ?: continue
                val caps = dto.capabilitiesObj ?: continue
                val tempCap = caps["measure_temperature"]
                val tempVal = (tempCap?.value as? Number)?.toDouble()
                if (tempVal != null && !tempVal.isNaN()) {
                    zoneTempReadings.getOrPut(zoneId) { mutableListOf() }.add(tempVal)
                }
            }
            val computedTemps = mutableMapOf<String, Double>()
            for ((zId, readings) in zoneTempReadings) {
                if (readings.isNotEmpty()) {
                    val zonePathName = _buildZonePathName(zId, zones)
                    computedTemps[zonePathName] = readings.average()
                }
            }
            cachedZoneTemperatures = computedTemps

            val excludedIds = devices.values
                .flatMap { it.settings?.deviceIds ?: emptyList() }
                .toSet()

            val globalZoneOrder = _computeGlobalZoneOrder(zones)

            val mappedDevices = devices.values
                .filter { device -> _isSupportedDevice(device) }
                .map { device -> _toHomeyDevice(device, zones, favoritesList, excludedIds, globalZoneOrder) }
                .sortedBy { it.name }

            // 3. Update cache
            cachedDevices = mappedDevices
            cachedHubId = currentHubId
            _devicesFlow.value = mappedDevices
            _lastSyncTimestamp = System.currentTimeMillis()
            
            // Asynchronously preload icons for fast Tile & UI display
            TileIconHelper.preloadDeviceIcons(context, mappedDevices, currentHubId)

            Log.d(TAG, "Fetched ${mappedDevices.size} devices from network and saved to cache.")
            HomeyResult.Success(mappedDevices)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during getDevices: ${e.message}", e)
            HomeyResult.Error(context.getString(R.string.repo_error_conn_failed, e.message ?: "Unknown error"))
        }
    }

    /**
     * Clears the in-memory device cache, forcing the next [getDevices] call to fetch from network.
     * Use this for manual refresh flows or after significant configuration changes.
     */
    fun invalidateCache() {
        Log.d(TAG, "Device cache invalidated.")
        cachedDevices = null
        cachedHubId = null
        cachedZoneTemperatures = emptyMap()
        demoDevices = null
        demoDevices2 = null
        cachedHubDevices.clear()
        _devicesFlow.value = emptyList()
        _hubDevicesFlow.value = emptyMap()
        _lastSyncTimestamp = 0L
    }

    /**
     * Checks if the in-memory cache is currently empty.
     */
    fun isCacheEmpty(): Boolean {
        return cachedDevices == null
    }

    /**
     * Returns devices for a specific hub (or current active hub if [hubId] is null or matches active hub).
     *
     * @public
     * @param hubId Target Homey ID or "demo".
     * @param forceRefresh Whether to force a network reload.
     * @return [HomeyResult] containing devices.
     */
    suspend fun getDevicesForHub(
        hubId: String?,
        forceRefresh: Boolean = false
    ): HomeyResult<List<HomeyDevice>> {
        if (hubId.isNullOrBlank() || hubId == storage.getSelectedHomeyId()) {
            return getDevices(forceRefresh)
        }
        if (hubId == "demo") {
            return cacheMutex.withLock {
                if (demoDevices == null || forceRefresh) {
                    demoDevices = _createDemoDevices().toMutableList()
                }
                HomeyResult.Success(demoDevices!!.toList())
            }
        }
        if (hubId == "demo_2") {
            return cacheMutex.withLock {
                if (demoDevices2 == null || forceRefresh) {
                    demoDevices2 = _createDemoDevices2().toMutableList()
                }
                HomeyResult.Success(demoDevices2!!.toList())
            }
        }

        return cacheMutex.withLock {
            if (!forceRefresh && cachedHubDevices.containsKey(hubId)) {
                return@withLock HomeyResult.Success(cachedHubDevices[hubId]!!)
            }

            val entry = storage.getHubToken(hubId)
                ?: return@withLock HomeyResult.Error(context.getString(R.string.repo_error_not_configured))
            val apiUrl = entry.apiUrl
                ?: return@withLock HomeyResult.Error(context.getString(R.string.repo_error_not_configured))
            val sessionToken = entry.sessionToken
                ?: return@withLock HomeyResult.Error(context.getString(R.string.repo_error_not_configured))

            val service = HomeyApiClient.createForHub(apiUrl, sessionToken)
            try {
                val devicesResponse = service.getDevices()
                val zonesResponse = service.getZones()
                val userResponse = service.getUserMe()

                if (!devicesResponse.isSuccessful) {
                    return@withLock HomeyResult.Error(context.getString(R.string.repo_error_devices_http, devicesResponse.code()))
                }
                val devices = devicesResponse.body() ?: emptyMap()
                val zones = zonesResponse.body() ?: emptyMap()
                val favoritesList = userResponse.body()?.properties?.favoriteDevices ?: emptyList()

                val excludedIds = devices.values
                    .flatMap { it.settings?.deviceIds ?: emptyList() }
                    .toSet()

                val globalZoneOrder = _computeGlobalZoneOrder(zones)
                val mappedDevices = devices.values
                    .filter { device -> _isSupportedDevice(device) }
                    .map { device -> _toHomeyDevice(device, zones, favoritesList, excludedIds, globalZoneOrder, targetHubId = hubId) }
                    .sortedBy { it.name }

                cachedHubDevices[hubId] = mappedDevices
                _hubDevicesFlow.value = cachedHubDevices.toMap()
                if (hubId == storage.getSelectedHomeyId()) {
                    cachedDevices = mappedDevices
                    _devicesFlow.value = mappedDevices
                }
                TileIconHelper.preloadDeviceIcons(context, mappedDevices, hubId)
                HomeyResult.Success(mappedDevices)
            } catch (e: Exception) {
                HomeyResult.Error(context.getString(R.string.repo_error_conn_failed, e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Toggles a device (door/light) by setting its primary capability.
     * Optionally routes to a specific [hubId] if provided.
     */
    suspend fun toggleDeviceState(
        deviceId: String,
        capabilityId: String,
        makeActive: Boolean,
        hubId: String? = null
    ): HomeyResult<String> {
        // Handle Demo Mode optimistic state toggle
        if (storage.isDemoMode() || hubId?.startsWith("demo") == true) {
            return cacheMutex.withLock {
                val isSecondDemo = hubId == "demo_2"
                val list = if (isSecondDemo) {
                    demoDevices2 ?: _createDemoDevices2().toMutableList()
                } else {
                    demoDevices ?: _createDemoDevices().toMutableList()
                }
                val index = list.indexOfFirst { it.id == deviceId }
                if (index != -1) {
                    val isBtn = isButtonOrActionCapability(capabilityId)
                    val newActive = if (isBtn) false else makeActive
                    list[index] = list[index].copy(isActive = newActive)
                    if (isSecondDemo) {
                        demoDevices2 = list
                    } else {
                        demoDevices = list
                        _devicesFlow.value = list.toList()
                    }
                    Log.d(TAG, "[DeviceRepository:toggleDeviceState] Toggled demo device $deviceId to isActive=$newActive on hub=$hubId")
                }
                try {
                    com.dimapp.android.homeywatch.tile.TileSlotManager.updateAllSlots(context)
                } catch (e: Exception) {
                    Log.w(TAG, "[DeviceRepository:toggleDeviceState] Failed to notify TileSlotManager in demo: ${e.message}")
                }
                HomeyResult.Success(context.getString(R.string.repo_success_toggle))
            }
        }

        val service = if (!hubId.isNullOrBlank() && hubId != storage.getSelectedHomeyId()) {
            val entry = storage.getHubToken(hubId)
            val apiUrl = entry?.apiUrl
            val token = entry?.sessionToken
            if (apiUrl != null && token != null) {
                HomeyApiClient.createForHub(apiUrl, token)
            } else {
                _buildService()
            }
        } else {
            _buildService()
        } ?: return HomeyResult.Error(context.getString(R.string.repo_error_not_configured))

        val isButtonOrAction = isButtonOrActionCapability(capabilityId)
        val value: Any = when {
            isButtonOrAction         -> true
            capabilityId == CAP_GARAGEDOOR           -> !makeActive  
            capabilityId == "locked"                 -> !makeActive  
            capabilityId == "windowcoverings_closed" -> !makeActive
            else                                     -> makeActive   
        }

        return try {
            val response = service.setCapability(
                deviceId = deviceId,
                capabilityId = capabilityId,
                body = SetCapabilityBody(value = value)
            )

            if (response.isSuccessful) {
                val nextActive = if (isButtonOrAction) false else makeActive
                cacheMutex.withLock {
                    cachedDevices = cachedDevices?.map { dev ->
                        if (dev.id == deviceId) {
                            val updatedRaw = dev.rawCapabilities.toMutableMap()
                            updatedRaw[capabilityId] = value
                            dev.copy(
                                isActive = nextActive,
                                rawCapabilities = updatedRaw
                            )
                        } else {
                            dev
                        }
                    }
                    cachedDevices?.let { _devicesFlow.value = it }

                    if (!hubId.isNullOrBlank() && cachedHubDevices.containsKey(hubId)) {
                        cachedHubDevices[hubId] = cachedHubDevices[hubId]!!.map { dev ->
                            if (dev.id == deviceId) {
                                val updatedRaw = dev.rawCapabilities.toMutableMap()
                                updatedRaw[capabilityId] = value
                                dev.copy(
                                    isActive = nextActive,
                                    rawCapabilities = updatedRaw
                                )
                            } else {
                                dev
                            }
                        }
                        _hubDevicesFlow.value = cachedHubDevices.toMap()
                    }
                }
                try {
                    com.dimapp.android.homeywatch.tile.TileSlotManager.updateAllSlots(context)
                } catch (e: Exception) {
                    Log.w(TAG, "[DeviceRepository:toggleDeviceState] Failed to notify TileSlotManager: ${e.message}")
                }
                Log.d(TAG, "[DeviceRepository:toggleDeviceState] Successfully toggled $deviceId ($capabilityId) to $value")
                HomeyResult.Success(context.getString(R.string.repo_success_toggle))
            } else {
                HomeyResult.Error(context.getString(R.string.repo_error_toggle_http, response.code()))
            }
        } catch (e: Exception) {
            HomeyResult.Error(context.getString(R.string.repo_error_network, e.message ?: "Unknown error"))
        }
    }

    /**
     * Sets an arbitrary capability value (e.g. dim, target_temperature, light_hue) on a device.
     *
     * @param deviceId Device UUID.
     * @param capabilityId Capability ID to update.
     * @param value The value to apply (Number, Boolean, or String).
     * @return [HomeyResult] indicating success or failure.
     */
    suspend fun setDeviceCapabilityValue(
        deviceId: String,
        capabilityId: String,
        value: Any
    ): HomeyResult<String> {
        // Handle Demo Mode state mutation
        if (storage.isDemoMode()) {
            val list = demoDevices ?: _createDemoDevices().toMutableList()
            val index = list.indexOfFirst { it.id == deviceId }
            if (index != -1) {
                val current = list[index]
                val updatedRaw = current.rawCapabilities.toMutableMap()
                updatedRaw[capabilityId] = value
                if (capabilityId == CAP_LIGHT_HUE || capabilityId == CAP_LIGHT_SATURATION) {
                    updatedRaw["light_mode"] = "color"
                } else if (capabilityId == CAP_LIGHT_TEMPERATURE) {
                    updatedRaw["light_mode"] = "temperature"
                }
                val numVal = (value as? Number)?.toDouble() ?: (value as? String)?.toDoubleOrNull()
                val updated = when (capabilityId) {
                    CAP_DIM -> current.copy(dimValue = numVal, rawCapabilities = updatedRaw)
                    CAP_TARGET_TEMPERATURE -> current.copy(targetTemperature = numVal, rawCapabilities = updatedRaw)
                    CAP_LIGHT_HUE -> current.copy(lightHue = numVal, rawCapabilities = updatedRaw)
                    CAP_LIGHT_SATURATION -> current.copy(lightSaturation = numVal, rawCapabilities = updatedRaw)
                    CAP_LIGHT_TEMPERATURE -> current.copy(lightTemperature = numVal, rawCapabilities = updatedRaw)
                    else -> current.copy(rawCapabilities = updatedRaw)
                }
                list[index] = updated
                demoDevices = list
                _devicesFlow.value = list.toList()
                Log.d(TAG, "Updated demo device $deviceId capability $capabilityId to $value")
            }
            try {
                com.dimapp.android.homeywatch.tile.TileSlotManager.updateAllSlots(context)
            } catch (e: Exception) {
                Log.w(TAG, "[DeviceRepository:setDeviceCapabilityValue] Failed to notify TileSlotManager in demo: ${e.message}")
            }
            return HomeyResult.Success(context.getString(R.string.repo_success_toggle))
        }

        val service = _buildService() ?: return HomeyResult.Error(context.getString(R.string.repo_error_not_configured))

        return try {
            val response = service.setCapability(
                deviceId = deviceId,
                capabilityId = capabilityId,
                body = SetCapabilityBody(value = value)
            )

            if (response.isSuccessful) {
                cacheMutex.withLock {
                    cachedDevices = cachedDevices?.map { dev ->
                        if (dev.id == deviceId) {
                            val updatedRaw = dev.rawCapabilities.toMutableMap()
                            updatedRaw[capabilityId] = value
                            if (capabilityId == CAP_LIGHT_HUE || capabilityId == CAP_LIGHT_SATURATION) {
                                updatedRaw["light_mode"] = "color"
                            } else if (capabilityId == CAP_LIGHT_TEMPERATURE) {
                                updatedRaw["light_mode"] = "temperature"
                            }
                            val numVal = (value as? Number)?.toDouble() ?: (value as? String)?.toDoubleOrNull()
                            when (capabilityId) {
                                CAP_DIM -> dev.copy(dimValue = numVal, rawCapabilities = updatedRaw)
                                CAP_TARGET_TEMPERATURE -> dev.copy(targetTemperature = numVal, rawCapabilities = updatedRaw)
                                CAP_LIGHT_HUE -> dev.copy(lightHue = numVal, rawCapabilities = updatedRaw)
                                CAP_LIGHT_SATURATION -> dev.copy(lightSaturation = numVal, rawCapabilities = updatedRaw)
                                CAP_LIGHT_TEMPERATURE -> dev.copy(lightTemperature = numVal, rawCapabilities = updatedRaw)
                                else -> dev.copy(rawCapabilities = updatedRaw)
                            }
                        } else {
                            dev
                        }
                    }
                    cachedDevices?.let { _devicesFlow.value = it }
                }
                try {
                    com.dimapp.android.homeywatch.tile.TileSlotManager.updateAllSlots(context)
                } catch (e: Exception) {
                    Log.w(TAG, "[DeviceRepository:setDeviceCapabilityValue] Failed to notify TileSlotManager: ${e.message}")
                }
                HomeyResult.Success(context.getString(R.string.repo_success_toggle))
            } else {
                HomeyResult.Error(context.getString(R.string.repo_error_toggle_http, response.code()))
            }
        } catch (e: Exception) {
            HomeyResult.Error(context.getString(R.string.repo_error_network, e.message ?: "Unknown error"))
        }
    }

    /**
     * Updates capability values and active states for a given list of [HomeyDevice] items
     * using a single batch call to `GET /manager/devices/device`.
     *
     * @public
     * @param currentDevices The list of devices to update states for.
     * @param hubId Optional target Homey ID. Defaults to current active hub if null.
     * @return [HomeyResult.Success] containing updated [HomeyDevice] items, or [HomeyResult.Error].
     * @example val result = repo.syncDeviceStates(allDevices)
     */
    suspend fun syncDeviceStates(
        currentDevices: List<HomeyDevice>,
        hubId: String? = null
    ): HomeyResult<List<HomeyDevice>> {
        if (currentDevices.isEmpty()) return HomeyResult.Success(emptyList())

        if (storage.isDemoMode() || hubId?.startsWith("demo") == true) {
            val isSecondDemo = hubId == "demo_2"
            return cacheMutex.withLock {
                val list = if (isSecondDemo) {
                    demoDevices2 ?: currentDevices
                } else {
                    demoDevices ?: currentDevices
                }
                _lastSyncTimestamp = System.currentTimeMillis()
                if (!isSecondDemo) {
                    _devicesFlow.value = list.toList()
                }
                HomeyResult.Success(list)
            }
        }

        val targetHubId = if (!hubId.isNullOrBlank()) hubId else storage.getSelectedHomeyId()
        val isDifferentHub = !targetHubId.isNullOrBlank() && targetHubId != storage.getSelectedHomeyId()

        val service = if (isDifferentHub) {
            val entry = storage.getHubToken(targetHubId!!)
            val apiUrl = entry?.apiUrl
            val token = entry?.sessionToken
            if (apiUrl != null && token != null) {
                HomeyApiClient.createForHub(apiUrl, token)
            } else {
                _buildService()
            }
        } else {
            _buildService()
        } ?: return HomeyResult.Error(context.getString(R.string.repo_error_not_configured))

        return try {
            val response = service.getDevices()
            if (!response.isSuccessful) {
                Log.e(TAG, "[DeviceRepository:syncDeviceStates] Batch sync failed: HTTP ${response.code()} for hub=$targetHubId")
                return HomeyResult.Error(context.getString(R.string.repo_error_devices_http, response.code()))
            }

            val remoteDevices = response.body() ?: emptyMap()
            val updatedDevices = currentDevices.map { device ->
                val remoteDto = remoteDevices[device.id]
                if (remoteDto != null) {
                    val caps = remoteDto.capabilitiesObj ?: emptyMap()
                    val rawCaps = caps.mapValues { it.value.value }
                    val rawValue = caps[device.primaryCapability]?.value
                    val newIsActive = isCapabilityActive(device.primaryCapability, rawValue)
                    val newIsAvailable = remoteDto.available != false

                    val dimVal = (caps[CAP_DIM]?.value as? Number)?.toDouble() ?: device.dimValue
                    val targetTemp = (caps[CAP_TARGET_TEMPERATURE]?.value as? Number)?.toDouble() ?: device.targetTemperature
                    val devTemp = (caps[CAP_MEASURE_TEMPERATURE]?.value as? Number)?.toDouble() ?: device.temperature
                    val hue = (caps[CAP_LIGHT_HUE]?.value as? Number)?.toDouble() ?: device.lightHue
                    val sat = (caps[CAP_LIGHT_SATURATION]?.value as? Number)?.toDouble() ?: device.lightSaturation
                    val colorTemp = (caps[CAP_LIGHT_TEMPERATURE]?.value as? Number)?.toDouble() ?: device.lightTemperature
                    val capMeta = _parseCapabilitiesMetadata(caps).ifEmpty { device.capabilitiesMetadata }

                    val resolvedIconUrl = if (!remoteDto.iconOverride.isNullOrBlank()) {
                        "https://my.homey.app/img/devices/${remoteDto.iconOverride}.svg"
                    } else if (!remoteDto.iconObj?.url.isNullOrBlank()) {
                        val raw = remoteDto.iconObj.url
                        val hub = targetHubId ?: storage.getSelectedHomeyId()
                        if (raw.startsWith("http")) raw else {
                            val path = if (raw.startsWith("/")) raw else "/$raw"
                            "https://$hub.connect.athom.com$path"
                        }
                    } else {
                        device.iconUrl
                    }

                    device.copy(
                        isActive = newIsActive,
                        isAvailable = newIsAvailable,
                        dimValue = dimVal,
                        targetTemperature = targetTemp,
                        currentTemperature = devTemp,
                        temperature = devTemp,
                        lightHue = hue,
                        lightSaturation = sat,
                        lightTemperature = colorTemp,
                        rawCapabilities = rawCaps,
                        capabilitiesMetadata = capMeta,
                        iconUrl = resolvedIconUrl
                    )
                } else {
                    device
                }
            }

            cacheMutex.withLock {
                val updatedMap = updatedDevices.associateBy { it.id }
                if (isDifferentHub) {
                    val updatedHub = cachedHubDevices[targetHubId!!]?.map { dev ->
                        updatedMap[dev.id] ?: dev
                    } ?: updatedDevices
                    cachedHubDevices[targetHubId!!] = updatedHub
                    _hubDevicesFlow.value = cachedHubDevices.toMap()
                } else {
                    val updatedMain = cachedDevices?.map { dev ->
                        updatedMap[dev.id] ?: dev
                    } ?: updatedDevices
                    cachedDevices = updatedMain
                    _devicesFlow.value = updatedMain
                }
            }

            _lastSyncTimestamp = System.currentTimeMillis()
            Log.d(TAG, "[DeviceRepository:syncDeviceStates] Successfully synced ${updatedDevices.size} devices via single batch call for hub=$targetHubId.")
            TileIconHelper.preloadDeviceIcons(context, updatedDevices, targetHubId)
            HomeyResult.Success(updatedDevices)
        } catch (e: Exception) {
            Log.e(TAG, "[DeviceRepository:syncDeviceStates] Batch sync exception: ${e.message}")
            HomeyResult.Error(context.getString(R.string.repo_error_sync_failed, e.message ?: "Unknown error"))
        }
    }

    /**
     * Fetches and refreshes state for a single device directly from Homey REST API on-demand.
     *
     * @public
     * @param deviceId UUID of the device to refresh.
     * @return [HomeyResult] containing the refreshed [HomeyDevice].
     * @example
     * val result = repo.refreshSingleDevice("device-id")
     */
    suspend fun refreshSingleDevice(deviceId: String): HomeyResult<HomeyDevice> {
        if (storage.isDemoMode()) {
            val dev = (demoDevices ?: cachedDevices)?.find { it.id == deviceId }
            return if (dev != null) HomeyResult.Success(dev) else HomeyResult.Error("Device not found in demo mode")
        }

        val service = _buildService() ?: return HomeyResult.Error(context.getString(R.string.repo_error_not_configured))

        return try {
            val response = service.getDevice(deviceId)
            if (!response.isSuccessful) {
                Log.e(TAG, "[DeviceRepository:refreshSingleDevice] Failed: HTTP ${response.code()} for device=$deviceId")
                return HomeyResult.Error(context.getString(R.string.repo_error_devices_http, response.code()))
            }

            val remoteDto = response.body()
                ?: return HomeyResult.Error(context.getString(R.string.repo_error_devices_http, response.code()))

            var updatedDevice: HomeyDevice? = null
            cacheMutex.withLock {
                val currentList = cachedDevices ?: emptyList()
                val existing = currentList.find { it.id == deviceId }
                if (existing != null) {
                    val caps = remoteDto.capabilitiesObj ?: emptyMap()
                    val rawCaps = caps.mapValues { it.value.value }
                    val rawValue = caps[existing.primaryCapability]?.value
                    val newIsActive = isCapabilityActive(existing.primaryCapability, rawValue)
                    val newIsAvailable = remoteDto.available != false

                    val dimVal = (caps[CAP_DIM]?.value as? Number)?.toDouble() ?: existing.dimValue
                    val targetTemp = (caps[CAP_TARGET_TEMPERATURE]?.value as? Number)?.toDouble() ?: existing.targetTemperature
                    val devTemp = (caps[CAP_MEASURE_TEMPERATURE]?.value as? Number)?.toDouble() ?: existing.temperature
                    val hue = (caps[CAP_LIGHT_HUE]?.value as? Number)?.toDouble() ?: existing.lightHue
                    val sat = (caps[CAP_LIGHT_SATURATION]?.value as? Number)?.toDouble() ?: existing.lightSaturation
                    val colorTemp = (caps[CAP_LIGHT_TEMPERATURE]?.value as? Number)?.toDouble() ?: existing.lightTemperature
                    val capMeta = _parseCapabilitiesMetadata(caps).ifEmpty { existing.capabilitiesMetadata }

                    val updated = existing.copy(
                        isActive = newIsActive,
                        isAvailable = newIsAvailable,
                        dimValue = dimVal,
                        targetTemperature = targetTemp,
                        currentTemperature = devTemp,
                        temperature = devTemp,
                        lightHue = hue,
                        lightSaturation = sat,
                        lightTemperature = colorTemp,
                        rawCapabilities = rawCaps,
                        capabilitiesMetadata = capMeta
                    )
                    updatedDevice = updated
                    val newList = currentList.map { if (it.id == deviceId) updated else it }
                    cachedDevices = newList
                    _devicesFlow.value = newList
                }
            }

            if (updatedDevice != null) {
                Log.d(TAG, "[DeviceRepository:refreshSingleDevice] Successfully refreshed state for $deviceId")
                HomeyResult.Success(updatedDevice!!)
            } else {
                HomeyResult.Error("Device $deviceId not found in cache")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DeviceRepository:refreshSingleDevice] Exception: ${e.message}", e)
            HomeyResult.Error(context.getString(R.string.repo_error_network, e.message ?: "Unknown error"))
        }
    }

    /**
     * Retrieves the state transition history for a specific device from Homey Insights.
     * Only loaded on-demand when the user views the Recap / History tab.
     *
     * @public
     * @param deviceId UUID of the device to inspect.
     * @return [HomeyResult] containing list of [DeviceHistoryEvent]s ordered from newest to oldest.
     * @example
     * val history = repo.getDeviceStateHistory("device-id")
     */
    suspend fun getDeviceStateHistory(deviceId: String): HomeyResult<List<DeviceHistoryEvent>> {
        if (storage.isDemoMode()) {
            val now = System.currentTimeMillis()
            val demoEvents = listOf(
                DeviceHistoryEvent(
                    timestamp = now - (2 * 3600 * 1000L),
                    isOn = true,
                    stateText = context.getString(R.string.device_history_turned_on)
                ),
                DeviceHistoryEvent(
                    timestamp = now - (4 * 3600 * 1000L),
                    isOn = true,
                    stateText = context.getString(R.string.device_history_turned_on)
                ),
                DeviceHistoryEvent(
                    timestamp = now - (24 * 3600 * 1000L),
                    isOn = false,
                    stateText = context.getString(R.string.device_history_turned_off)
                ),
                DeviceHistoryEvent(
                    timestamp = now - (28 * 3600 * 1000L),
                    isOn = true,
                    stateText = context.getString(R.string.device_history_turned_on)
                ),
                DeviceHistoryEvent(
                    timestamp = now - (3 * 24 * 3600 * 1000L),
                    isOn = false,
                    stateText = context.getString(R.string.device_history_turned_off)
                )
            )
            return HomeyResult.Success(demoEvents)
        }

        val service = _buildService() ?: return HomeyResult.Error(context.getString(R.string.repo_error_not_configured))

        return try {
            val logsResponse = service.getInsightsLogs()
            if (!logsResponse.isSuccessful) {
                Log.e(TAG, "[DeviceRepository:getDeviceStateHistory] Failed to get insight logs: HTTP ${logsResponse.code()} - ${logsResponse.errorBody()?.string()}")
                return HomeyResult.Error(context.getString(R.string.recap_error_history))
            }

            val rootLogsJson = logsResponse.body()
            val logList = mutableListOf<InsightLogDto>()
            val gson = Gson()

            if (rootLogsJson != null) {
                if (rootLogsJson.isJsonArray) {
                    for (elem in rootLogsJson.asJsonArray) {
                        if (elem.isJsonObject) {
                            try {
                                val log = gson.fromJson(elem, InsightLogDto::class.java)
                                if (log != null) logList.add(log)
                            } catch (e: Exception) {
                                Log.w(TAG, "[DeviceRepository:getDeviceStateHistory] Failed to parse log element: ${e.message}")
                            }
                        }
                    }
                } else if (rootLogsJson.isJsonObject) {
                    for ((key, elem) in rootLogsJson.asJsonObject.entrySet()) {
                        if (elem.isJsonObject) {
                            try {
                                val log = gson.fromJson(elem, InsightLogDto::class.java)
                                if (log != null) {
                                    val resolvedLog = if (log.id.isNullOrBlank()) log.copy(id = key) else log
                                    logList.add(resolvedLog)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "[DeviceRepository:getDeviceStateHistory] Failed to parse log entry: ${e.message}")
                            }
                        }
                    }
                }
            }

            Log.d(TAG, "[DeviceRepository:getDeviceStateHistory] Retrieved ${logList.size} insight logs. Searching for device $deviceId")

            val deviceTargetUri = "homey:device:$deviceId"
            val deviceLogs = logList.filter { log ->
                log.ownerUri?.equals(deviceTargetUri, ignoreCase = true) == true ||
                    log.ownerUri?.contains(deviceId, ignoreCase = true) == true ||
                    log.ownerId?.equals(deviceId, ignoreCase = true) == true ||
                    log.uri?.contains(deviceId, ignoreCase = true) == true ||
                    log.id?.contains(deviceId, ignoreCase = true) == true
            }
            Log.d(TAG, "[DeviceRepository:getDeviceStateHistory] Found ${deviceLogs.size} logs for device $deviceId: ${deviceLogs.map { it.id }}")

            val targetLog = deviceLogs.firstOrNull { log ->
                val idStr = log.id ?: ""
                idStr.equals(CAP_ONOFF, ignoreCase = true) ||
                    idStr.endsWith(":$CAP_ONOFF", ignoreCase = true) ||
                    idStr.endsWith(".$CAP_ONOFF", ignoreCase = true)
            } ?: deviceLogs.firstOrNull { log ->
                val idStr = log.id ?: ""
                !idStr.contains(CAP_DIM, ignoreCase = true)
            } ?: deviceLogs.firstOrNull()

            if (targetLog == null) {
                Log.d(TAG, "[DeviceRepository:getDeviceStateHistory] No matching insights log found for device $deviceId")
                return HomeyResult.Success(emptyList())
            }

            val ownerUri = targetLog.ownerUri?.takeIf { it.isNotBlank() }
                ?: targetLog.uri?.takeIf { it.isNotBlank() }
                ?: deviceTargetUri
            val rawId = targetLog.id?.takeIf { it.isNotBlank() } ?: CAP_ONOFF
            val shortId = rawId.substringAfterLast(":")

            Log.d(TAG, "[DeviceRepository:getDeviceStateHistory] Target log found. ownerUri=$ownerUri, rawId=$rawId, shortId=$shortId, title=${targetLog.title}")

            // Attempt 1: Using ownerUri and rawId
            var entriesResponse = service.getInsightEntries(uri = ownerUri, logId = rawId, resolution = "last7Days")
            if (!entriesResponse.isSuccessful && shortId != rawId) {
                Log.d(TAG, "[DeviceRepository:getDeviceStateHistory] First attempt HTTP ${entriesResponse.code()}. Retrying with shortId=$shortId")
                entriesResponse = service.getInsightEntries(uri = ownerUri, logId = shortId, resolution = "last7Days")
            }
            if (!entriesResponse.isSuccessful) {
                Log.d(TAG, "[DeviceRepository:getDeviceStateHistory] Retrying with deviceId=$deviceId and logId=$shortId")
                entriesResponse = service.getInsightEntries(uri = deviceId, logId = shortId, resolution = "last7Days")
            }

            if (!entriesResponse.isSuccessful) {
                Log.e(TAG, "[DeviceRepository:getDeviceStateHistory] Failed to get insight entries: HTTP ${entriesResponse.code()} - ${entriesResponse.errorBody()?.string()}")
                return HomeyResult.Error(context.getString(R.string.recap_error_history))
            }

            val rootJson = entriesResponse.body()
            Log.d(TAG, "[DeviceRepository:getDeviceStateHistory] Insight entries response received: $rootJson")

            val pointsJson = when {
                rootJson == null -> null
                rootJson.isJsonArray -> rootJson.asJsonArray
                rootJson.isJsonObject && rootJson.asJsonObject.has("values") && rootJson.asJsonObject.get("values").isJsonArray -> {
                    rootJson.asJsonObject.getAsJsonArray("values")
                }
                else -> null
            }

            if (pointsJson == null || pointsJson.size() == 0) {
                Log.d(TAG, "[DeviceRepository:getDeviceStateHistory] No historical data points found for $deviceId")
                return HomeyResult.Success(emptyList())
            }

            val events = mutableListOf<DeviceHistoryEvent>()
            var previousState: Boolean? = null

            for (elem in pointsJson) {
                if (!elem.isJsonObject) continue
                val obj = elem.asJsonObject
                val ts = _parseIsoOrEpochTimestamp(obj.get("t")) ?: continue
                val boolVal = _parseInsightValueToBoolean(obj.get("v")) ?: continue

                if (previousState == null || previousState != boolVal) {
                    val text = if (boolVal) {
                        context.getString(R.string.device_history_turned_on)
                    } else {
                        context.getString(R.string.device_history_turned_off)
                    }
                    events.add(DeviceHistoryEvent(timestamp = ts, isOn = boolVal, stateText = text))
                    previousState = boolVal
                }
            }

            val sorted = events.sortedByDescending { it.timestamp }
            Log.d(TAG, "[DeviceRepository:getDeviceStateHistory] Found ${sorted.size} historical state transitions for $deviceId")
            HomeyResult.Success(sorted)
        } catch (e: Exception) {
            Log.e(TAG, "[DeviceRepository:getDeviceStateHistory] Exception: ${e.message}", e)
            HomeyResult.Error(context.getString(R.string.repo_error_network, e.message ?: "Unknown error"))
        }
    }

    // ── Private Methods ───────────────────────────────────────────────────────

    private fun _isSupportedDevice(device: DeviceDto): Boolean {
        val clazz = device.effectiveClass?.lowercase() ?: ""
        val caps = device.capabilitiesObj ?: emptyMap()
        return clazz in DOOR_CLASSES || clazz in LIGHT_CLASSES || clazz in THERMOSTAT_CLASSES ||
                caps.containsKey(CAP_ONOFF) || caps.containsKey(CAP_DIM) ||
                caps.containsKey(CAP_TARGET_TEMPERATURE) || caps.containsKey("locked") ||
                caps.containsKey(CAP_GARAGEDOOR) || caps.containsKey("windowcoverings_set") ||
                caps.containsKey("windowcoverings_state") || caps.containsKey("windowcoverings_closed") ||
                caps.keys.any { isButtonOrActionCapability(it) }
    }

    private fun _toHomeyDevice(
        device: DeviceDto,
        zones: Map<String, ZoneDto>,
        favoritesList: List<String>,
        excludedIds: Set<String>,
        globalZoneOrder: Map<String, Int>,
        targetHubId: String? = null
    ): HomeyDevice {
        val caps = device.capabilitiesObj ?: emptyMap()
        val clazz = device.effectiveClass?.lowercase() ?: ""
        val isThermostat = clazz in THERMOSTAT_CLASSES
        val isLight = clazz in LIGHT_CLASSES || caps.containsKey(CAP_LIGHT_HUE) || caps.containsKey(CAP_LIGHT_TEMPERATURE)
        val hasOnOff = caps.containsKey(CAP_ONOFF)

        // 1. Resolve Quick Action according to official Homey configuration
        val homeyQuickAction = device.ui?.quickAction
        val explicitQuickActionCap = when {
            !homeyQuickAction.isNullOrBlank() && caps.containsKey(homeyQuickAction) && caps[homeyQuickAction]?.setable != false -> {
                homeyQuickAction
            }
            else -> {
                val uiQaCap = caps.entries.firstOrNull { it.value.uiQuickAction == true && it.value.setable != false }?.key
                val uiListQaCap = device.ui?.quickActions?.firstOrNull { caps.containsKey(it) && caps[it]?.setable != false }
                uiQaCap ?: uiListQaCap
            }
        }

        val resolvedQuickActionCap: String? = when {
            explicitQuickActionCap != null -> explicitQuickActionCap
            // Fallback for standard actionable devices if Homey didn't provide ui.quickAction explicitly
            caps.containsKey(CAP_GARAGEDOOR) && caps[CAP_GARAGEDOOR]?.setable != false -> CAP_GARAGEDOOR
            caps.containsKey("locked") && caps["locked"]?.setable != false -> "locked"
            hasOnOff && caps[CAP_ONOFF]?.setable != false -> CAP_ONOFF
            caps.keys.any { isButtonOrActionCapability(it) && caps[it]?.setable != false } -> {
                caps.keys.first { isButtonOrActionCapability(it) && caps[it]?.setable != false }
            }
            // Pure thermostats or read-only devices without actionable quick action
            else -> null
        }

        val primaryCap = resolvedQuickActionCap ?: when {
            caps.containsKey(CAP_GARAGEDOOR) -> CAP_GARAGEDOOR
            caps.containsKey("locked")       -> "locked"
            hasOnOff                         -> CAP_ONOFF
            isThermostat && caps.containsKey(CAP_TARGET_TEMPERATURE) -> CAP_TARGET_TEMPERATURE
            else                             -> caps.keys.firstOrNull() ?: CAP_ONOFF
        }

        val rawCaps = caps.mapValues { it.value.value }
        val rawValue = caps[primaryCap]?.value
        val isActive = isCapabilityActive(primaryCap, rawValue)

        val zoneName = _buildZonePathName(device.zone, zones)
        val zoneOrder = device.zone?.let { globalZoneOrder[it] } ?: Int.MAX_VALUE

        val isZoneActive = device.zone?.let { zones[it]?.active } == true
        val devTemp = (caps[CAP_MEASURE_TEMPERATURE]?.value as? Number)?.toDouble()

        val isDimmable = caps.containsKey(CAP_DIM)
        val hasColor = caps.containsKey(CAP_LIGHT_HUE)
        val hasColorTemp = caps.containsKey(CAP_LIGHT_TEMPERATURE)

        val dimVal = (caps[CAP_DIM]?.value as? Number)?.toDouble()
        val targetTemp = (caps[CAP_TARGET_TEMPERATURE]?.value as? Number)?.toDouble()
        val currentTemp = devTemp
        val hue = (caps[CAP_LIGHT_HUE]?.value as? Number)?.toDouble()
        val sat = (caps[CAP_LIGHT_SATURATION]?.value as? Number)?.toDouble()
        val colorTemp = (caps[CAP_LIGHT_TEMPERATURE]?.value as? Number)?.toDouble()
        val capMeta = _parseCapabilitiesMetadata(caps)

        val effectiveHubId = targetHubId ?: storage.getSelectedHomeyId()

        return HomeyDevice(
            id = device.id,
            name = device.name,
            zoneName = zoneName,
            isActive = isActive,
            isAvailable = device.available != false,
            primaryCapability = primaryCap,
            deviceClass = device.effectiveClass,
            iconUrl = if (!device.iconOverride.isNullOrBlank()) {
                "https://my.homey.app/img/devices/${device.iconOverride}.svg"
            } else if (!device.iconObj?.url.isNullOrBlank()) {
                val raw = device.iconObj.url
                if (raw.startsWith("http")) raw else {
                    val path = if (raw.startsWith("/")) raw else "/$raw"
                    "https://$effectiveHubId.connect.athom.com$path"
                }
            } else {
                null
            },
            isFavorite = favoritesList.contains(device.id),
            isLight = isLight,
            isHidden = device.hidden == true,
            isGroupMember = excludedIds.contains(device.id),
            zoneOrder = zoneOrder,
            zoneId = device.zone,
            isZoneActive = isZoneActive,
            temperature = devTemp,
            isThermostat = isThermostat,
            isDimmable = isDimmable,
            hasColor = hasColor,
            hasColorTemp = hasColorTemp,
            hasOnOff = hasOnOff,
            dimValue = dimVal,
            targetTemperature = targetTemp,
            currentTemperature = currentTemp,
            lightHue = hue,
            lightSaturation = sat,
            lightTemperature = colorTemp,
            rawCapabilities = rawCaps,
            quickActionCapability = resolvedQuickActionCap,
            capabilitiesMetadata = capMeta
        )
    }

    /**
     * Parses raw [CapabilityDto] map from Homey API into structured [CapabilityMeta] domain objects.
     */
    private fun _parseCapabilitiesMetadata(caps: Map<String, CapabilityDto>?): Map<String, CapabilityMeta> {
        if (caps.isNullOrEmpty()) return emptyMap()
        return caps.mapValues { (capId, capDto) ->
            val titleStr = when (val t = capDto.title) {
                is String -> t
                is Map<*, *> -> (t["it"] ?: t["en"] ?: t.values.firstOrNull())?.toString()
                else -> null
            }
            CapabilityMeta(
                id = capId,
                type = capDto.type,
                title = titleStr,
                setable = capDto.setable != false,
                uiComponent = capDto.uiComponent
            )
        }
    }

    private fun _computeGlobalZoneOrder(zones: Map<String, ZoneDto>): Map<String, Int> {
        val childrenMap = mutableMapOf<String?, MutableList<ZoneDto>>()
        zones.values.forEach { zone ->
            val list = childrenMap.getOrPut(zone.parent) { mutableListOf() }
            list.add(zone)
        }

        childrenMap.values.forEach { list ->
            list.sortWith(
                compareBy<ZoneDto, Int?>(nullsLast()) { it.sortIndex }
                    .thenBy { it.name }
            )
        }

        val flattenedOrder = mutableMapOf<String, Int>()
        var currentIndex = 0

        fun dfs(parentId: String?) {
            val children = childrenMap[parentId] ?: return
            for (child in children) {
                flattenedOrder[child.id] = currentIndex++
                dfs(child.id)
            }
        }

        dfs(null)
        return flattenedOrder
    }

    private fun _buildZonePathName(zoneId: String?, zones: Map<String, ZoneDto>): String {
        if (zoneId == null) return context.getString(R.string.repo_zone_unknown)
        
        val path = mutableListOf<String>()
        var current: ZoneDto? = zones[zoneId]
        
        while (current != null) {
            if (current.parent != null) {
                path.add(current.name)
            }
            current = current.parent?.let { zones[it] }
        }
        
        if (path.isEmpty()) {
             return zones[zoneId]?.name ?: context.getString(R.string.repo_zone_unknown)
        }
        
        return path.reversed().joinToString(" - ")
    }

    /**
     * Creates a predefined list of mock devices for Demo Mode / Google Play Store review.
     *
     * @private
     * @return List of mock [HomeyDevice] instances.
     */
    private fun _createDemoDevices(): List<HomeyDevice> {
        return listOf(
            HomeyDevice(
                id = "demo_light_1",
                name = context.getString(R.string.demo_device_living_light),
                zoneName = context.getString(R.string.demo_zone_ground_floor),
                isActive = true,
                isAvailable = true,
                primaryCapability = CAP_ONOFF,
                deviceClass = "light",
                iconUrl = null,
                isFavorite = true,
                isLight = true,
                isHidden = false,
                isGroupMember = false,
                zoneOrder = 1,
                zoneId = "demo_zone_ground_floor",
                isZoneActive = true,
                isDimmable = true,
                hasColor = true,
                hasColorTemp = true,
                dimValue = 0.8,
                lightHue = 0.58,
                lightSaturation = 0.85,
                lightTemperature = 0.5,
                quickActionCapability = CAP_ONOFF
            ),
            HomeyDevice(
                id = "demo_thermostat_1",
                name = context.getString(R.string.demo_device_living_thermostat),
                zoneName = context.getString(R.string.demo_zone_ground_floor),
                isActive = true,
                isAvailable = true,
                primaryCapability = CAP_TARGET_TEMPERATURE,
                deviceClass = "thermostat",
                iconUrl = null,
                isFavorite = true,
                isLight = false,
                isHidden = false,
                isGroupMember = false,
                zoneOrder = 1,
                zoneId = "demo_zone_ground_floor",
                isZoneActive = true,
                isThermostat = true,
                hasOnOff = true,
                targetTemperature = 20.5,
                currentTemperature = 21.0,
                quickActionCapability = null
            ),
            HomeyDevice(
                id = "demo_light_2",
                name = context.getString(R.string.demo_device_kitchen_light),
                zoneName = context.getString(R.string.demo_zone_ground_floor),
                isActive = false,
                isAvailable = true,
                primaryCapability = CAP_ONOFF,
                deviceClass = "light",
                iconUrl = null,
                isFavorite = true,
                isLight = true,
                isHidden = false,
                isGroupMember = false,
                zoneOrder = 1,
                zoneId = "demo_zone_ground_floor",
                isZoneActive = true,
                quickActionCapability = CAP_ONOFF
            ),
            HomeyDevice(
                id = "demo_light_3",
                name = context.getString(R.string.demo_device_garden_light),
                zoneName = context.getString(R.string.demo_zone_outside),
                isActive = false,
                isAvailable = true,
                primaryCapability = CAP_ONOFF,
                deviceClass = "light",
                iconUrl = null,
                isFavorite = false,
                isLight = true,
                isHidden = false,
                isGroupMember = false,
                zoneOrder = 2,
                zoneId = "demo_zone_outside",
                isZoneActive = false,
                quickActionCapability = CAP_ONOFF
            ),
            HomeyDevice(
                id = "demo_door_1",
                name = context.getString(R.string.demo_device_front_door),
                zoneName = context.getString(R.string.demo_zone_ground_floor),
                isActive = false, // false for lock = locked / closed
                isAvailable = true,
                primaryCapability = "locked",
                deviceClass = "lock",
                iconUrl = null,
                isFavorite = true,
                isLight = false,
                isHidden = false,
                isGroupMember = false,
                zoneOrder = 1,
                zoneId = "demo_zone_ground_floor",
                isZoneActive = true,
                quickActionCapability = "locked"
            ),
            HomeyDevice(
                id = "demo_garage_1",
                name = context.getString(R.string.demo_device_main_garage),
                zoneName = context.getString(R.string.demo_zone_outside),
                isActive = false, // false for garagedoor = closed
                isAvailable = true,
                primaryCapability = CAP_GARAGEDOOR,
                deviceClass = "garagedoor",
                iconUrl = null,
                isFavorite = true,
                isLight = false,
                isHidden = false,
                isGroupMember = false,
                zoneOrder = 2,
                zoneId = "demo_zone_outside",
                isZoneActive = false,
                quickActionCapability = CAP_GARAGEDOOR
            )
        )
    }

    /**
     * Creates a predefined list of mock devices for secondary Demo Mode hub (Beach House).
     *
     * Private method.
     *
     * @private
     * @return List of mock [HomeyDevice] instances for the secondary demo hub.
     * @example
     * val devices = _createDemoDevices2()
     */
    private fun _createDemoDevices2(): List<HomeyDevice> {
        return listOf(
            HomeyDevice(
                id = "demo2_light_1",
                name = context.getString(R.string.demo_device_patio_light),
                zoneName = context.getString(R.string.demo_zone_outside),
                isActive = false,
                isAvailable = true,
                primaryCapability = CAP_ONOFF,
                deviceClass = "light",
                iconUrl = null,
                isFavorite = true,
                isLight = true,
                isHidden = false,
                isGroupMember = false,
                zoneOrder = 1,
                zoneId = "demo_zone_outside",
                isZoneActive = false,
                isDimmable = true,
                hasColor = true,
                hasColorTemp = true,
                dimValue = 0.5,
                quickActionCapability = CAP_ONOFF
            ),
            HomeyDevice(
                id = "demo2_gate_1",
                name = context.getString(R.string.demo_device_gate),
                zoneName = context.getString(R.string.demo_zone_outside),
                isActive = false,
                isAvailable = true,
                primaryCapability = "locked",
                deviceClass = "lock",
                iconUrl = null,
                isFavorite = true,
                isLight = false,
                isHidden = false,
                isGroupMember = false,
                zoneOrder = 1,
                zoneId = "demo_zone_outside",
                isZoneActive = false,
                quickActionCapability = "locked"
            ),
            HomeyDevice(
                id = "demo2_light_2",
                name = context.getString(R.string.demo_device_garden_light),
                zoneName = context.getString(R.string.demo_zone_outside),
                isActive = true,
                isAvailable = true,
                primaryCapability = CAP_ONOFF,
                deviceClass = "light",
                iconUrl = null,
                isFavorite = true,
                isLight = true,
                isHidden = false,
                isGroupMember = false,
                zoneOrder = 1,
                zoneId = "demo_zone_outside",
                isZoneActive = true,
                quickActionCapability = CAP_ONOFF
            ),
            HomeyDevice(
                id = "demo2_garage_1",
                name = context.getString(R.string.demo_device_main_garage),
                zoneName = context.getString(R.string.demo_zone_outside),
                isActive = false,
                isAvailable = true,
                primaryCapability = CAP_GARAGEDOOR,
                deviceClass = "garagedoor",
                iconUrl = null,
                isFavorite = true,
                isLight = false,
                isHidden = false,
                isGroupMember = false,
                zoneOrder = 1,
                zoneId = "demo_zone_outside",
                isZoneActive = false,
                quickActionCapability = CAP_GARAGEDOOR
            )
        )
    }

    /**
     * Parses a JSON element into an epoch millisecond timestamp.
     *
     * @private
     * @param json JsonElement containing either a number (epoch ms) or an ISO-8601 date string.
     * @return Epoch millisecond timestamp, or null if parsing fails.
     */
    private fun _parseIsoOrEpochTimestamp(json: com.google.gson.JsonElement?): Long? {
        if (json == null || json.isJsonNull) return null
        return try {
            if (json.isJsonPrimitive) {
                val prim = json.asJsonPrimitive
                if (prim.isNumber) {
                    prim.asLong
                } else if (prim.isString) {
                    val str = prim.asString
                    try {
                        java.time.Instant.parse(str).toEpochMilli()
                    } catch (e: Exception) {
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                        sdf.parse(str)?.time
                    }
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses a JSON element from an insight log entry into a Boolean state.
     *
     * @private
     * @param json JsonElement representing a boolean, numeric 0/1, or string truth value.
     * @return Boolean representation, or null if invalid.
     */
    private fun _parseInsightValueToBoolean(json: com.google.gson.JsonElement?): Boolean? {
        if (json == null || json.isJsonNull) return null
        return try {
            if (json.isJsonPrimitive) {
                val prim = json.asJsonPrimitive
                if (prim.isBoolean) {
                    prim.asBoolean
                } else if (prim.isNumber) {
                    prim.asDouble > 0.0
                } else if (prim.isString) {
                    val str = prim.asString
                    str.equals("true", ignoreCase = true) || str == "1"
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
