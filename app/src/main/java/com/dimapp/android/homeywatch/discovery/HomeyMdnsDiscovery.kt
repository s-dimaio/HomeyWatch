package com.dimapp.android.homeywatch.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "HomeyMdnsDiscovery"
private const val SERVICE_TYPE = "_homey._tcp"

/**
 * Data model representing a Homey Pro hub discovered via mDNS DNS-SD on the local network.
 *
 * @property id      The 24-character hexadecimal Homey ID of the hub.
 * @property name    The user-configured display name (e.g. "Veggia", "Home").
 * @property model   The hardware model identifier (e.g. "homey5q").
 * @property version The Homey OS firmware version (e.g. "13.5.0").
 * @property host    Local IPv4 address of the hub.
 * @property port    Port exposing HTTP services (typically 80).
 */
data class DiscoveredHomey(
    val id: String,
    val name: String,
    val model: String? = null,
    val version: String? = null,
    val host: String = "",
    val port: Int = 80
)

/**
 * Service that handles local network discovery of Athom Homey Pro hubs using Android's
 * native [NsdManager] (Network Service Discovery) and mDNS (`_homey._tcp`).
 *
 * Acquires a [WifiManager.MulticastLock] during active discovery to ensure Wear OS
 * receives incoming mDNS announcements over Wi-Fi.
 *
 * @param context Application context used to retrieve system services.
 */
class HomeyMdnsDiscovery(private val context: Context) {

    private val _nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val _wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var _multicastLock: WifiManager.MulticastLock? = null
    private var _discoveryListener: NsdManager.DiscoveryListener? = null
    private var _timeoutJob: Job? = null
    private val _scope = CoroutineScope(Dispatchers.Main)

    private val _discoveredHubs = MutableStateFlow<List<DiscoveredHomey>>(emptyList())
    val discoveredHubs: StateFlow<List<DiscoveredHomey>> = _discoveredHubs.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // Internal queue to prevent NsdManager.FAILURE_ALREADY_ACTIVE during concurrent resolves
    private val _pendingResolves = ArrayDeque<NsdServiceInfo>()
    private var _isResolving = false
    private val _resolveLock = Any()

    // -- Public Methods --------------------------------------------------------

    /**
     * Starts discovering Homey Pro hubs on the local Wi-Fi network.
     * Automatically stops after [timeoutMs] milliseconds.
     *
     * @public
     * @param timeoutMs Maximum duration of discovery in milliseconds (default 12000ms).
     * @example
     * ```
     * val discovery = HomeyMdnsDiscovery(context)
     * discovery.startDiscovery(timeoutMs = 12000L)
     * ```
     */
    fun startDiscovery(timeoutMs: Long = 12000L) {
        if (_isSearching.value) {
            Log.d(TAG, "[HomeyMdnsDiscovery:startDiscovery] Discovery is already active.")
            return
        }

        if (_nsdManager == null) {
            Log.w(TAG, "[HomeyMdnsDiscovery:startDiscovery] NsdManager is unavailable on this device.")
            return
        }

        _discoveredHubs.value = emptyList()
        _isSearching.value = true
        synchronized(_resolveLock) {
            _pendingResolves.clear()
            _isResolving = false
        }

        _acquireMulticastLock()
        _startNsdDiscovery()
        _scheduleTimeout(timeoutMs)
    }

    /**
     * Stops active mDNS service discovery and releases the multicast lock.
     *
     * @public
     * @example
     * ```
     * discovery.stopDiscovery()
     * ```
     */
    fun stopDiscovery() {
        _timeoutJob?.cancel()
        _timeoutJob = null

        _safeStopDiscovery()
        _releaseMulticastLock()

        _isSearching.value = false
    }

    // -- Private Methods -------------------------------------------------------

