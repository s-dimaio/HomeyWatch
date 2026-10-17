package com.dimapp.android.homeywatch.api

import com.dimapp.android.homeywatch.auth.HomeyAuthRepository
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Factory for creating [HomeyApiService] Retrofit instances.
 */
object HomeyApiClient {

    private const val TIMEOUT_SECONDS = 25L

    /**
     * Creates a new [HomeyApiService] using a shared [OkHttpClient].
     *
     * @public
     * @param baseUrl Full base URL including trailing slash.
     * @param client  Shared [OkHttpClient] instance.
     * @return A ready-to-use [HomeyApiService].
     */
    fun createWithClient(
        baseUrl: String,
        client: OkHttpClient
    ): HomeyApiService {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(HomeyApiService::class.java)
    }

    /**
     * Builds a shared [OkHttpClient] equipped with token injection and automatic 401 retry.
     *
     * @public
     * @param authRepo [HomeyAuthRepository] for the injector interceptor.
     * @param debug    Whether to enable logging.
     * @return Configured [OkHttpClient].
     */
    fun buildSharedOkHttpClient(
        authRepo: HomeyAuthRepository,
        debug: Boolean = false
    ): OkHttpClient {
        return _buildOkHttpClient(authRepo, debug)
    }

    /**
     * Creates a new [HomeyApiService] dedicated to a specific hub with an explicit Bearer token.
     *
     * @public
     * @param baseUrl Full base URL including trailing slash.
     * @param sessionToken Session token for the target hub.
     * @param debug Whether to enable logging.
     * @return A ready-to-use [HomeyApiService].
     */
    fun createForHub(
        baseUrl: String,
        sessionToken: String,
        debug: Boolean = false
    ): HomeyApiService {
        val client = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder()
                    .header("Authorization", "Bearer $sessionToken")
                    .build()
                chain.proceed(request)
            }

        if (debug) {
            client.addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
        }

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client.build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(HomeyApiService::class.java)
    }

    // ── Private Methods ────────────────────────────────────────────────────────

    /**
     * Internal factory building the [OkHttpClient].
     *
     * @private
     * @param authRepo [HomeyAuthRepository] provided to [TokenInjectorInterceptor].
     * @param debug    If `true`, enables BASIC-level HTTP logging.
     * @return Configured [OkHttpClient] instance.
     */
    private fun _buildOkHttpClient(authRepo: HomeyAuthRepository, debug: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(TokenInjectorInterceptor(authRepo))

        if (debug) {
            builder.addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
        }

        return builder.build()
    }
}

/**
 * OkHttp [Interceptor] that automatically injects the OAuth2 Bearer token
 * into every outgoing request, refreshing it silently if expired.
 *
 * @property authRepo [HomeyAuthRepository] used to obtain a valid Bearer token.
 */
class TokenInjectorInterceptor(
    private val authRepo: HomeyAuthRepository
) : Interceptor {

    /**
     * Intercepts each request, injects the Bearer token, and retries once upon HTTP 401.
     *
     * @public
     * @param chain The OkHttp [Interceptor.Chain].
     * @return The [Response] from the upstream server.
     */
    override fun intercept(chain: Interceptor.Chain): Response {
        val bearer = runBlocking { authRepo.getValidDelegationToken() }

        val request = if (bearer != null) {
            chain.request().newBuilder()
                .header("Authorization", bearer)
                .build()
        } else {
            chain.request()
        }

        val response = chain.proceed(request)

        // If the Homey Hub returns 401 (token expired), attempt a silent refresh
        if (response.code == 401) {
            val isRefreshed = runBlocking { authRepo.refreshHubSessionToken(bearer) }
            if (isRefreshed) {
                val newBearer = runBlocking { authRepo.getValidDelegationToken() }
                if (newBearer != null) {
                    response.close()

                    val newRequest = chain.request().newBuilder()
                        .header("Authorization", newBearer)
                        .build()
                    return chain.proceed(newRequest)
                }
            }
        }

        return response
    }
}
