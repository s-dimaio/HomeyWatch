package com.dimapp.android.homeywatch.repository.models

/**
 * Device classes considered "door/gate/lock" devices.
 */
val DOOR_CLASSES = setOf(
    "garagedoor",
    "lock",
    "doorbell"
)

/** Device classes considered "light" devices. */
val LIGHT_CLASSES = setOf(
    "light",
    "lamp"
)

/** Device classes considered "thermostat" devices. */
val THERMOSTAT_CLASSES = setOf(
    "thermostat"
)

/**
 * Capability used to open/close garage doors and gates.
 * `false` = open, `true` = closed.
 */
const val CAP_GARAGEDOOR = "garagedoor_closed"

/**
 * Fallback capability for devices that use a simple on/off toggle.
 */
const val CAP_ONOFF = "onoff"

/** Capability for dimming / brightness (0.0 to 1.0). */
const val CAP_DIM = "dim"

/** Capability for target temperature setpoint. */
const val CAP_TARGET_TEMPERATURE = "target_temperature"

/** Capability for current measured temperature. */
const val CAP_MEASURE_TEMPERATURE = "measure_temperature"

/** Capability for light hue (0.0 to 1.0). */
const val CAP_LIGHT_HUE = "light_hue"

/** Capability for light saturation (0.0 to 1.0). */
const val CAP_LIGHT_SATURATION = "light_saturation"

/** Capability for light color temperature (0.0 to 1.0, warm to cold). */
const val CAP_LIGHT_TEMPERATURE = "light_temperature"

/** Capability for thermostat mode (e.g. "auto", "heat", "off"). */
const val CAP_THERMOSTAT_MODE = "thermostat_mode"

/**
 * Domain model for a smart device (Lock/Door/Light/Thermostat), ready for display in Wear OS screens.
 *
 * @property id Homey device UUID.
 * @property name Human-readable name (e.g. "Cancello Giardino").
 * @property zoneName Name of the zone/room the device is in.
 * @property isActive For doors: `true` if open. For lights/thermostats: `true` if on/active.
 * @property isAvailable Whether the device is currently reachable.
 * @property primaryCapability The capability ID used to toggle this device.
 * @property deviceClass Effective device class.
 * @property iconUrl URL of the icon to be displayed.
 * @property isFavorite `true` if this device is marked as a user favorite.
 * @property isLight `true` if this device is considered a Light.
 * @property isHidden `true` if the device shouldn't be shown.
 * @property isGroupMember `true` if this device is part of a larger group.
 * @property zoneOrder Order index based on depth-first search of zones.
 * @property zoneId Optional UUID of the zone/room the device belongs to.
 * @property isZoneActive Whether the device's zone is currently active (e.g. motion/occupancy).
 * @property temperature Optional temperature reading if the device reports `measure_temperature`.
 * @property isThermostat `true` if this device is a Thermostat.
 * @property isDimmable `true` if this device supports dimming (`dim`).
 * @property hasColor `true` if this device supports RGB color (`light_hue`).
 * @property hasColorTemp `true` if this device supports color temperature (`light_temperature`).
 * @property hasOnOff `true` if this device has a binary `onoff` capability.
 * @property dimValue Current brightness value between 0.0 and 1.0.
 * @property targetTemperature Target setpoint temperature in degrees Celsius.
 * @property currentTemperature Measured temperature in degrees Celsius.
 * @property lightHue Color hue between 0.0 and 1.0.
 * @property lightSaturation Color saturation between 0.0 and 1.0.
 * @property lightTemperature Color temperature between 0.0 and 1.0.
 * @property rawCapabilities Map of capability ID to raw value received from the Homey API.
 * @property customName Optional custom display name configured by the user in companion app.
 */
