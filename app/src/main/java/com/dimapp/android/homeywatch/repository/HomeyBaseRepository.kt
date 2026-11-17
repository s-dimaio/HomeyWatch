package com.dimapp.android.homeywatch.repository

import android.content.Context
import android.util.Log
import com.dimapp.android.homeywatch.api.HomeyApiClient
import com.dimapp.android.homeywatch.api.HomeyApiService
import com.dimapp.android.homeywatch.api.SanitizedHttpLogger
import com.dimapp.android.homeywatch.auth.HomeyAuthRepository
import com.dimapp.android.homeywatch.core.DependencyManager
import com.dimapp.android.homeywatch.storage.TokenStorage
import java.util.concurrent.ConcurrentHashMap

/**
 * Base abstract class for domain-specific Homey repositories.
 *
 * @property context   Application context.
 * @property storage   Shared instance of [TokenStorage].
 * @property authRepo  Shared instance of [HomeyAuthRepository].
 */
abstract class HomeyBaseRepository(
    protected val context: Context,
    protected val storage: TokenStorage,
    protected val authRepo: HomeyAuthRepository
) {

    private val _hubServicesCache = ConcurrentHashMap<String, Pair<String, HomeyApiService>>()

    /**
     * Retrieves the [HomeyApiService] for the currently active hub from [DependencyManager].
     *
     * @return Configured [HomeyApiService], or `null` if not configured.
     * @example
     * val service = _buildService() ?: return HomeyResult.Error("Not configured")
     */
    protected fun _buildService(): HomeyApiService? {
        return DependencyManager.getApiService(context)
    }

    /**
     * Resolves a [HomeyApiService] for the target [hubId], reusing the singleton
     * [okhttp3.OkHttpClient] from [DependencyManager] (including shared connection pooling,
     * dynamic hub-scoped token injection, and automatic 401 refresh + retry).
     *
     * Falls back to [_buildService] when [hubId] is `null`, blank, or matches the currently
     * selected active hub.
     *
     * @param hubId Optional target Homey hub ID for multi-hub routing.
     * @return Configured [HomeyApiService] sharing the singleton HTTP client, or `null` if unconfigured.
     * @example
     * val service = _buildServiceForHub(targetHubId) ?: return HomeyResult.Error("Not configured")
     */
    protected fun _buildServiceForHub(hubId: String? = null): HomeyApiService? {
        if (hubId.isNullOrBlank() || hubId == storage.getSelectedHomeyId()) {
            return _buildService()
        }

        val entry = storage.getHubToken(hubId)
        val rawApiUrl = entry?.apiUrl?.trim()
        if (rawApiUrl.isNullOrBlank() || entry.sessionToken.isNullOrBlank()) {
            Log.w(
                "HomeyBaseRepository",
                "[HomeyBaseRepository:_buildServiceForHub] Missing credentials or apiUrl for hub=${SanitizedHttpLogger.maskId(hubId)}"
            )
            return null
        }

        val normalizedUrl = if (rawApiUrl.endsWith("/")) rawApiUrl else "$rawApiUrl/"
        val cached = _hubServicesCache[hubId]
        if (cached != null && cached.first == normalizedUrl) {
            return cached.second
        }

        val sharedClient = DependencyManager.getOkHttpClient(context)
        val service = HomeyApiClient.createWithClient(normalizedUrl, sharedClient)
        _hubServicesCache[hubId] = normalizedUrl to service
        return service
    }
}
