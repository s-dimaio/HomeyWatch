package com.dimapp.android.homeywatch.repository.models

/**
 * Domain model representing a single historical state transition for a device.
 *
 * Public data class.
 *
 * @property timestamp Epoch millisecond timestamp when the state transition occurred.
 * @property isOn Whether the device transitioned into an active/on state (`true`) or inactive/off (`false`).
 * @property stateText Localized or human-readable description of the state (e.g. "On", "Off", "Open", "Closed").
 * @property serverClockOffsetMs Offset (`serverTimeMs - localClockMs`) observed from HTTP `Date` headers to compensate for skewed watch clocks.
 */
data class DeviceHistoryEvent(
    val timestamp: Long,
    val isOn: Boolean,
    val stateText: String,
    val serverClockOffsetMs: Long = 0L
)