data class HomeyDevice(
    val id: String,
    val name: String,
    val zoneName: String,
    val isActive: Boolean,
    val isAvailable: Boolean,
    val primaryCapability: String,
    val deviceClass: String?,
    val iconUrl: String?,
    val isFavorite: Boolean,
    val isLight: Boolean,
    val isHidden: Boolean,
    val isGroupMember: Boolean,
    val zoneOrder: Int,
    val zoneId: String? = null,
    val isZoneActive: Boolean = false,
    val temperature: Double? = null,
    val isThermostat: Boolean = false,
    val isDimmable: Boolean = false,
    val hasColor: Boolean = false,
    val hasColorTemp: Boolean = false,
    val hasOnOff: Boolean = true,
    val dimValue: Double? = null,
    val targetTemperature: Double? = null,
    val currentTemperature: Double? = null,
    val lightHue: Double? = null,
    val lightSaturation: Double? = null,
    val lightTemperature: Double? = null,
    val rawCapabilities: Map<String, Any?> = emptyMap(),
    val customName: String? = null,
    val quickActionCapability: String? = null,
    val capabilitiesMetadata: Map<String, CapabilityMeta> = emptyMap()
) {
    /**
     * Display label for this device, favoring the custom label if configured.
     */
    val displayName: String
        get() = customName?.takeIf { it.isNotBlank() } ?: name

    /**
     * Whether this device has an actionable quick action configured.
     */
    val hasQuickAction: Boolean
        get() = !quickActionCapability.isNullOrBlank()

    /**
     * Whether the device's quick action is currently active.
     */
    val isQuickActionActive: Boolean
        get() = quickActionCapability?.let { isCapabilityActive(it, rawCapabilities[it]) } ?: isActive

    /**
     * True if the quick action capability represents a momentary button or trigger action (e.g. unlatch, buzzer).
     */
    val isQuickActionButtonAction: Boolean
        get() = isButtonOrActionCapability(quickActionCapability)

    /**
     * True if the device's primary or quick action capability represents a momentary button or trigger action.
     */
    val isButtonAction: Boolean
        get() = isButtonOrActionCapability(quickActionCapability ?: primaryCapability)

    /**
     * Checks if this device supports a specific capability ID.
     *
     * @public
     * @param capabilityId Capability ID to check.
     * @return True if capability exists in [rawCapabilities].
     */
    fun hasCapability(capabilityId: String): Boolean = rawCapabilities.containsKey(capabilityId)
}

/**
 * Metadata for a capability exposed by the Homey Web API.
 *
 * @property id Unique capability identifier (e.g. "dim", "volume_set", "onoff").
 * @property type Value type (e.g. "boolean", "number", "string", "enum").
 * @property title Human-readable localized title if available.
 * @property setable Whether the capability accepts mutations.
 * @property uiComponent Preferred UI component template (e.g. "slider", "toggle", "sensor").
 */
data class CapabilityMeta(
    val id: String,
    val type: String? = null,
    val title: String? = null,
    val setable: Boolean = true,
    val uiComponent: String? = null
)

/**
 * Resolves all continuous slider-based capabilities supported by the device.
 *
 * @public
 * @param device Target smart device.
 * @return Ordered list of slider capability identifiers.
 */
fun resolveSliderCapabilities(device: HomeyDevice): List<String> {
    val result = mutableListOf<String>()
    for ((id, meta) in device.capabilitiesMetadata) {
        if (meta.uiComponent == "slider" && meta.setable && !result.contains(id)) {
            result.add(id)
        }
    }
    val knownSliders = listOf(CAP_DIM, "volume_set", "windowcoverings_set")
    for (known in knownSliders) {
        if (device.rawCapabilities.containsKey(known) && !result.contains(known)) {
            result.add(known)
        }
    }
    for (key in device.rawCapabilities.keys) {
        if ((key.startsWith("dim.") || key.startsWith("volume_set.") || key.startsWith("windowcoverings_set.")) && !result.contains(key)) {
            result.add(key)
        }
    }
    if (result.isEmpty() && device.isDimmable) {
        result.add(CAP_DIM)
    }
    return result
}

/**
 * Resolves the numeric Double value (0.0 to 1.0) for a specific capability.
 *
 * @public
 * @param device Target device.
 * @param capabilityId Capability identifier.
 * @return Double value if present and convertible, or null.
 */
fun resolveCapabilityDouble(device: HomeyDevice, capabilityId: String): Double? {
    if (capabilityId == CAP_DIM && device.dimValue != null) {
        return device.dimValue
    }
    val raw = device.rawCapabilities[capabilityId]
    return when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    }
}

/**
 * Checks whether a capability represents a momentary button or trigger action (e.g. unlatch, buzzer, push button).
 *
 * @param capabilityId Target capability identifier.
 * @return True if capability is a momentary button or stateless action.
 */
fun isButtonOrActionCapability(capabilityId: String?): Boolean {
    if (capabilityId == null) return false
    return capabilityId == "button" ||
            capabilityId.startsWith("button.") ||
            capabilityId == "unlatch" ||
            capabilityId.startsWith("unlatch.") ||
            capabilityId == "open" ||
            capabilityId.startsWith("open.")
}

/**
 * Evaluates whether a capability's raw value signifies an "active" state.
 *
 * For doors/locks/shutters, `false` means open/unlocked (active).
 * For momentary push buttons and unlatch actions, the active state is always `false` (until pressed).
 * For on/off and sensors, `true` means turned on/triggered.
 *
 * @param capabilityId Target capability identifier.
 * @param rawValue Raw value from Homey API.
 * @return True if considered active, false otherwise.
 */
fun isCapabilityActive(capabilityId: String, rawValue: Any?): Boolean {
    if (isButtonOrActionCapability(capabilityId)) return false
    val boolVal = rawValue as? Boolean
    return when (capabilityId) {
        CAP_GARAGEDOOR           -> boolVal == false
        "locked"                 -> boolVal == false
        "windowcoverings_closed" -> boolVal == false
        CAP_TARGET_TEMPERATURE   -> true
        else                     -> boolVal == true
    }
}

