package com.dimapp.android.homeywatch.api

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Factory for creating [HomeyCompanionApiService] Retrofit instances.
 *
 * Used for public OAuth2 authentication endpoints exposed by the Homey Wear OS Companion App:
 * `https://{homeyId}.connect.athom.com/api/app/com.dimapp.wearos/`
 *
 * @public
 * @example
 * ```kotlin
 * val service = HomeyCompanionApiClient.create(
 *     homeyId = storage.getSelectedHomeyId()!!,
 *     debug = BuildConfig.DEBUG
 * )
 * val response = service.startAuth(StartAuthRequest(sessionId))
 * ```
 */
object HomeyCompanionApiClient {

    private const val TIMEOUT_SECONDS = 30L
    private const val COMPANION_APP_ID = "com.dimapp.wearos"

    private var _sharedOkHttpClient: OkHttpClient? = null
    private val _serviceCache = mutableMapOf<String, HomeyCompanionApiService>()

    /**
     * Returns (or creates and caches) a [HomeyCompanionApiService] for the given [homeyId].
     *
     * @public
     * @param homeyId The 24-character hex ID of the target Homey Pro.
     * @param debug   If `true`, attaches a sanitized HTTP logging interceptor on first creation.
     * @return Configured [HomeyCompanionApiService] instance.
     * @example
     * ```kotlin
     * val service = HomeyCompanionApiClient.create(homeyId = "abc123", debug = BuildConfig.DEBUG)
     * ```
     */
    fun create(homeyId: String, debug: Boolean = false): HomeyCompanionApiService {
        _serviceCache[homeyId]?.let { return it }

        val baseUrl = "https://${homeyId}.connect.athom.com/api/app/$COMPANION_APP_ID/"
        val client = _getOrBuildOkHttpClient(debug)

        val service = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(HomeyCompanionApiService::class.java)

        _serviceCache[homeyId] = service
        return service
    }

    /**
     * Returns the shared [OkHttpClient], creating it on first use to maximize TCP connection reuse.
     *
     * @private
     * @param debug If `true`, enables sanitized HTTP logging on initial creation.
     * @return The shared [OkHttpClient] instance.
     */
    private fun _getOrBuildOkHttpClient(debug: Boolean): OkHttpClient {
        return _sharedOkHttpClient ?: _buildOkHttpClient(debug).also { _sharedOkHttpClient = it }
    }

    /**
     * Builds the [OkHttpClient] with timeouts and sanitized HTTP error logging.
     *
     * @private
     * @param debug If `true`, adds a BASIC-level [HttpLoggingInterceptor] using [SanitizedHttpLogger].
     * @return Configured [OkHttpClient].
     */
    private fun _buildOkHttpClient(debug: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                if (!response.isSuccessful) {
                    val peekBody = try { response.peekBody(2048).string() } catch (_: Exception) { "<unavailable>" }
                    Log.e(
                        "HomeyCompanionApiClient",
                        "[HomeyCompanionApiClient:intercept] [${request.method}] ${request.url.encodedPath} failed with HTTP ${response.code}: ${SanitizedHttpLogger.sanitize(peekBody)}"
                    )
                }
                response
            }

        if (debug) {
            val loggingInterceptor = HttpLoggingInterceptor(SanitizedHttpLogger("OkHttpClient.Companion")).apply {
                level = HttpLoggingInterceptor.Level.BASIC
                redactHeader("Authorization")
                redactHeader("x-homey-id")
            }
            builder.addInterceptor(loggingInterceptor)
        }

        return builder.build()
    }
}
