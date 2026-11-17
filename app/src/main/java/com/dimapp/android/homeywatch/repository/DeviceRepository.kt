package com.dimapp.android.homeywatch.repository

import android.content.Context
import com.dimapp.android.homeywatch.R
import com.dimapp.android.homeywatch.api.HomeyApiService
import com.dimapp.android.homeywatch.api.SanitizedHttpLogger
import com.dimapp.android.homeywatch.api.models.DeviceDto
import com.dimapp.android.homeywatch.api.models.SetCapabilityBody
import com.dimapp.android.homeywatch.api.models.UserMeDto
import com.dimapp.android.homeywatch.api.models.ZoneDto
import retrofit2.Response
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
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.dimapp.android.homeywatch.auth.HomeyAuthRepository
import com.dimapp.android.homeywatch.storage.TokenStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.util.Log

/**
 * Repository handling device-related operations (fetching, toggling, syncing).
 */
class DeviceRepository(
    context: Context,
    storage: TokenStorage,
    authRepo: HomeyAuthRepository
) : HomeyBaseRepository(context, storage, authRepo) {
 
    private val _TAG = "DeviceRepository"
    private val _cacheMutex = Mutex()
    private val _insightLogsMutex = Mutex()
    private var _cachedDevices: List<HomeyDevice>? = null
    private var _cachedHubId: String? = null
    private var _cachedZoneTemperatures: Map<String, Double> = emptyMap()
    private var _cachedZoneIdToPath: Map<String, String> = emptyMap()
    private var _cachedInsightLogs: List<InsightLogDto>? = null
    private var _cachedInsightLogsHubId: String? = null
    private var _cachedInsightLogsTimestampMs: Long = 0L
    private var _cachedInsightLogsServerDateMs: Long? = null
    private var _demoDevices: MutableList<HomeyDevice>? = null
    private var _demoDevices2: MutableList<HomeyDevice>? = null
    private val _cachedHubDevices = mutableMapOf<String, List<HomeyDevice>>()

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
     * @example
     * ```kotlin
     * val lastSync = repo.getLastSyncTimestamp()
     * ```
     */
    fun getLastSyncTimestamp(): Long = _lastSyncTimestamp

    /**
     * Returns the latest computed average temperatures mapped by zone name.
     *
     * @public
     * @return Map of zone name to average temperature in degrees Celsius.
     * @example
     * ```kotlin
     * val temps = repo.getZoneTemperatures()
     * ```
     */
    fun getZoneTemperatures(): Map<String, Double> {
        return _cachedZoneTemperatures
    }


    /**
     * Returns all supported devices, using an in-memory cache for structure to improve performance.
     *
     * The cache is automatically invalidated if the active hub ID changes.
     * To force a network refresh, set [forceRefresh] to true.
     *
     * @public
     * @param forceRefresh If `true`, bypasses the in-memory cache and reloads from network.
     * @return [HomeyResult.Success] containing the sorted list of [HomeyDevice] items, or [HomeyResult.Error].
     * @example
     * ```kotlin
     * val result = repo.getDevices(forceRefresh = true)
     * ```
     */
    suspend fun getDevices(forceRefresh: Boolean = false): HomeyResult<List<HomeyDevice>> = withContext(Dispatchers.IO) {
        _cacheMutex.withLock {
            // Handle Demo Mode directly without network interaction
            if (storage.isDemoMode()) {
                _cachedZoneTemperatures = mapOf(
                    context.getString(R.string.demo_zone_ground_floor) to 21.5,
                    context.getString(R.string.demo_zone_outside) to 18.0
                )
                if (_demoDevices == null || forceRefresh) {
                    _demoDevices = _createDemoDevices().toMutableList()
                    Log.d(_TAG, "[DeviceRepository:getDevices] Initialized demo devices list with ${_demoDevices?.size} mock devices.")
                }
                _devicesFlow.value = _demoDevices!!.toList()
                _lastSyncTimestamp = System.currentTimeMillis()
                return@withLock HomeyResult.Success(_demoDevices!!.toList())
            }

            val currentHubId = storage.getSelectedHomeyId() ?: return@withLock HomeyResult.Error(context.getString(R.string.repo_error_not_configured))
            
            // 1. Check if we have valid cached data for the current hub
            // If forceRefresh is true, we skip the cache and go straight to network.
            if (!forceRefresh && _cachedHubId == currentHubId && _cachedDevices != null) {
                Log.d(_TAG, "[DeviceRepository:getDevices] Returning ${_cachedDevices?.size} devices from in-memory cache.")
                _devicesFlow.value = _cachedDevices!!
                return@withLock HomeyResult.Success(_cachedDevices!!)
            }

            // 2. Fetch from network if cache is missing, stale, or forced
            val reason = when {
                forceRefresh -> "Forced refresh"
                _cachedHubId != currentHubId -> "Hub changed (${SanitizedHttpLogger.maskId(_cachedHubId)} -> ${SanitizedHttpLogger.maskId(currentHubId)})"
                else -> "Cache empty"
            }
            Log.d(_TAG, "[DeviceRepository:getDevices] $reason. Fetching from network...")

            val service = _buildService()
            if (service == null) {
                Log.e(_TAG, "[DeviceRepository:getDevices] Cannot fetch devices: HomeyApiService is null (check if Hub is configured).")
                return@withLock HomeyResult.Error(context.getString(R.string.repo_error_not_configured))
            }

            try {
                val bundle = _fetchDevicesBundle(service)
                val devicesResponse = bundle.devicesResponse
                val zonesResponse = bundle.zonesResponse
                val userResponse = bundle.userResponse

                if (!devicesResponse.isSuccessful) {
                    val errorBody = devicesResponse.errorBody()?.string()
                    Log.e(_TAG, "[DeviceRepository:getDevices] Devices fetch failed: HTTP ${devicesResponse.code()} - $errorBody")
                    if (devicesResponse.code() == 401 && !currentHubId.isNullOrBlank()) {
                        storage.setReauthRequired(currentHubId, true, "EXPIRED")
                    }
                    return@withLock HomeyResult.Error(context.getString(R.string.repo_error_devices_http, devicesResponse.code()))
                }
                if (!zonesResponse.isSuccessful) {
                    val errorBody = zonesResponse.errorBody()?.string()
                    Log.e(_TAG, "[DeviceRepository:getDevices] Zones fetch failed: HTTP ${zonesResponse.code()} - $errorBody")
                    if (zonesResponse.code() == 401 && !currentHubId.isNullOrBlank()) {
                        storage.setReauthRequired(currentHubId, true, "EXPIRED")
                    }
                    return@withLock HomeyResult.Error(context.getString(R.string.repo_error_zones_http, zonesResponse.code()))
                }

                val devices = devicesResponse.body() ?: emptyMap()
                val zones = zonesResponse.body() ?: emptyMap()
                val favoritesList = if (userResponse?.isSuccessful == true) {
                    userResponse.body()?.properties?.favoriteDevices ?: emptyList()
                } else {
                    emptyList()
                }

                val zoneIdToPath = zones.keys.associateWith { zId -> _buildZonePathName(zId, zones) }
                _cachedZoneIdToPath = zoneIdToPath
                _cachedZoneTemperatures = _computeZoneTemperatures(devices, zoneIdToPath)

                val excludedIds = devices.values
                    .flatMap { it.settings?.deviceIds ?: emptyList() }
                    .toSet()

                val globalZoneOrder = _computeGlobalZoneOrder(zones)

                val mappedDevices = devices.values
                    .filter { device -> _isSupportedDevice(device) }
                    .map { device -> _toHomeyDevice(device, zones, favoritesList, excludedIds, globalZoneOrder) }
                    .sortedBy { it.name }

                // 3. Update cache
                _cachedDevices = mappedDevices
                _cachedHubId = currentHubId
                _devicesFlow.value = mappedDevices
                _lastSyncTimestamp = System.currentTimeMillis()

                Log.d(_TAG, "[DeviceRepository:getDevices] Fetched ${mappedDevices.size} devices from network and saved to cache.")
                HomeyResult.Success(mappedDevices)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(_TAG, "[DeviceRepository:getDevices] Exception: ${e.message}", e)
                HomeyResult.Error(context.getString(R.string.repo_error_conn_failed, e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Clears the in-memory device cache, forcing the next [getDevices] call to fetch from network.
     * Use this for manual refresh flows or after significant configuration changes.
     *
     * @public
     * @example
     * ```kotlin
     * repo.invalidateCache()
     * ```
     */
    fun invalidateCache() {
        Log.d(_TAG, "[DeviceRepository:invalidateCache] Device cache invalidated.")
        _cachedDevices = null
        _cachedHubId = null
        _cachedZoneTemperatures = emptyMap()
        _cachedZoneIdToPath = emptyMap()
        _demoDevices = null
        _demoDevices2 = null
        _cachedHubDevices.clear()
        _cachedInsightLogs = null
        _cachedInsightLogsHubId = null
        _cachedInsightLogsTimestampMs = 0L
        _cachedInsightLogsServerDateMs = null
        _devicesFlow.value = emptyList()
        _hubDevicesFlow.value = emptyMap()
        _lastSyncTimestamp = 0L
    }

    /**
     * Checks if the in-memory cache is currently empty.
     *
     * @public
     * @return `true` if no devices are cached in memory.
     * @example
     * ```kotlin
     * if (repo.isCacheEmpty()) { /* fetch devices */ }
     * ```
     */
    fun isCacheEmpty(): Boolean {
        return _cachedDevices == null
    }

    /**
     * Returns devices for a specific hub (or current active hub if [hubId] is null or matches active hub).
     *
     * @public
     * @param hubId Target Homey ID or "demo".
     * @param forceRefresh Whether to force a network reload.
     * @return [HomeyResult] containing devices.
     * @example
     * ```kotlin
     * val devices = repo.getDevicesForHub(hubId = "hub-id", forceRefresh = false)
     * ```
     */
    suspend fun getDevicesForHub(
        hubId: String?,
        forceRefresh: Boolean = false
    ): HomeyResult<List<HomeyDevice>> = withContext(Dispatchers.IO) {
        if (hubId.isNullOrBlank() || hubId == storage.getSelectedHomeyId()) {
            return@withContext getDevices(forceRefresh)
        }
        if (hubId == "demo") {
            return@withContext _cacheMutex.withLock {
                if (_demoDevices == null || forceRefresh) {
                    _demoDevices = _createDemoDevices().toMutableList()
                }
                HomeyResult.Success(_demoDevices!!.toList())
            }
        }
        if (hubId == "demo_2") {
            return@withContext _cacheMutex.withLock {
                if (_demoDevices2 == null || forceRefresh) {
                    _demoDevices2 = _createDemoDevices2().toMutableList()
                }
                HomeyResult.Success(_demoDevices2!!.toList())
            }
        }

        _cacheMutex.withLock {
            if (!forceRefresh && _cachedHubDevices.containsKey(hubId)) {
                return@withLock HomeyResult.Success(_cachedHubDevices[hubId]!!)
            }

            val service = _buildServiceForHub(hubId)
                ?: return@withLock HomeyResult.Error(context.getString(R.string.repo_error_not_configured))

            try {
                val bundle = _fetchDevicesBundle(service)
                val devicesResponse = bundle.devicesResponse
                val zonesResponse = bundle.zonesResponse
                val userResponse = bundle.userResponse

                if (!devicesResponse.isSuccessful) {
                    if (devicesResponse.code() == 401) {
                        storage.setReauthRequired(hubId, true, "EXPIRED")
                    }
                    return@withLock HomeyResult.Error(context.getString(R.string.repo_error_devices_http, devicesResponse.code()))
                }
                val devices = devicesResponse.body() ?: emptyMap()
                val zones = if (zonesResponse.isSuccessful) zonesResponse.body() ?: emptyMap() else emptyMap()
                val favoritesList = if (userResponse?.isSuccessful == true) {
                    userResponse.body()?.properties?.favoriteDevices ?: emptyList()
                } else {
                    emptyList()
                }

                val excludedIds = devices.values
                    .flatMap { it.settings?.deviceIds ?: emptyList() }
                    .toSet()

                val globalZoneOrder = _computeGlobalZoneOrder(zones)
                val mappedDevices = devices.values
                    .filter { device -> _isSupportedDevice(device) }
                    .map { device -> _toHomeyDevice(device, zones, favoritesList, excludedIds, globalZoneOrder, targetHubId = hubId) }
                    .sortedBy { it.name }

                _cachedHubDevices[hubId] = mappedDevices
                _hubDevicesFlow.value = _cachedHubDevices.toMap()
                if (hubId == storage.getSelectedHomeyId()) {
                    _cachedDevices = mappedDevices
                    _devicesFlow.value = mappedDevices
                }
                HomeyResult.Success(mappedDevices)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HomeyResult.Error(context.getString(R.string.repo_error_conn_failed, e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Toggles a device (door/light) by setting its primary capability.
     * Optionally routes to a specific [hubId] if provided.
     *
     * @public
     * @param deviceId UUID of the target device.
     * @param capabilityId Capability ID to update (e.g. `"onoff"`, `"locked"`, `"garagedoor_closed"`).
     * @param makeActive Desired semantic active state (`true` = ON / unlocked / open).
     * @param hubId Optional Homey Hub ID for multi-hub routing.
     * @return [HomeyResult.Success] with localized confirmation message, or [HomeyResult.Error].
     * @example
     * ```kotlin
     * val res = repo.toggleDeviceState("dev-uuid", "onoff", makeActive = true)
     * ```
     */
    suspend fun toggleDeviceState(
        deviceId: String,
        capabilityId: String,
        makeActive: Boolean,
        hubId: String? = null
    ): HomeyResult<String> = withContext(Dispatchers.IO) {
        val maskedDeviceId = SanitizedHttpLogger.maskId(deviceId)
        // Handle Demo Mode optimistic state toggle
        if (storage.isDemoMode() || hubId?.startsWith("demo") == true) {
            return@withContext _cacheMutex.withLock {
                val isSecondDemo = hubId == "demo_2"
                val list = if (isSecondDemo) {
                    _demoDevices2 ?: _createDemoDevices2().toMutableList()
                } else {
                    _demoDevices ?: _createDemoDevices().toMutableList()
                }
                val index = list.indexOfFirst { it.id == deviceId }
                if (index != -1) {
                    val isBtn = isButtonOrActionCapability(capabilityId)
                    val newActive = if (isBtn) false else makeActive
                    list[index] = list[index].copy(isActive = newActive)
                    if (isSecondDemo) {
                        _demoDevices2 = list
                    } else {
                        _demoDevices = list
                        _devicesFlow.value = list.toList()
                    }
                    Log.d(_TAG, "[DeviceRepository:toggleDeviceState] Toggled demo device $maskedDeviceId to isActive=$newActive on hub=${SanitizedHttpLogger.maskId(hubId)}")
                }
                HomeyResult.Success(context.getString(R.string.repo_success_toggle))
            }
        }

        val service = _buildServiceForHub(hubId)
            ?: return@withContext HomeyResult.Error(context.getString(R.string.repo_error_not_configured))

        val isButtonOrAction = isButtonOrActionCapability(capabilityId)
        val value: Any = when {
            isButtonOrAction         -> true
            capabilityId == CAP_GARAGEDOOR           -> !makeActive
            capabilityId == "locked"                 -> !makeActive
            capabilityId == "windowcoverings_closed" -> !makeActive
            else                                     -> makeActive
        }

        try {
            val response = service.setCapability(
                deviceId = deviceId,
                capabilityId = capabilityId,
                body = SetCapabilityBody(value = value)
            )

            if (response.isSuccessful) {
                val nextActive = if (isButtonOrAction) false else makeActive
                _cacheMutex.withLock {
                    _cachedDevices = _cachedDevices?.map { dev ->
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
                    _cachedDevices?.let { _devicesFlow.value = it }

                    if (!hubId.isNullOrBlank() && _cachedHubDevices.containsKey(hubId)) {
                        _cachedHubDevices[hubId] = _cachedHubDevices[hubId]!!.map { dev ->
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
                        _hubDevicesFlow.value = _cachedHubDevices.toMap()
                    }
                }
                Log.d(_TAG, "[DeviceRepository:toggleDeviceState] Successfully toggled $maskedDeviceId ($capabilityId) to $value")
                HomeyResult.Success(context.getString(R.string.repo_success_toggle))
            } else {
                HomeyResult.Error(context.getString(R.string.repo_error_toggle_http, response.code()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HomeyResult.Error(context.getString(R.string.repo_error_network, e.message ?: "Unknown error"))
        }
    }

    /**
     * Reads the current state of a single device on Homey and immediately toggles the target
     * capability to its opposite state. Designed for stateless Wear OS Tiles so switches invert
     * the actual hardware state on the first press without background polling.
     *
     * @public
     * @param deviceId UUID of the device to toggle.
     * @param capabilityId Target capability ID (e.g. "onoff", "locked", "garagedoor_closed").
     * @param fallbackIsActive Fallback active flag used when the live pre-read fails or times out.
     * @param hubId Optional target Homey hub ID for multi-hub routing.
     * @return [HomeyResult.Success] containing the resulting active state after actuation, or [HomeyResult.Error].
     * @example
     * val result = deviceRepo.smartToggleDevice("dev-uuid", "onoff", false, "hub-id")
     */
    suspend fun smartToggleDevice(
        deviceId: String,
        capabilityId: String,
        fallbackIsActive: Boolean,
        hubId: String? = null
    ): HomeyResult<Boolean> = withContext(Dispatchers.IO) {
        val maskedDeviceId = SanitizedHttpLogger.maskId(deviceId)
        if (isButtonOrActionCapability(capabilityId)) {
            return@withContext when (val res = toggleDeviceState(deviceId, capabilityId, makeActive = true, hubId = hubId)) {
                is HomeyResult.Success -> HomeyResult.Success(false)
                is HomeyResult.Error -> HomeyResult.Error(res.message)
            }
        }

        if (storage.isDemoMode() || hubId?.startsWith("demo") == true) {
            val currentActive = _cacheMutex.withLock {
                val isSecondDemo = hubId == "demo_2"
                val list = if (isSecondDemo) {
                    _demoDevices2 ?: _createDemoDevices2().toMutableList().also { _demoDevices2 = it }
                } else {
                    _demoDevices ?: _createDemoDevices().toMutableList().also { _demoDevices = it }
                }
                list.find { it.id == deviceId }?.let { dev ->
                    val rawVal = dev.rawCapabilities[capabilityId]
                    if (rawVal != null) isCapabilityActive(capabilityId, rawVal) else dev.isActive
                } ?: fallbackIsActive
            }
            val targetActive = !currentActive
            return@withContext when (val res = toggleDeviceState(deviceId, capabilityId, makeActive = targetActive, hubId = hubId)) {
                is HomeyResult.Success -> HomeyResult.Success(targetActive)
                is HomeyResult.Error -> HomeyResult.Error(res.message)
            }
        }

        val service = _buildServiceForHub(hubId)
            ?: return@withContext HomeyResult.Error(context.getString(R.string.repo_error_not_configured))

        var currentActive = fallbackIsActive
        try {
            val preReadResponse = withTimeoutOrNull(2500L) {
                service.getDevice(deviceId)
            }
            if (preReadResponse != null && preReadResponse.isSuccessful) {
                val caps = preReadResponse.body()?.capabilitiesObj
                if (caps != null && caps.containsKey(capabilityId)) {
                    currentActive = isCapabilityActive(capabilityId, caps[capabilityId]?.value)
                    Log.d(_TAG, "[DeviceRepository:smartToggleDevice] Live pre-read for $maskedDeviceId ($capabilityId): currentActive=$currentActive")
                } else {
                    Log.w(_TAG, "[DeviceRepository:smartToggleDevice] Capability $capabilityId absent for $maskedDeviceId; using fallback=$fallbackIsActive")
                }
            } else {
                Log.w(_TAG, "[DeviceRepository:smartToggleDevice] Live pre-read failed or timed out for $maskedDeviceId (code=${preReadResponse?.code()}); using fallback=$fallbackIsActive")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(_TAG, "[DeviceRepository:smartToggleDevice] Live pre-read error for $maskedDeviceId: ${e.message}; using fallback=$fallbackIsActive")
        }

        val targetActive = !currentActive
        when (val toggleRes = toggleDeviceState(deviceId, capabilityId, makeActive = targetActive, hubId = hubId)) {
            is HomeyResult.Success -> HomeyResult.Success(targetActive)
            is HomeyResult.Error -> HomeyResult.Error(toggleRes.message)
        }
    }

    /**
     * Sets an arbitrary capability value (e.g. dim, target_temperature, light_hue) on a device.
     *
     * @public
     * @param deviceId Device UUID.
     * @param capabilityId Capability ID to update.
     * @param value The value to apply (Number, Boolean, or String).
     * @return [HomeyResult] indicating success or failure.
     * @example
     * ```kotlin
     * val res = repo.setDeviceCapabilityValue("dev-uuid", "dim", 0.5)
     * ```
     */
    suspend fun setDeviceCapabilityValue(
        deviceId: String,
        capabilityId: String,
        value: Any
    ): HomeyResult<String> = withContext(Dispatchers.IO) {
        val maskedDeviceId = SanitizedHttpLogger.maskId(deviceId)
        // Handle Demo Mode state mutation
        if (storage.isDemoMode()) {
            val list = _demoDevices ?: _createDemoDevices().toMutableList()
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
                _demoDevices = list
                _devicesFlow.value = list.toList()
                Log.d(_TAG, "[DeviceRepository:setDeviceCapabilityValue] Updated demo device $maskedDeviceId capability $capabilityId to $value")
            }
            return@withContext HomeyResult.Success(context.getString(R.string.repo_success_toggle))
        }

        val service = _buildService() ?: return@withContext HomeyResult.Error(context.getString(R.string.repo_error_not_configured))

        try {
            val response = service.setCapability(
                deviceId = deviceId,
                capabilityId = capabilityId,
                body = SetCapabilityBody(value = value)
            )

            if (response.isSuccessful) {
                _cacheMutex.withLock {
                    _cachedDevices = _cachedDevices?.map { dev ->
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
                    _cachedDevices?.let { _devicesFlow.value = it }
                }
                HomeyResult.Success(context.getString(R.string.repo_success_toggle))
            } else {
                HomeyResult.Error(context.getString(R.string.repo_error_toggle_http, response.code()))
            }
        } catch (e: CancellationException) {
            throw e
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
    ): HomeyResult<List<HomeyDevice>> = withContext(Dispatchers.IO) {
        if (currentDevices.isEmpty()) return@withContext HomeyResult.Success(emptyList())

        if (storage.isDemoMode() || hubId?.startsWith("demo") == true) {
            val isSecondDemo = hubId == "demo_2"
            return@withContext _cacheMutex.withLock {
                val list = if (isSecondDemo) {
                    _demoDevices2 ?: currentDevices
                } else {
                    _demoDevices ?: currentDevices
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

        val service = _buildServiceForHub(targetHubId)
            ?: return@withContext HomeyResult.Error(context.getString(R.string.repo_error_not_configured))

        try {
            val response = service.getDevices()
            if (!response.isSuccessful) {
                Log.e(_TAG, "[DeviceRepository:syncDeviceStates] Batch sync failed: HTTP ${response.code()} for hub=${SanitizedHttpLogger.maskId(targetHubId)}")
                return@withContext HomeyResult.Error(context.getString(R.string.repo_error_devices_http, response.code()))
            }

            val remoteDevices = response.body() ?: emptyMap()
            val updatedDevices = currentDevices.map { device ->
                val remoteDto = remoteDevices[device.id]
                if (remoteDto != null) {
                    _mergeLiveDeviceDto(device, remoteDto, targetHubId)
                } else {
                    device
                }
            }

            _cacheMutex.withLock {
                val updatedMap = updatedDevices.associateBy { it.id }
                if (isDifferentHub) {
                    val updatedHub = _cachedHubDevices[targetHubId!!]?.map { dev ->
                        updatedMap[dev.id] ?: dev
                    } ?: updatedDevices
                    _cachedHubDevices[targetHubId!!] = updatedHub
                    _hubDevicesFlow.value = _cachedHubDevices.toMap()
                } else {
                    if (_cachedZoneIdToPath.isNotEmpty()) {
                        _cachedZoneTemperatures = _computeZoneTemperatures(remoteDevices, _cachedZoneIdToPath)
                    }
                    val updatedMain = _cachedDevices?.map { dev ->
                        updatedMap[dev.id] ?: dev
                    } ?: updatedDevices
                    _cachedDevices = updatedMain
                    _devicesFlow.value = updatedMain
                }
            }

            _lastSyncTimestamp = System.currentTimeMillis()
            Log.d(_TAG, "[DeviceRepository:syncDeviceStates] Successfully synced ${updatedDevices.size} devices via single batch call for hub=${SanitizedHttpLogger.maskId(targetHubId)}.")
            HomeyResult.Success(updatedDevices)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(_TAG, "[DeviceRepository:syncDeviceStates] Batch sync exception: ${e.message}")
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
     * ```kotlin
     * val result = repo.refreshSingleDevice("device-id")
     * ```
     */
    suspend fun refreshSingleDevice(deviceId: String): HomeyResult<HomeyDevice> = withContext(Dispatchers.IO) {
        val maskedDeviceId = SanitizedHttpLogger.maskId(deviceId)
        if (storage.isDemoMode()) {
            val dev = (_demoDevices ?: _cachedDevices)?.find { it.id == deviceId }
            return@withContext if (dev != null) HomeyResult.Success(dev) else HomeyResult.Error("Device not found in demo mode")
        }

        val service = _buildService() ?: return@withContext HomeyResult.Error(context.getString(R.string.repo_error_not_configured))

        try {
            val response = service.getDevice(deviceId)
            if (!response.isSuccessful) {
                Log.e(_TAG, "[DeviceRepository:refreshSingleDevice] Failed: HTTP ${response.code()} for device=$maskedDeviceId")
                return@withContext HomeyResult.Error(context.getString(R.string.repo_error_devices_http, response.code()))
            }

            val remoteDto = response.body()
                ?: return@withContext HomeyResult.Error(context.getString(R.string.repo_error_devices_http, response.code()))

            var updatedDevice: HomeyDevice? = null
            val activeHubId = storage.getSelectedHomeyId()
            _cacheMutex.withLock {
                val currentList = _cachedDevices ?: emptyList()
                val existing = currentList.find { it.id == deviceId }
                if (existing != null) {
                    val updated = _mergeLiveDeviceDto(existing, remoteDto, activeHubId)
                    updatedDevice = updated
                    val newList = currentList.map { if (it.id == deviceId) updated else it }
                    _cachedDevices = newList
                    _devicesFlow.value = newList
                }
            }

            if (updatedDevice != null) {
                Log.d(_TAG, "[DeviceRepository:refreshSingleDevice] Successfully refreshed state for $maskedDeviceId")
                HomeyResult.Success(updatedDevice!!)
            } else {
                HomeyResult.Error("Device $maskedDeviceId not found in cache")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(_TAG, "[DeviceRepository:refreshSingleDevice] Exception: ${e.message}", e)
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
    suspend fun getDeviceStateHistory(deviceId: String): HomeyResult<List<DeviceHistoryEvent>> = withContext(Dispatchers.IO) {
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
            return@withContext HomeyResult.Success(demoEvents)
        }

        val maskedDeviceId = SanitizedHttpLogger.maskId(deviceId)
        val service = _buildService() ?: return@withContext HomeyResult.Error(context.getString(R.string.repo_error_not_configured))

        try {
            val logsResult = _getInsightLogsForActiveHub(service)
            val (logList, logsServerDateMs) = when (logsResult) {
                is HomeyResult.Success -> logsResult.data
                is HomeyResult.Error -> return@withContext HomeyResult.Error(logsResult.message)
            }

            val cachedDevice = _cacheMutex.withLock {
                _cachedDevices?.firstOrNull { it.id == deviceId }
                    ?: _cachedHubDevices.values.asSequence().flatten().firstOrNull { it.id == deviceId }
            }

            val deviceTargetUri = "homey:device:$deviceId"
            val deviceLogs = logList.filter { log ->
                log.ownerUri?.equals(deviceTargetUri, ignoreCase = true) == true ||
                    log.ownerUri?.contains(deviceId, ignoreCase = true) == true ||
                    log.ownerId?.equals(deviceId, ignoreCase = true) == true ||
                    log.uri?.contains(deviceId, ignoreCase = true) == true ||
                    log.id?.contains(deviceId, ignoreCase = true) == true
            }
            Log.d(_TAG, "[DeviceRepository:getDeviceStateHistory] Found ${deviceLogs.size} logs for device $maskedDeviceId: ${deviceLogs.map { it.id?.substringAfterLast(":") }}")

            val targetLog = _selectPrimaryHistoryLog(deviceLogs, cachedDevice)

            if (targetLog == null) {
                Log.d(_TAG, "[DeviceRepository:getDeviceStateHistory] No matching insights log found for device $maskedDeviceId")
                return@withContext HomeyResult.Success(emptyList())
            }

            val ownerUri = targetLog.ownerUri?.takeIf { it.isNotBlank() }
                ?: targetLog.uri?.takeIf { it.isNotBlank() }
                ?: deviceTargetUri
            val rawId = targetLog.id?.takeIf { it.isNotBlank() } ?: CAP_ONOFF
            val shortId = rawId.substringAfterLast(":")

            Log.d(
                _TAG,
                "[DeviceRepository:getDeviceStateHistory] Target log selected. device=$maskedDeviceId, shortId=$shortId, type=${targetLog.type}, hasTitleTrue=${!targetLog.titleTrue.isNullOrBlank()}, hasTitleFalse=${!targetLog.titleFalse.isNullOrBlank()}"
            )

            // Attempt 1: Using ownerUri and rawId
            var entriesResponse = service.getInsightEntries(uri = ownerUri, logId = rawId, resolution = "last7Days")
            if (!entriesResponse.isSuccessful && shortId != rawId) {
                Log.d(_TAG, "[DeviceRepository:getDeviceStateHistory] First attempt HTTP ${entriesResponse.code()}. Retrying with shortId=$shortId")
                entriesResponse = service.getInsightEntries(uri = ownerUri, logId = shortId, resolution = "last7Days")
            }
            if (!entriesResponse.isSuccessful) {
                Log.d(_TAG, "[DeviceRepository:getDeviceStateHistory] Retrying with device=$maskedDeviceId and logId=$shortId")
                entriesResponse = service.getInsightEntries(uri = deviceId, logId = shortId, resolution = "last7Days")
            }

            if (!entriesResponse.isSuccessful) {
                Log.e(_TAG, "[DeviceRepository:getDeviceStateHistory] Failed to get insight entries: HTTP ${entriesResponse.code()} - ${entriesResponse.errorBody()?.string()}")
                return@withContext HomeyResult.Error(context.getString(R.string.recap_error_history))
            }

            val serverDateMs = entriesResponse.headers().getDate("Date")?.time ?: logsServerDateMs
            val serverClockOffsetMs = if (serverDateMs != null) {
                serverDateMs - System.currentTimeMillis()
            } else {
                0L
            }

            val rootJson = entriesResponse.body()
            val pointsJson = _extractInsightPointsJson(rootJson)
            Log.d(_TAG, "[DeviceRepository:getDeviceStateHistory] Insight entries parsed: ${pointsJson?.size() ?: 0} raw points for $maskedDeviceId")

            if (pointsJson == null || pointsJson.size() == 0) {
                Log.d(_TAG, "[DeviceRepository:getDeviceStateHistory] No historical data points found for $maskedDeviceId")
                return@withContext HomeyResult.Success(emptyList())
            }

            val events = mutableListOf<DeviceHistoryEvent>()
            val baseCap = shortId.substringBefore(".")
            val isNumericDimLog = targetLog.type.equals("number", ignoreCase = true) &&
                baseCap.equals(CAP_DIM, ignoreCase = true)

            if (isNumericDimLog) {
                val parsedPoints = pointsJson.mapNotNull { elem ->
                    if (!elem.isJsonObject) return@mapNotNull null
                    val obj = elem.asJsonObject
                    val ts = _parseIsoOrEpochTimestamp(obj.get("t")) ?: return@mapNotNull null
                    val numVal = _parseInsightValueToDouble(obj.get("v")) ?: return@mapNotNull null
                    ts to kotlin.math.round(numVal * 100.0).toInt().coerceIn(0, 100)
                }.sortedBy { it.first }

                var previousPct: Int? = null
                for ((ts, pct) in parsedPoints) {
                    if (previousPct == null || previousPct != pct) {
                        val stateText = if (pct == 0) {
                            context.getString(R.string.device_history_turned_off)
                        } else {
                            context.getString(R.string.device_history_dimmed, pct)
                        }
                        events.add(
                            DeviceHistoryEvent(
                                timestamp = ts,
                                isOn = pct > 0,
                                stateText = stateText,
                                serverClockOffsetMs = serverClockOffsetMs
                            )
                        )
                        previousPct = pct
                    }
                }
            } else {
                val parsedPoints = pointsJson.mapNotNull { elem ->
                    if (!elem.isJsonObject) return@mapNotNull null
                    val obj = elem.asJsonObject
                    val ts = _parseIsoOrEpochTimestamp(obj.get("t")) ?: return@mapNotNull null
                    val boolVal = _parseInsightValueToBoolean(obj.get("v")) ?: return@mapNotNull null
                    ts to boolVal
                }.sortedBy { it.first }

                var previousState: Boolean? = null
                for ((ts, boolVal) in parsedPoints) {
                    if (previousState == null || previousState != boolVal) {
                        val text = _resolveBooleanHistoryStateText(
                            shortCapId = shortId,
                            boolVal = boolVal,
                            targetLog = targetLog,
                            cachedDevice = cachedDevice
                        )
                        val isActiveState = isCapabilityActive(shortId, boolVal)
                        events.add(
                            DeviceHistoryEvent(
                                timestamp = ts,
                                isOn = isActiveState,
                                stateText = text,
                                serverClockOffsetMs = serverClockOffsetMs
                            )
                        )
                        previousState = boolVal
                    }
                }
            }

            val sorted = events.sortedByDescending { it.timestamp }
            Log.d(_TAG, "[DeviceRepository:getDeviceStateHistory] Found ${sorted.size} historical state transitions for $maskedDeviceId")
            HomeyResult.Success(sorted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(_TAG, "[DeviceRepository:getDeviceStateHistory] Exception: ${e.message}", e)
            HomeyResult.Error(context.getString(R.string.repo_error_network, e.message ?: "Unknown error"))
        }
    }

    // ── Private Methods ───────────────────────────────────────────────────────

    private data class _FetchedDevicesBundle(
        val devicesResponse: Response<Map<String, DeviceDto>>,
        val zonesResponse: Response<Map<String, ZoneDto>>,
        val userResponse: Response<UserMeDto>?
    )

    /**
     * Fetches devices, zones, and user profile concurrently to minimize cold-start latency.
     *
     * @private
     * @param service Configured [HomeyApiService] pointing at the target hub.
     * @return [_FetchedDevicesBundle] containing all three parallel responses.
     * @example
     * val bundle = _fetchDevicesBundle(service)
     */
    private suspend fun _fetchDevicesBundle(service: HomeyApiService): _FetchedDevicesBundle {
        return coroutineScope {
            val devicesDeferred = async { service.getDevices() }
            val zonesDeferred = async { service.getZones() }
            val userDeferred = async {
                try {
                    service.getUserMe()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(_TAG, "[DeviceRepository:_fetchDevicesBundle] Optional getUserMe failed: ${e.message}")
                    null
                }
            }
            _FetchedDevicesBundle(
                devicesResponse = devicesDeferred.await(),
                zonesResponse = zonesDeferred.await(),
                userResponse = userDeferred.await()
            )
        }
    }

    /**
     * Computes average room temperatures mapped by full zone path name from raw device DTOs.
     *
     * @private
     * @param devices Full map of remote [DeviceDto] instances keyed by device UUID.
     * @param zoneIdToPath Pre-resolved map of zone ID to display zone path name.
     * @return Map of zone path name to average `measure_temperature` reading in degrees Celsius.
     * @example
     * val temps = _computeZoneTemperatures(devices, zoneIdToPath)
     */
    private fun _computeZoneTemperatures(
        devices: Map<String, DeviceDto>,
        zoneIdToPath: Map<String, String>
    ): Map<String, Double> {
        val zoneTempReadings = mutableMapOf<String, MutableList<Double>>()
        for (dto in devices.values) {
            val zoneId = dto.zone ?: continue
            val caps = dto.capabilitiesObj ?: continue
            val tempCap = caps[CAP_MEASURE_TEMPERATURE]
            val tempVal = (tempCap?.value as? Number)?.toDouble()
            if (tempVal != null && !tempVal.isNaN()) {
                zoneTempReadings.getOrPut(zoneId) { mutableListOf() }.add(tempVal)
            }
        }
        val computedTemps = mutableMapOf<String, Double>()
        for ((zId, readings) in zoneTempReadings) {
            if (readings.isNotEmpty()) {
                val zonePathName = zoneIdToPath[zId] ?: continue
                computedTemps[zonePathName] = readings.average()
            }
        }
        return computedTemps
    }

    /**
     * Merges live capability values, availability, capability metadata, and icon URL from a
     * remote [DeviceDto] into an existing [HomeyDevice] instance.
     *
     * @private
     * @param device Cached [HomeyDevice] to update.
     * @param remoteDto Fresh [DeviceDto] received from the Homey Web API.
     * @param targetHubId Optional hub identifier used to resolve relative icon URLs.
     * @return Updated [HomeyDevice] preserving zone ordering and custom quick-action metadata.
     * @example
     * val refreshed = _mergeLiveDeviceDto(existing, remoteDto, storage.getSelectedHomeyId())
     */
    private fun _mergeLiveDeviceDto(
        device: HomeyDevice,
        remoteDto: DeviceDto,
        targetHubId: String?
    ): HomeyDevice {
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

        val effectiveHub = targetHubId ?: storage.getSelectedHomeyId()
        val resolvedIconUrl = if (!remoteDto.iconOverride.isNullOrBlank()) {
            "https://my.homey.app/img/devices/${remoteDto.iconOverride}.svg"
        } else if (!remoteDto.iconObj?.url.isNullOrBlank()) {
            val raw = remoteDto.iconObj.url
            if (raw.startsWith("http")) raw else {
                val path = if (raw.startsWith("/")) raw else "/$raw"
                if (!effectiveHub.isNullOrBlank()) "https://$effectiveHub.connect.athom.com$path" else device.iconUrl
            }
        } else {
            device.iconUrl
        }

        return device.copy(
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
    }

    /**
     * Fetches and briefly caches (5-minute TTL) the catalog of Homey Insights log descriptors
     * for the active hub under [_insightLogsMutex], avoiding repeated ~88 KB downloads and
     * 200+ item Gson deserializations when inspecting consecutive devices.
     *
     * @private
     * @param service Authenticated [HomeyApiService] instance for the active hub.
     * @return [HomeyResult.Success] with paired `(logList, serverDateMs)` or [HomeyResult.Error].
     * @example
     * val result = _getInsightLogsForActiveHub(service)
     */
    private suspend fun _getInsightLogsForActiveHub(
        service: HomeyApiService
    ): HomeyResult<Pair<List<InsightLogDto>, Long?>> = _insightLogsMutex.withLock {
        val currentHubId = storage.getSelectedHomeyId()
        val nowMs = System.currentTimeMillis()
        val cacheTtlMs = 5 * 60 * 1000L

        if (_cachedInsightLogs != null &&
            _cachedInsightLogsHubId == currentHubId &&
            (nowMs - _cachedInsightLogsTimestampMs) < cacheTtlMs
        ) {
            Log.d(_TAG, "[DeviceRepository:_getInsightLogsForActiveHub] Returning ${_cachedInsightLogs!!.size} insight logs from in-memory cache.")
            return@withLock HomeyResult.Success(_cachedInsightLogs!! to _cachedInsightLogsServerDateMs)
        }

        val logsResponse = service.getInsightsLogs()
        if (!logsResponse.isSuccessful) {
            Log.e(_TAG, "[DeviceRepository:_getInsightLogsForActiveHub] Failed to get insight logs: HTTP ${logsResponse.code()} - ${logsResponse.errorBody()?.string()}")
            return@withLock HomeyResult.Error(context.getString(R.string.recap_error_history))
        }

        val logsServerDateMs = logsResponse.headers().getDate("Date")?.time
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
                            Log.w(_TAG, "[DeviceRepository:_getInsightLogsForActiveHub] Failed to parse log element: ${e.message}")
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
                            Log.w(_TAG, "[DeviceRepository:_getInsightLogsForActiveHub] Failed to parse log entry: ${e.message}")
                        }
                    }
                }
            }
        }

        _cachedInsightLogs = logList
        _cachedInsightLogsHubId = currentHubId
        _cachedInsightLogsTimestampMs = nowMs
        _cachedInsightLogsServerDateMs = logsServerDateMs

        Log.d(_TAG, "[DeviceRepository:_getInsightLogsForActiveHub] Retrieved and cached ${logList.size} insight logs.")
        HomeyResult.Success(logList to logsServerDateMs)
    }

    /**
     * Extracts the array of historical data points from either a raw JSON array
     * or a Homey Insights entry envelope containing a `"values"` array.
     *
     * Private helper method.
     *
     * @private
     * @param rootJson Parsed JSON element returned by Homey Insights or companion proxy.
     * @return Extracted [JsonArray] of `{ t, v }` points, or `null` if unavailable.
     * @example
     * val points = _extractInsightPointsJson(entriesNode)
     */
    private fun _extractInsightPointsJson(rootJson: JsonElement?): JsonArray? {
        return when {
            rootJson == null -> null
            rootJson.isJsonArray -> rootJson.asJsonArray
            rootJson.isJsonObject && rootJson.asJsonObject.has("values") && rootJson.asJsonObject.get("values").isJsonArray -> {
                rootJson.asJsonObject.getAsJsonArray("values")
            }
            else -> null
        }
    }

    /**
     * Checks whether a remote Homey [DeviceDto] exposes any supported device class or capability.
     *
     * @private
     * @param device Raw [DeviceDto] from Homey API.
     * @return `true` if the device should be shown in HomeyWatch.
     */
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

    /**
     * Maps a raw Homey [DeviceDto] into a domain [HomeyDevice] with zone ordering, favorites,
     * quick-action resolution, capabilities metadata, and icon URL.
     *
     * @private
     * @param device Raw [DeviceDto] instance.
     * @param zones Zone lookup map keyed by zone UUID.
     * @param favoritesList Ordered list of user-favorited device IDs.
     * @param excludedIds Device IDs bundled inside virtual groups.
     * @param globalZoneOrder Precomputed DFS display order map for zones.
     * @param targetHubId Optional explicit hub ID for relative icon URL resolution.
     * @return Mapped [HomeyDevice] instance.
     */
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
        val favIndex = favoritesList.indexOf(device.id)

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
            isFavorite = favIndex >= 0,
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
            capabilitiesMetadata = capMeta,
            favoriteOrder = if (favIndex >= 0) favIndex else Int.MAX_VALUE
        )
    }

    /**
     * Parses raw [CapabilityDto] map from Homey API into structured [CapabilityMeta] domain objects.
     *
     * @private
     * @param caps Raw capability DTO map keyed by capability identifier.
     * @return Map of capability IDs to parsed [CapabilityMeta] descriptors.
     * @example
     * val metadata = _parseCapabilitiesMetadata(deviceDto.capabilitiesObj)
     */
    private fun _parseCapabilitiesMetadata(caps: Map<String, CapabilityDto>?): Map<String, CapabilityMeta> {
        if (caps.isNullOrEmpty()) return emptyMap()
        return caps.mapValues { (capId, capDto) ->
            CapabilityMeta(
                id = capId,
                type = capDto.type,
                title = _resolveLocalizedText(capDto.title),
                setable = capDto.setable != false,
                uiComponent = capDto.uiComponent,
                insightsTitleTrue = _resolveLocalizedText(capDto.insightsTitleTrue),
                insightsTitleFalse = _resolveLocalizedText(capDto.insightsTitleFalse),
                titleTrue = _resolveLocalizedText(capDto.titleTrue),
                titleFalse = _resolveLocalizedText(capDto.titleFalse)
            )
        }
    }

    /**
     * Extracts a human-readable localized string from either a plain [String]
     * or a Homey translation map (`{ "en": ..., "it": ... }`), prioritizing the current system locale.
     *
     * @private
     * @param raw Dynamic field value parsed from Homey Web API JSON.
     * @return Resolved non-blank string, or `null` if unavailable.
     * @example
     * val label = _resolveLocalizedText(capDto.insightsTitleTrue)
     */
    private fun _resolveLocalizedText(raw: Any?): String? {
        return when (raw) {
            is String -> raw.takeIf { it.isNotBlank() }
            is Map<*, *> -> {
                val lang = java.util.Locale.getDefault().language
                (raw[lang] ?: raw["en"] ?: raw["it"] ?: raw.values.firstOrNull { it != null && it.toString().isNotBlank() })
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
            }
            else -> null
        }
    }

    /**
     * Selects the most representative Homey Insights log for a device's state history timeline.
     * Prioritizes the device's configured primary/quick-action capability, then known boolean control
     * capabilities (`onoff`, `garagedoor_closed`, `locked`, `windowcoverings_closed`), alarms, and other boolean logs.
     *
     * @private
     * @param deviceLogs Filtered list of [InsightLogDto] belonging to the device.
     * @param cachedDevice Optional cached [HomeyDevice] holding capability metadata and primary capability hints.
     * @return Best-matching [InsightLogDto], or `null` if no logs are available.
     * @example
     * val target = _selectPrimaryHistoryLog(deviceLogs, cachedDevice)
     */
    private fun _selectPrimaryHistoryLog(
        deviceLogs: List<InsightLogDto>,
        cachedDevice: HomeyDevice?
    ): InsightLogDto? {
        if (deviceLogs.isEmpty()) return null

        fun shortCapOf(log: InsightLogDto): String = (log.id ?: "").substringAfterLast(":")

        if (cachedDevice != null) {
            val preferredCaps = listOfNotNull(
                cachedDevice.quickActionCapability,
                cachedDevice.primaryCapability
            ).distinct()
            for (cap in preferredCaps) {
                val match = deviceLogs.firstOrNull { log ->
                    val shortCap = shortCapOf(log)
                    shortCap.equals(cap, ignoreCase = true) ||
                        shortCap.endsWith(".$cap", ignoreCase = true)
                }
                if (match != null) return match
            }
        }

        val priorityCaps = listOf(CAP_ONOFF, CAP_GARAGEDOOR, "locked", "windowcoverings_closed")
        for (cap in priorityCaps) {
            val match = deviceLogs.firstOrNull { log ->
                val shortCap = shortCapOf(log)
                val baseCap = shortCap.substringBefore(".")
                baseCap.equals(cap, ignoreCase = true)
            }
            if (match != null) return match
        }

        return deviceLogs.firstOrNull { log ->
            shortCapOf(log).startsWith("alarm_", ignoreCase = true)
        } ?: deviceLogs.firstOrNull { log ->
            log.type.equals("boolean", ignoreCase = true) &&
                !shortCapOf(log).contains(CAP_DIM, ignoreCase = true)
        } ?: deviceLogs.firstOrNull { log ->
            !shortCapOf(log).contains(CAP_DIM, ignoreCase = true)
        } ?: deviceLogs.firstOrNull()
    }

    /**
     * Resolves the localized state label for a historical boolean transition.
     * Precedence: Homey Insight log `titleTrue`/`titleFalse` -> device capability `insightsTitleTrue`/`insightsTitleFalse`
     * -> device capability `titleTrue`/`titleFalse` -> localized capability-specific fallback strings.
     *
     * @private
     * @param shortCapId Short capability identifier (e.g. `"locked"`, `"garagedoor_closed"`, `"onoff"`).
     * @param boolVal Raw boolean value recorded in Insights.
     * @param targetLog Selected [InsightLogDto] metadata from Homey Insights.
     * @param cachedDevice Optional cached [HomeyDevice] holding localized capability metadata and device class.
     * @return Localized human-readable state label.
     * @example
     * val label = _resolveBooleanHistoryStateText("locked", false, targetLog, cachedDevice)
     */
    private fun _resolveBooleanHistoryStateText(
        shortCapId: String,
        boolVal: Boolean,
        targetLog: InsightLogDto,
        cachedDevice: HomeyDevice?
    ): String {
        val logTitle = if (boolVal) targetLog.titleTrue else targetLog.titleFalse
        if (!logTitle.isNullOrBlank()) return logTitle

        val capMeta = cachedDevice?.capabilitiesMetadata?.get(shortCapId)
        val insightsTitle = if (boolVal) capMeta?.insightsTitleTrue else capMeta?.insightsTitleFalse
        if (!insightsTitle.isNullOrBlank()) return insightsTitle

        val sensorTitle = if (boolVal) capMeta?.titleTrue else capMeta?.titleFalse
        if (!sensorTitle.isNullOrBlank()) return sensorTitle

        val baseCap = shortCapId.substringBefore(".").lowercase()
        val devClass = cachedDevice?.deviceClass?.lowercase()

        return when {
            baseCap == CAP_GARAGEDOOR || baseCap == "windowcoverings_closed" -> {
                context.getString(if (boolVal) R.string.device_state_closed else R.string.device_state_open)
            }
            baseCap == "locked" -> {
                if (devClass == "garagedoor") {
                    context.getString(if (boolVal) R.string.device_state_closed else R.string.device_state_open)
                } else {
                    context.getString(if (boolVal) R.string.device_state_locked else R.string.device_state_unlocked)
                }
            }
            baseCap == CAP_ONOFF -> {
                context.getString(if (boolVal) R.string.device_history_turned_on else R.string.device_history_turned_off)
            }
            baseCap.startsWith("alarm_") -> {
                context.getString(if (boolVal) R.string.device_history_alarm_triggered else R.string.device_history_alarm_cleared)
            }
            else -> {
                context.getString(if (boolVal) R.string.device_history_active else R.string.device_history_inactive)
            }
        }
    }

    /**
     * Computes a flattened depth-first display order index for each Homey zone tree node.
     *
     * @private
     * @param zones Map of zone ID to [ZoneDto].
     * @return Map of zone ID to integer display order index.
     */
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

    /**
     * Builds a hierarchical display path string for a zone (excluding the root home node).
     *
     * @private
     * @param zoneId Target zone identifier.
     * @param zones Map of zone ID to [ZoneDto].
     * @return Formatted zone path (e.g. `"Ground Floor - Living Room"`).
     */
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

    /**
     * Parses a JSON element from an insight log entry into a Double numeric value.
     *
     * @private
     * @param json JsonElement representing a number or numeric string.
     * @return Parsed [Double], or `null` if invalid or non-numeric.
     * @example
     * val level = _parseInsightValueToDouble(obj.get("v"))
     */
    private fun _parseInsightValueToDouble(json: com.google.gson.JsonElement?): Double? {
        if (json == null || json.isJsonNull) return null
        return try {
            if (json.isJsonPrimitive) {
                val prim = json.asJsonPrimitive
                when {
                    prim.isNumber -> prim.asDouble
                    prim.isString -> prim.asString.toDoubleOrNull()
                    else -> null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
