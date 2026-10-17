package com.dimapp.android.homeywatch.api.models

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/**
 * DTO representing an Insight log definition returned by Homey API.
 *
 * Public data class.
 *
 * @property id Log identifier (e.g. "onoff", "dim").
 * @property uri Resource URI (e.g. "homey:device:xxx").
 * @property ownerUri Owner URI (e.g. "homey:device:xxx").
 * @property type Log data type (e.g. "boolean", "number").
 * @property title Human readable title.
 */
data class InsightLogDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("uri") val uri: String? = null,
    @SerializedName("ownerUri") val ownerUri: String? = null,
    @SerializedName("ownerId") val ownerId: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("title") val title: String? = null
)

/**
 * DTO for the entries response wrapper from `manager/insights/log/{id}/entry`.
 *
 * Public data class.
 *
 * @property values List of individual insight data points.
 */
data class InsightEntriesDto(
    @SerializedName("values") val values: List<InsightPointDto>? = null
)

/**
 * DTO for a single historical data point in an Insight log.
 *
 * Public data class.
 *
 * @property t Raw timestamp element (can be epoch timestamp number or ISO date string).
 * @property v Raw value element (can be boolean, numeric 0/1, or float).
 */
data class InsightPointDto(
    @SerializedName("t") val t: JsonElement? = null,
    @SerializedName("v") val v: JsonElement? = null
)
