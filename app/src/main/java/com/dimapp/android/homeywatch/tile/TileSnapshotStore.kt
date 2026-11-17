package com.dimapp.android.homeywatch.tile

import android.content.Context
import android.util.Log
import com.dimapp.android.homeywatch.repository.models.CAP_TARGET_TEMPERATURE
import com.dimapp.android.homeywatch.repository.models.HomeyDevice
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Lightweight device metadata persisted on disk for stateless Wear OS Tile rendering.
 *
 * Stores only structural button properties and the last known active state as an offline
 * fallback so cold-started Tile requests render immediately without querying all devices.
 *
 * @property id Homey device UUID.
 * @property displayName Resolved display label for the Tile button.
 * @property primaryCapability Capability ID actuated on tap.
 * @property deviceClass Effective Homey device class (e.g. "light", "lock", "garagedoor").
 * @property iconUrl Optional Homey icon URL for inline Tile image loading.
 * @property isButtonAction Whether the primary capability is a momentary pulse action.
 * @property isThermostat Whether this device is a thermostat.
 * @property hasOnOff Whether this device exposes a binary `onoff` capability.
 * @property fallbackIsActive Last known active state used only if live pre-read fails.
 */
data class TileDeviceSnapshot(
    @SerializedName("id") val id: String,
    @SerializedName("displayName") val displayName: String,
    @SerializedName("primaryCapability") val primaryCapability: String,
    @SerializedName("deviceClass") val deviceClass: String?,
    @SerializedName("iconUrl") val iconUrl: String?,
    @SerializedName("isButtonAction") val isButtonAction: Boolean,
    @SerializedName("isThermostat") val isThermostat: Boolean,
    @SerializedName("hasOnOff") val hasOnOff: Boolean,
    @SerializedName("fallbackIsActive") val fallbackIsActive: Boolean
)

/**
 * Persistent snapshot of a Homey hub and up to 4 quick-action devices for Wear OS Tiles.
 *
 * @property hubId Target Homey hub ID (or "demo" / "demo_2").
 * @property hubName Display name rendered in the Tile header.
 * @property devices Ordered list of up to 4 quick-action device entries.
 */
data class TileSlotSnapshot(
    @SerializedName("hubId") val hubId: String,
    @SerializedName("hubName") val hubName: String,
    @SerializedName("devices") val devices: List<TileDeviceSnapshot>
)

/**
 * Disk-backed store for stateless Wear OS Tile configuration snapshots and anti-replay click IDs.
 *
 * Keeps Tile cold starts instantaneous by avoiding full-home device fetches while preventing
 * stale `lastClickableId` values from re-triggering device actuations across redraws or restarts.
 */
object TileSnapshotStore {

    private const val _TAG = "TileSnapshotStore"
    private const val _PREFS_NAME = "homey_tile_snapshot_prefs"
    private const val _KEY_HUB_PREFIX = "tile_snapshot_hub_"
    private const val _KEY_CONSUMED_IDS = "tile_consumed_click_ids"
    private const val _MAX_DEVICES = 4
    private const val _MAX_CONSUMED_HISTORY = 24

    private val _gson = Gson()
    private val _lock = Any()