    /**
     * Starts mDNS service discovery on the bound Wi-Fi interface.
     *
     * @private
     */
    private fun _startNsdDiscovery() {
        if (_discoveryListener != null) {
            Log.d(TAG, "[HomeyMdnsDiscovery:_startNsdDiscovery] Discovery listener is already active.")
            return
        }

        _acquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "[HomeyMdnsDiscovery:onDiscoveryStarted] Service discovery started for $regType")
                _isSearching.value = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "[HomeyMdnsDiscovery:onServiceFound] Discovered service: name=${serviceInfo.serviceName}, type=${serviceInfo.serviceType}")
                synchronized(_resolveLock) {
                    _pendingResolves.add(serviceInfo)
                    _processNextResolve()
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "[HomeyMdnsDiscovery:onServiceLost] Service lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "[HomeyMdnsDiscovery:onDiscoveryStopped] Service discovery stopped for $serviceType")
                _isSearching.value = false
                _releaseMulticastLock()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "[HomeyMdnsDiscovery:onStartDiscoveryFailed] Start discovery failed: code $errorCode")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "[HomeyMdnsDiscovery:onStopDiscoveryFailed] Stop discovery failed: code $errorCode")
            }
        }

        _discoveryListener = listener

        try {
            _nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            _isSearching.value = true
            Log.d(TAG, "[HomeyMdnsDiscovery:_startNsdDiscovery] Initiated discoverServices for $SERVICE_TYPE")
        } catch (e: Exception) {
            Log.e(TAG, "[HomeyMdnsDiscovery:_startNsdDiscovery] Exception starting discovery: ${e.message}")
            stopDiscovery()
        }
    }

    /**
     * Schedules discovery timeout.
     *
     * @private
     * @param timeoutMs Timeout duration in milliseconds.
     */
    private fun _scheduleTimeout(timeoutMs: Long) {
        _timeoutJob?.cancel()
        _timeoutJob = _scope.launch {
            delay(timeoutMs)
            Log.d(TAG, "[HomeyMdnsDiscovery:_scheduleTimeout] Discovery timeout reached (${timeoutMs}ms). Stopping...")
            stopDiscovery()
        }
    }

    /**
     * Sequentially processes service resolution requests from the internal queue.
     *
     * @private
     */
    private fun _processNextResolve() {
        synchronized(_resolveLock) {
            if (_isResolving || _pendingResolves.isEmpty()) return
            val nextService = _pendingResolves.removeFirst()
            _isResolving = true

            try {
                _nsdManager?.resolveService(nextService, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                        Log.w(TAG, "[HomeyMdnsDiscovery:onResolveFailed] Failed to resolve ${serviceInfo?.serviceName}: code $errorCode")
                        synchronized(_resolveLock) {
                            _isResolving = false
                            _processNextResolve()
                        }
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                        _handleResolvedService(serviceInfo)
                        synchronized(_resolveLock) {
                            _isResolving = false
                            _processNextResolve()
                        }
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "[HomeyMdnsDiscovery:_processNextResolve] Exception resolving service: ${e.message}")
                _isResolving = false
                _processNextResolve()
            }
        }
    }

    /**
     * Parses the TXT record attributes of a resolved Homey mDNS service.
     *
     * @private
     * @param serviceInfo The resolved [NsdServiceInfo].
     */
    private fun _handleResolvedService(serviceInfo: NsdServiceInfo?) {
        if (serviceInfo == null) return

        try {
            val attributes = serviceInfo.attributes
            val id = (attributes["id"] ?: attributes["ID"])?.let { String(it, Charsets.UTF_8).trim() }
            val name = attributes["name"]?.let { String(it, Charsets.UTF_8).trim() } ?: serviceInfo.serviceName
            val model = attributes["model"]?.let { String(it, Charsets.UTF_8).trim() }
            val version = attributes["version"]?.let { String(it, Charsets.UTF_8).trim() }
            val host = serviceInfo.host?.hostAddress ?: ""
            val port = serviceInfo.port

            if (!id.isNullOrBlank()) {
                val discovered = DiscoveredHomey(
                    id = id,
                    name = name,
                    model = model,
                    version = version,
                    host = host,
                    port = port
                )
                Log.d(TAG, "[HomeyMdnsDiscovery:_handleResolvedService] Successfully resolved Homey hub: name=$name, id=$id, ip=$host:$port")

                val currentList = _discoveredHubs.value.toMutableList()
                if (currentList.none { it.id == id }) {
                    currentList.add(discovered)
                    _discoveredHubs.value = currentList
                }
            } else {
                Log.w(TAG, "[HomeyMdnsDiscovery:_handleResolvedService] Discovered service missing 'id' in TXT record: ${serviceInfo.serviceName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[HomeyMdnsDiscovery:_handleResolvedService] Error parsing resolved service: ${e.message}")
        }
    }

    /**
     * Safely unregisters the NsdManager discovery listener without throwing unhandled exceptions.
     *
     * @private
     */
    private fun _safeStopDiscovery() {
        val listener = _discoveryListener ?: return
        try {
            _nsdManager?.stopServiceDiscovery(listener)
            Log.d(TAG, "[HomeyMdnsDiscovery:_safeStopDiscovery] Service discovery stop requested.")
        } catch (e: Exception) {
            Log.d(TAG, "[HomeyMdnsDiscovery:_safeStopDiscovery] Stop discovery suppressed: ${e.message}")
        } finally {
            _discoveryListener = null
        }
    }

    /**
     * Acquires the Wi-Fi MulticastLock so the OS Wi-Fi chipset passes mDNS packets.
     *
     * @private
     */
    private fun _acquireMulticastLock() {
        try {
            if (_multicastLock == null) {
                _multicastLock = _wifiManager?.createMulticastLock("HomeyMdnsLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (_multicastLock?.isHeld == false) {
                _multicastLock?.acquire()
                Log.d(TAG, "[HomeyMdnsDiscovery:_acquireMulticastLock] Multicast lock acquired.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[HomeyMdnsDiscovery:_acquireMulticastLock] Failed to acquire multicast lock: ${e.message}")
        }
    }

    /**
     * Releases the Wi-Fi MulticastLock to preserve battery life.
     *
     * @private
     */
    private fun _releaseMulticastLock() {
        try {
            if (_multicastLock?.isHeld == true) {
                _multicastLock?.release()
                Log.d(TAG, "[HomeyMdnsDiscovery:_releaseMulticastLock] Multicast lock released.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[HomeyMdnsDiscovery:_releaseMulticastLock] Failed to release multicast lock: ${e.message}")
        }
    }
}
