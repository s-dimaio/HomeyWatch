package com.dimapp.android.homeywatch.api

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

// ── Request / Response DTOs ───────────────────────────────────────────────────

/**
 * Request body for `POST /auth/start`.
 *
 * @property sessionId A 64-character hex string generated with [java.security.SecureRandom]
 *                     by the watch app. Used as a unique, unguessable session identifier.
 */
@Keep
data class StartAuthRequest(
    @field:SerializedName("sessionId") val sessionId: String
)

/**
 * Response body from `POST /auth/start`.
 *
 * @property authUrl The Athom authorization URL to be launched on the paired phone or displayed.
 */
@Keep
data class StartAuthResponse(
    @field:SerializedName("authUrl") val authUrl: String
)

/**
 * Response body from `GET /auth/poll`.
 *
 * @property status Current session state: `"pending"`, `"complete"`, or `"expired"`.
 * @property token  Token payload, present only when [status] is `"complete"`.
 */
@Keep
data class PollAuthResponse(
    @field:SerializedName("status") val status: String,
    @field:SerializedName("token") val token: CompanionToken? = null
)

/**
 * Token payload delivered by the Homey Wear OS Companion App after successful authorization.
 *
 * @property session_token       The owner API token for the Hub.
 * @property homey_id            The Hub ID.
 * @property homey_name          The Hub display name.
 * @property homey_api_url       The Hub API URL.
 * @property athom_refresh_token The refresh secret for silent token rotation.
 * @property user                The user profile payload.
 */
@Keep
data class CompanionToken(
    @field:SerializedName("session_token") val session_token: String,
    @field:SerializedName("homey_id") val homey_id: String?,
    @field:SerializedName("homey_name") val homey_name: String?,
    @field:SerializedName("homey_api_url") val homey_api_url: String?,
    @field:SerializedName("athom_refresh_token") val athom_refresh_token: String?,
    @field:SerializedName("user") val user: UserPayload?
)

/**
 * User profile payload from the companion app.
 */
@Keep
data class UserPayload(
    @field:SerializedName("id") val id: String?,
    @field:SerializedName("name") val name: String?,
    @field:SerializedName("email") val email: String?
)

/**
 * Request body for `POST /auth/refresh`.
 *
 * @property athom_refresh_token The refresh token obtained previously from the Athom Cloud.
 */
@Keep
data class RefreshAuthRequest(
    @field:SerializedName("athom_refresh_token") val athom_refresh_token: String
)

/**
 * Response body from `POST /auth/refresh`.
 *
 * @property session_token       The new owner API token for the Hub.
 * @property athom_refresh_token The new refresh token to use for the next rotation.
 */
@Keep
data class RefreshAuthResponse(
    @field:SerializedName("session_token") val session_token: String,
    @field:SerializedName("athom_refresh_token") val athom_refresh_token: String
)

// ── Retrofit Service ──────────────────────────────────────────────────────────

/**
 * Retrofit interface for the public endpoints exposed by the Homey Wear OS Companion App.
 *
 * Base URL: `https://{homeyId}.connect.athom.com/api/app/com.dimapp.wearos/`
 */
interface HomeyCompanionApiService {

    /**
     * Starts a new OAuth2 auth session on the Homey Companion App.
     *
     * @public
     * @param body [StartAuthRequest] containing the watch-generated session ID.
     * @return [StartAuthResponse] with the authorization URL.
     */
    @POST("auth/start")
    suspend fun startAuth(@Body body: StartAuthRequest): StartAuthResponse

    /**
     * Polls the Homey Companion App for the current status of an auth session.
     *
     * @public
     * @param session The session identifier matching a previous [startAuth] call.
     * @return [PollAuthResponse] with the current session state.
     */
    @GET("auth/poll")
    suspend fun pollAuth(@Query("session") session: String): PollAuthResponse

    /**
     * Attempts to refresh the Hub session token using the stored refresh secret.
     *
     * @public
     * @param body [RefreshAuthRequest] containing the valid refresh secret.
     * @return [RefreshAuthResponse] with the new session_token and new refresh secret.
     */
    @POST("auth/refresh")
    suspend fun refreshAuth(@Body body: RefreshAuthRequest): RefreshAuthResponse
}
