package com.dimapp.android.homeywatch.api

import com.dimapp.android.homeywatch.api.models.DashboardDto
import com.dimapp.android.homeywatch.api.models.DeviceDto
import com.dimapp.android.homeywatch.api.models.FlowDto
import com.dimapp.android.homeywatch.api.models.FlowFolderDto
import com.google.gson.JsonElement
import com.dimapp.android.homeywatch.api.models.InsightLogDto
import com.dimapp.android.homeywatch.api.models.SetCapabilityBody
import com.dimapp.android.homeywatch.api.models.UserMeDto
import com.dimapp.android.homeywatch.api.models.ZoneDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for the Homey Cloud REST API v3.
 *
 * Base URL: `https://{homeyId}.connect.athom.com/api/`
 *
 * Authorization is handled transparently by [TokenInjectorInterceptor].
 */
interface HomeyApiService {

    /**
     * Retrieves all devices registered in Homey.
     *
     * @public
     * @return Map of device ID -> [DeviceDto], or an error [Response].
     */
    @GET("manager/devices/device")
    suspend fun getDevices(): Response<Map<String, DeviceDto>>

    /**
     * Sets the value of a specific capability on a device.
     *
     * @public
     * @param deviceId     UUID of the target device.
     * @param capabilityId Capability identifier (e.g. `"onoff"`, `"locked"`, `"garagedoor_closed"`).
     * @param body         [SetCapabilityBody] containing the new value.
     * @return Empty [Response] on success (HTTP 200).
     */
    @PUT("manager/devices/device/{deviceId}/capability/{capabilityId}")
    suspend fun setCapability(
        @Path("deviceId")     deviceId: String,
        @Path("capabilityId") capabilityId: String,
        @Body body: SetCapabilityBody
    ): Response<Unit>

    /**
     * Retrieves all standard Flows defined in Homey.
     *
     * @public
     * @return Map of flow ID -> [FlowDto], or an error [Response].
     */
    @GET("manager/flow/flow")
    suspend fun getFlows(): Response<Map<String, FlowDto>>

    /**
     * Triggers a Homey standard Flow by its UUID.
     *
     * @public
     * @param flowId UUID of the flow to trigger.
     * @return Empty [Response] on success (HTTP 200).
     */
    @POST("manager/flow/flow/{flowId}/trigger")
    suspend fun triggerFlow(
        @Path("flowId") flowId: String
    ): Response<Unit>

    /**
     * Retrieves all flow folders defined in Homey.
     *
     * @public
     * @return Map of folder ID -> [FlowFolderDto], or an error [Response].
     */
    @GET("manager/flow/folder")
    suspend fun getFlowFolders(): Response<Map<String, FlowFolderDto>>

    /**
     * Retrieves all zones (rooms) defined in Homey.
     *
     * @public
     * @return Map of zone ID -> [ZoneDto], or an error [Response].
     */
    @GET("manager/zones/zone")
    suspend fun getZones(): Response<Map<String, ZoneDto>>

    /**
     * Retrieves the current value object of a specific capability for a device.
     *
     * @public
     * @param deviceId     UUID of the target device.
     * @param capabilityId Capability identifier.
     * @return A map containing the capability properties, e.g. `{"value": true}`.
     */
    @GET("manager/devices/device/{deviceId}/capability/{capabilityId}")
    suspend fun getCapabilityObject(
        @Path("deviceId")     deviceId: String,
        @Path("capabilityId") capabilityId: String
    ): Response<Any>

    /**
     * Retrieves the current user profile, including favorite devices.
     *
     * @public
     * @return [UserMeDto] mapped response or an error [Response].
     */
    @GET("manager/users/user/me")
    suspend fun getUserMe(): Response<UserMeDto>

    /**
     * Retrieves all dashboards as structured data from the Wear OS companion app.
     *
     * @public
     * @return Map of dashboard ID -> [DashboardDto].
     */
    @GET("app/com.dimapp.wearos/dashboards")
    suspend fun getDashboards(): Response<Map<String, DashboardDto>>

    /**
     * Retrieves a single device by its UUID.
     *
     * @public
     * @param deviceId UUID of the device to fetch.
     * @return [DeviceDto] wrapped in a Retrofit [Response].
     * @example
     * val response = apiService.getDevice("uuid-1234")
     */
    @GET("manager/devices/device/{deviceId}")
    suspend fun getDevice(
        @Path("deviceId") deviceId: String
    ): Response<DeviceDto>

    /**
     * Retrieves all Insights logs definitions.
     *
     * @public
     * @return [Response] containing the raw [com.google.gson.JsonElement] payload.
     * @example
     * val logs = apiService.getInsightsLogs()
     */
    @GET("manager/insights/log")
    suspend fun getInsightsLogs(): Response<com.google.gson.JsonElement>

    /**
     * Retrieves historical entries for a given Insights log ID and URI.
     *
     * @public
     * @param uri URI of the log owner (e.g. "homey:device:xxx").
     * @param logId ID of the insight log (e.g. "onoff", "measure_temperature").
     * @param resolution Sampling resolution window (e.g. "last7Days", "today", "last6Hours").
     * @return [Response] containing the raw [com.google.gson.JsonElement] payload.
     * @example
     * val entries = apiService.getInsightEntries("homey:device:1234", "onoff", "last7Days")
     */
    @GET("manager/insights/log/{uri}/{logId}/entry")
    suspend fun getInsightEntries(
        @Path(value = "uri", encoded = true) uri: String,
        @Path(value = "logId", encoded = true) logId: String,
        @Query("resolution") resolution: String = "last7Days"
    ): Response<com.google.gson.JsonElement>
}