    /**
     * Reads the persisted [TileSlotSnapshot] for the given [hubId], or returns null if absent.
     *
     * @public
     * @param context Application context.
     * @param hubId Target Homey hub ID.
     * @return Saved [TileSlotSnapshot] or null if no snapshot has been stored yet.
     * @example
     * val snapshot = TileSnapshotStore.getHubSnapshot(context, "hub_123")
     */
    fun getHubSnapshot(context: Context, hubId: String): TileSlotSnapshot? {
        if (hubId.isBlank()) return null
        val prefs = context.applicationContext.getSharedPreferences(_PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString("$_KEY_HUB_PREFIX$hubId", null) ?: return null
        return try {
            _gson.fromJson(raw, TileSlotSnapshot::class.java)
        } catch (e: Exception) {
            Log.w(_TAG, "[TileSnapshotStore:getHubSnapshot] Failed to parse snapshot for hub=$hubId: ${e.message}")
            null
        }
    }

    /**
     * Saves up to 4 quick-action devices for [hubId] and reports whether any visual or structural
     * property changed compared to the previously stored snapshot.
     *
     * Changes restricted to [TileDeviceSnapshot.fallbackIsActive] are persisted quietly without
     * returning `true`, so live device state changes do not trigger unnecessary Tile redraws.
     *
     * @public
     * @param context Application context.
     * @param hubId Target Homey hub ID.
     * @param hubName Display name of the hub.
     * @param devices Ordered quick-action devices resolved for this hub.
     * @return `true` if the Tile's visual structure (header name, device IDs, labels, icons, or
     *   capabilities) changed and requires a Tile update; `false` otherwise.
     * @example
     * val changed = TileSnapshotStore.saveHubSnapshotIfChanged(context, hubId, hubName, devices)
     */
    fun saveHubSnapshotIfChanged(
        context: Context,
        hubId: String,
        hubName: String,
        devices: List<HomeyDevice>
    ): Boolean {
        if (hubId.isBlank()) return false
        val entries = devices.take(_MAX_DEVICES).map { dev ->
            val cap = if (dev.isThermostat && dev.primaryCapability == CAP_TARGET_TEMPERATURE && dev.hasOnOff) {
                com.dimapp.android.homeywatch.repository.models.CAP_ONOFF
            } else {
                dev.quickActionCapability ?: dev.primaryCapability
            }
            TileDeviceSnapshot(
                id = dev.id,
                displayName = dev.displayName,
                primaryCapability = cap,
                deviceClass = dev.deviceClass,
                iconUrl = dev.iconUrl,
                isButtonAction = dev.isButtonAction,
                isThermostat = dev.isThermostat,
                hasOnOff = dev.hasOnOff,
                fallbackIsActive = dev.isActive
            )
        }
        val next = TileSlotSnapshot(hubId = hubId, hubName = hubName, devices = entries)

        synchronized(_lock) {
            val previous = getHubSnapshot(context, hubId)
            val structuralChanged = _hasStructuralChange(previous, next)
            val prefs = context.applicationContext.getSharedPreferences(_PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString("$_KEY_HUB_PREFIX$hubId", _gson.toJson(next))
                .apply()
            if (structuralChanged) {
                Log.d(_TAG, "[TileSnapshotStore:saveHubSnapshotIfChanged] Structural change saved for hub=$hubId (${entries.size} devices)")
            }
            return structuralChanged
        }
    }

    /**
     * Updates the cached [TileDeviceSnapshot.fallbackIsActive] flag for a single device after a
     * Tile actuation without altering the structural layout of the Tile.
     *
     * @public
     * @param context Application context.
     * @param hubId Target Homey hub ID.
     * @param deviceId Actuated device UUID.
     * @param isActive Resulting active state after actuation.
     * @example
     * TileSnapshotStore.updateFallbackActive(context, "hub_123", "dev_456", true)
     */
    fun updateFallbackActive(
        context: Context,
        hubId: String,
        deviceId: String,
        isActive: Boolean
    ) {
        if (hubId.isBlank() || deviceId.isBlank()) return
        synchronized(_lock) {
            val current = getHubSnapshot(context, hubId) ?: return
            var updatedAny = false
            val updatedDevices = current.devices.map { entry ->
                if (entry.id == deviceId && entry.fallbackIsActive != isActive) {
                    updatedAny = true
                    entry.copy(fallbackIsActive = isActive)
                } else {
                    entry
                }
            }
            if (updatedAny) {
                val prefs = context.applicationContext.getSharedPreferences(_PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("$_KEY_HUB_PREFIX$hubId", _gson.toJson(current.copy(devices = updatedDevices)))
                    .apply()
            }
        }
    }

    /**
     * Atomically checks whether [clickableId] was already processed and records it if new.
     *
     * Prevents duplicate device actuations if Wear OS delivers the same `lastClickableId`
     * again across system redraws or process restarts.
     *
     * @public
     * @param context Application context.
     * @param clickableId Unique clickable identifier received from `TileRequest.currentState`.
     * @return `true` if this [clickableId] is new and marked as consumed now; `false` if it was
     *   already consumed previously.
     * @example
     * if (TileSnapshotStore.markClickConsumedIfNew(context, clickedId)) { ... }
     */
    fun markClickConsumedIfNew(context: Context, clickableId: String): Boolean {
        if (clickableId.isBlank()) return false
        synchronized(_lock) {
            val prefs = context.applicationContext.getSharedPreferences(_PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(_KEY_CONSUMED_IDS, null)
            val history = raw?.split("\n")?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
            if (history.contains(clickableId)) {
                Log.w(_TAG, "[TileSnapshotStore:markClickConsumedIfNew] Ignoring duplicate clickableId")
                return false
            }
            history.add(clickableId)
            val trimmed = if (history.size > _MAX_CONSUMED_HISTORY) {
                history.takeLast(_MAX_CONSUMED_HISTORY)
            } else {
                history
            }
            prefs.edit()
                .putString(_KEY_CONSUMED_IDS, trimmed.joinToString("\n"))
                .apply()
            return true
        }
    }

    /**
     * Compares two snapshots ignoring volatile [TileDeviceSnapshot.fallbackIsActive] values.
     *
     * @private
     * @param previous Previously stored snapshot, if any.
     * @param next Newly resolved snapshot.
     * @return `true` if header or button structure differs.
     */
    private fun _hasStructuralChange(previous: TileSlotSnapshot?, next: TileSlotSnapshot): Boolean {
        if (previous == null) return true
        if (previous.hubId != next.hubId || previous.hubName != next.hubName) return true
        if (previous.devices.size != next.devices.size) return true
        return previous.devices.zip(next.devices).any { (old, cur) ->
            old.id != cur.id ||
                old.displayName != cur.displayName ||
                old.primaryCapability != cur.primaryCapability ||
                old.deviceClass != cur.deviceClass ||
                old.iconUrl != cur.iconUrl ||
                old.isButtonAction != cur.isButtonAction ||
                old.isThermostat != cur.isThermostat ||
                old.hasOnOff != cur.hasOnOff
        }
    }
}
