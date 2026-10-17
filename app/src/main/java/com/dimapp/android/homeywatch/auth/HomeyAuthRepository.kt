package com.dimapp.android.homeywatch.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.dimapp.android.homeywatch.BuildConfig
import com.dimapp.android.homeywatch.api.CompanionToken
import com.dimapp.android.homeywatch.api.HomeyCompanionApiClient
import com.dimapp.android.homeywatch.api.RefreshAuthRequest
import com.dimapp.android.homeywatch.api.StartAuthRequest
import com.dimapp.android.homeywatch.storage.TokenStorage
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "HomeyAuthRepository"

/**
 * Result of a companion authentication polling session.
 */
sealed class PollResult {
    object Success : PollResult()
    object Pending : PollResult()
    object Expired : PollResult()
    object Error : PollResult()
    data class NetworkError(val message: String) : PollResult()
}

/**
 * Repository orchestrating OAuth2 Authorization Code flow via the Homey Wear OS Companion App.
 *
 * @property context Application context.
 * @property storage Secure preferences storage.
 */
class HomeyAuthRepository(
    private val context: Context,
    private val storage: TokenStorage
) {

    companion object {
        private val globalRefreshMutex = Mutex()
    }

    // ── Public Methods ─────────────────────────────────────────────────────────

    /**
     * Retrieves the Hub session token formatted as `"Bearer <token>"`.
     *
     * @public
     * @return Valid Bearer token header string, or `null`.
     */
    suspend fun getValidDelegationToken(): String? {
        val sessionToken = storage.getHubSessionToken()
        if (sessionToken.isNullOrBlank()) {
            Log.w(TAG, "[HomeyAuthRepository:getValidDelegationToken] No Hub session token found.")
            return null
        }
        return "Bearer $sessionToken"
    }

    /**
     * Silently refreshes the Hub session token using the Companion app's `/auth/refresh` endpoint.
     *
     * @public
     * @param failedToken The token that failed with HTTP 401.
     * @return `true` if refresh succeeded, `false` otherwise.
     */
    suspend fun refreshHubSessionToken(failedToken: String? = null): Boolean = globalRefreshMutex.withLock {
        val currentTokenString = getValidDelegationToken()
        if (failedToken != null && failedToken != currentTokenString && currentTokenString != null) {
            Log.i(TAG, "[HomeyAuthRepository:refreshHubSessionToken] Token was already refreshed by another concurrent request.")
            return@withLock true
        }

        val homeyId = storage.getSelectedHomeyId()
        val refreshSecret = storage.getHubRefreshSecret()

        if (homeyId.isNullOrBlank() || refreshSecret.isNullOrBlank()) {
            Log.w(TAG, "[HomeyAuthRepository:refreshHubSessionToken] Cannot refresh: missing homeyId or refreshSecret.")
            return@withLock false
        }

        return@withLock try {
            val service = HomeyCompanionApiClient.create(homeyId, BuildConfig.DEBUG)
            val response = service.refreshAuth(RefreshAuthRequest(refreshSecret))

            if (!response.session_token.isNullOrBlank() && !response.athom_refresh_token.isNullOrBlank()) {
                storage.updateActiveHubTokens(response.session_token, response.athom_refresh_token)
                Log.i(TAG, "[HomeyAuthRepository:refreshHubSessionToken] Hub session token refreshed successfully.")
                true
            } else {
                Log.e(TAG, "[HomeyAuthRepository:refreshHubSessionToken] Invalid refresh response payload.")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "[HomeyAuthRepository:refreshHubSessionToken] Refresh failed: ${e.message}")
            false
        }
    }

    /**
     * Checks if the user is authenticated (access token exists or demo mode).
     *
     * @public
     * @return `true` if configured or in demo mode.
     */
    fun isAuthenticated(): Boolean = storage.isConfigured() || storage.isDemoMode()

    /**
     * Returns `true` if an active hub ID is selected.
     *
     * @public
     */
    fun hasSelectedHomey(): Boolean = !storage.getSelectedHomeyId().isNullOrBlank()

    /**
     * Starts a new auth session on the Homey Wear OS Companion App.
     *
     * @public
     * @param homeyId   Homey ID.
     * @param sessionId Random 64-char hex session string.
     * @return Auth URL or null.
     */
    suspend fun startCompanionAuth(homeyId: String, sessionId: String): String? {
        return try {
            val service = HomeyCompanionApiClient.create(homeyId, BuildConfig.DEBUG)
            val response = service.startAuth(StartAuthRequest(sessionId))
            Log.d(TAG, "[HomeyAuthRepository:startCompanionAuth] Auth session started.")
            response.authUrl
        } catch (e: Exception) {
            Log.e(TAG, "[HomeyAuthRepository:startCompanionAuth] Failed: ${e.message}")
            null
        }
    }

    /**
     * Opens the Athom OAuth authorization page directly on the user's paired smartphone.
     *
     * @public
     * @param authUrl The full Athom OAuth2 authorization URL.
     * @return `true` if the intent was successfully dispatched to the paired phone, `false` otherwise.
     */
    suspend fun openAuthOnPhone(authUrl: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            val remoteActivityHelper = RemoteActivityHelper(context)
            remoteActivityHelper.startRemoteActivity(intent).await()
            Log.d(TAG, "[HomeyAuthRepository:openAuthOnPhone] Remote activity dispatched to phone.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[HomeyAuthRepository:openAuthOnPhone] Remote activity dispatch failed: ${e.message}")
            false
        }
    }

    /**
     * Polls the companion app for auth session completion.
     *
     * @public
     * @param homeyId   Homey ID.
     * @param sessionId Unique session ID.
     * @return [PollResult] with status.
     */
    suspend fun pollCompanionAuth(homeyId: String, sessionId: String): PollResult {
        return try {
            val service = HomeyCompanionApiClient.create(homeyId, BuildConfig.DEBUG)
            val response = service.pollAuth(sessionId)

            when (response.status) {
                "complete" -> {
                    val token = response.token ?: run {
                        Log.e(TAG, "[HomeyAuthRepository:pollCompanionAuth] Poll complete but token is null.")
                        return PollResult.Error
                    }

                    if (token.homey_id == null || token.homey_api_url == null || token.athom_refresh_token == null) {
                        Log.e(TAG, "[HomeyAuthRepository:pollCompanionAuth] Incomplete token data received.")
                        return PollResult.Error
                    }

                    _saveAuthData(token)
                    Log.d(TAG, "[HomeyAuthRepository:pollCompanionAuth] Authentication complete for hub ${token.homey_id}")
                    PollResult.Success
                }
                "expired" -> {
                    Log.w(TAG, "[HomeyAuthRepository:pollCompanionAuth] Session expired on server.")
                    PollResult.Expired
                }
                "error" -> {
                    Log.e(TAG, "[HomeyAuthRepository:pollCompanionAuth] Session error reported by server.")
                    PollResult.Error
                }
                else -> {
                    PollResult.Pending
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "[HomeyAuthRepository:pollCompanionAuth] Network error: ${e.message}")
            PollResult.NetworkError(e.message ?: "Unknown network error")
        }
    }

    /**
     * Clears all stored tokens and hub selection.
     *
     * @public
     */
    fun logout() {
        storage.clearAllAuth()
        Log.d(TAG, "[HomeyAuthRepository:logout] All tokens and session state cleared.")
    }

    // -- Private Methods -------------------------------------------------------

    /**
     * Saves received auth data into encrypted preferences.
     *
     * @private
     * @param token Delivered token payload.
     */
    private fun _saveAuthData(token: CompanionToken) {
        val homeyId = token.homey_id ?: return
        val apiUrl = token.homey_api_url ?: return
        val sessionToken = token.session_token
        val refreshSecret = token.athom_refresh_token ?: return
        val resolvedName = token.homey_name ?: "Homey"

        storage.saveHubToken(
            homeyId = homeyId,
            name = resolvedName,
            sessionToken = sessionToken,
            refreshSecret = refreshSecret,
            apiUrl = apiUrl,
            saveAsActive = true
        )

        token.user?.let {
            storage.saveUserProfile(it.name)
        }

        token.location?.let { loc ->
            storage.saveHomeLocation(loc.latitude, loc.longitude, homeyId)
        }
    }
}
