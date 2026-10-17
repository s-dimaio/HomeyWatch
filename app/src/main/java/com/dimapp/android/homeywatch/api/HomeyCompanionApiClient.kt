package com.dimapp.android.homeywatch.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Factory for creating [HomeyCompanionApiService] Retrofit instances.
 *
 * Used exclusively for public unauthenticated endpoints on the Homey Wear OS Companion App:
 * `https://{homeyId}.connect.athom.com/api/app/com.dimapp.wearos/`
 */
object HomeyCompanionApiClient {

    private const val TIMEOUT_SECONDS = 15L

    /**
     * Creates a new [HomeyCompanionApiService] for the given [homeyId].
     *
     * @public
     * @param homeyId The 24-character hex ID of the target Homey Pro.
     * @param debug   If `true`, attaches an HTTP logging interceptor.
     * @return Configured [HomeyCompanionApiService] instance.
     */
    fun create(homeyId: String, debug: Boolean = false): HomeyCompanionApiService {
        val baseUrl = "https://${homeyId}.connect.athom.com/api/app/com.dimapp.wearos/"
        return _buildRetrofit(baseUrl, debug).create(HomeyCompanionApiService::class.java)
    }

    /**
     * Internal factory building the Retrofit instance with standard timeouts.
     *
     * @private
     * @param baseUrl Base URL including trailing slash.
     * @param debug   Whether to attach basic HTTP logging.
     * @return Configured Retrofit instance.
     */
    private fun _buildRetrofit(baseUrl: String, debug: Boolean): Retrofit {
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        if (debug) {
            clientBuilder.addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
        }

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(clientBuilder.build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
}
