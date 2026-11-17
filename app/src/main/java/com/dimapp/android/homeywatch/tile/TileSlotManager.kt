package com.dimapp.android.homeywatch.tile

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.wear.tiles.TileService
import com.dimapp.android.homeywatch.R
import com.dimapp.android.homeywatch.core.DependencyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Data holder representing a configured Homey hub.
 *
 * @property id Unique Homey hub ID or "demo".
 * @property name Display name of the hub.
 */
data class HubInfo(
    val id: String,
    val name: String
)

/**
 * Manager that dynamically enables or disables Wear OS [TileService] components based on
 * the number of authenticated Homey hubs.
 *
 * Each authenticated hub is assigned to a dedicated Tile slot:
 * - Slot 1: [QuickActionsTileService]
 * - Slot 2: [QuickActionsTileSlot2]
 * - Slot 3: [QuickActionsTileSlot3]
 * - Slot 4: [QuickActionsTileSlot4]
 */
object TileSlotManager {

    private const val TAG = "TileSlotManager"
    const val MAX_SLOTS = 4
    private val _syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var _lastSyncedSignature: String? = null

    /**
     * Launches [syncSlots] asynchronously on a background IO dispatcher so startup paths
     * do not perform `EncryptedSharedPreferences` reads or `PackageManager` IPC on the main thread.
     *
     * @public
     * @param context Application context.
     * @example
     * TileSlotManager.syncSlotsAsync(context)
     */
    fun syncSlotsAsync(context: Context) {
        val appCtx = context.applicationContext
        _syncScope.launch {
            syncSlots(appCtx)
        }
    }

    /**
     * Returns the [TileService] class corresponding to the given 1-based slot number.
     *
     * @public
     * @param slotNumber Slot index (1..4).
     * @return [TileService] class for this slot.
     */
    fun getServiceClass(slotNumber: Int): Class<out TileService> {
        return when (slotNumber) {
            1 -> QuickActionsTileService::class.java
            2 -> QuickActionsTileSlot2::class.java
            3 -> QuickActionsTileSlot3::class.java
            4 -> QuickActionsTileSlot4::class.java
            else -> QuickActionsTileService::class.java
        }
    }

    /**
     * Synchronizes all Tile slots with the currently authenticated hubs.
     *
     * Enables a dedicated Tile for each authenticated hub and disables unused slots
     * via [PackageManager.setComponentEnabledSetting].
     *
     * @public
     * @param context Application context.
     * @example
     * TileSlotManager.syncSlots(context)
     */
    @Synchronized
    fun syncSlots(context: Context) {
        val appCtx = context.applicationContext
        val storage = DependencyManager.getTokenStorage(appCtx)
        val packageManager = appCtx.packageManager

        val hubs = mutableListOf<HubInfo>()

        if (storage.isDemoMode()) {
            hubs.add(HubInfo("demo", appCtx.getString(R.string.demo_hub_title)))
            hubs.add(HubInfo("demo_2", appCtx.getString(R.string.demo_hub_2_title)))
        } else {
            val allHubs = storage.getAllHubTokens()
            if (allHubs.isNotEmpty()) {
                allHubs.forEach { (id, entry) ->
                    val displayName = entry.name ?: "Homey"
                    hubs.add(HubInfo(id, displayName))
                }
            } else {
                val activeId = storage.getSelectedHomeyId()
                if (!activeId.isNullOrBlank()) {
                    val activeName = storage.getSelectedHomeyName() ?: "Homey"
                    hubs.add(HubInfo(activeId, activeName))
                }
            }
        }

        val signature = hubs.joinToString("|") { "${it.id}:${it.name}" }
        if (signature == _lastSyncedSignature) {
            return
        }

        Log.i(TAG, "[TileSlotManager:syncSlots] Synchronizing Tile slots for ${hubs.size} hub(s).")

        for (slot in 1..MAX_SLOTS) {
            val serviceClass = getServiceClass(slot)
            val componentName = ComponentName(appCtx, serviceClass)
            val targetHub = hubs.getOrNull(slot - 1)

            if (targetHub != null) {
                val previousHubId = storage.getTileSlotHubId(slot)
                val previousHubName = storage.getTileSlotHubName(slot)
                val isAssignmentChanged = (previousHubId != targetHub.id || previousHubName != targetHub.name)

                // Assign hub to this slot
                storage.setTileSlotHub(slot, targetHub.id, targetHub.name)

                // Enable service in PackageManager if not already enabled
                val currentState = packageManager.getComponentEnabledSetting(componentName)
                val stateChanged = if (currentState != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    packageManager.setComponentEnabledSetting(
                        componentName,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    Log.d(TAG, "[TileSlotManager:syncSlots] Enabled slot $slot for hub: ${targetHub.name}")
                    true
                } else {
                    false
                }

                // Force Tile update only if assignment or component state changed (avoids startup request loop)
                if (isAssignmentChanged || stateChanged) {
                    try {
                        TileService.getUpdater(appCtx).requestUpdate(serviceClass)
                    } catch (e: Exception) {
                        Log.w(TAG, "[TileSlotManager:syncSlots] Could not request update for slot $slot: ${e.message}")
                    }
                }
            } else {
                val hadPreviousAssignment = !storage.getTileSlotHubId(slot).isNullOrBlank()

                // Clear assignment
                storage.clearTileSlot(slot)

                // Disable slot 2..4 if unused
                if (slot > 1) {
                    val currentState = packageManager.getComponentEnabledSetting(componentName)
                    if (currentState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                        packageManager.setComponentEnabledSetting(
                            componentName,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                        Log.d(TAG, "[TileSlotManager:syncSlots] Disabled unused slot $slot")
                    }
                } else {
                    // Slot 1 remains enabled (may show unconfigured/login state if no hubs)
                    if (hadPreviousAssignment) {
                        try {
                            TileService.getUpdater(appCtx).requestUpdate(serviceClass)
                        } catch (e: Exception) {
                            Log.w(TAG, "[TileSlotManager:syncSlots] Could not request update for slot 1: ${e.message}")
                        }
                    }
                }
            }
        }
        _lastSyncedSignature = signature
    }

    /**
     * Triggers a tile update request for all enabled slots.
     *
     * @public
     * @param context Application context.
     * @example
     * TileSlotManager.updateAllSlots(context)
     */
    fun updateAllSlots(context: Context) {
        val appCtx = context.applicationContext
        val storage = DependencyManager.getTokenStorage(appCtx)
        for (slot in 1..MAX_SLOTS) {
            val hubId = storage.getTileSlotHubId(slot)
            // Slot 1 is always valid (default/active); slots 2..MAX_SLOTS only if an assigned hub exists
            if (slot == 1 || !hubId.isNullOrBlank()) {
                try {
                    TileService.getUpdater(appCtx).requestUpdate(getServiceClass(slot))
                } catch (e: Exception) {
                    Log.w(TAG, "[TileSlotManager:updateAllSlots] Failed to request update for slot $slot: ${e.message}")
                }
            }
        }
    }
}
