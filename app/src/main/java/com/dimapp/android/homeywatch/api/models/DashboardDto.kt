package com.dimapp.android.homeywatch.api.models

import com.google.gson.annotations.SerializedName

/**
 * DTO for a single Dashboard returned by the Homey Wear OS Companion App.
 *
 * Current endpoint: `app/com.dimapp.wearos/dashboards`
 * This structure mirrors the Companion App Dashboard schema but is populated with custom data
 * from the companion app settings.
 *
 * Public data class.
 *
 * @property id Unique dashboard UUID.
 * @property name Human-readable dashboard name.
 * @property columns Ordered list of column containers, each holding widgets.
 */
data class DashboardDto(
    @SerializedName("id")      val id: String,
    @SerializedName("name")    val name: String? = null,
    @SerializedName("columns") val columns: List<DashboardColumnDto> = emptyList()
)

/**
 * DTO for a column inside a [DashboardDto].
 *
 * Public data class.
 *
 * @property id Unique column UUID.
 * @property widgets Ordered list of widget items inside this column.
 */
data class DashboardColumnDto(
    @SerializedName("id")      val id: String,
    @SerializedName("widgets") val widgets: List<DashboardWidgetDto> = emptyList()
)

/**
 * DTO for a single widget inside a [DashboardColumnDto].
 *
 * - "Dispositivi Preferiti" (always first): filters by [HomeyDevice.isFavorite].
 * - One row for each Virtual Dashboard found via the Companion App API.
 *
 * @property id Unique widget UUID.
 * @property type Widget type identifier (e.g. `"devices_custom"`).
 * @property data Widget data payload; contains device IDs when [type] is `"devices_custom"`.
 */
data class DashboardWidgetDto(
    @SerializedName("id")   val id: String,
    @SerializedName("type") val type: String?,
    @SerializedName("data") val data: DashboardWidgetDataDto?
)

/**
 * DTO for the `data` block of a `"devices_custom"` widget.
 *
 * Public data class.
 *
 * @property deviceIds List of Homey device UUIDs explicitly added to this widget.
 * @property capabilities Map of device UUID to configured primary Quick Action capability ID.
 * @property customNames Map of device UUID to custom display label configured by user.
 */
data class DashboardWidgetDataDto(
    @SerializedName("deviceIds")    val deviceIds: List<String> = emptyList(),
    @SerializedName("capabilities") val capabilities: Map<String, String>? = null,
    @SerializedName("customNames")  val customNames: Map<String, String>? = null
)
