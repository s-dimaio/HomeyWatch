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
            val loggingInterceptor = HttpLoggingInterceptor(SanitizedHttpLogger("OkHttpClient")).apply {
                level = HttpLoggingInterceptor.Level.BASIC
                redactHeader("Authorization")
                redactHeader("x-homey-id")
            }
            builder.addInterceptor(loggingInterceptor)
        }

        return builder.build()
    }
}

/**
 * OkHttp [Interceptor] that automatically injects the OAuth2 Bearer token
 * into every outgoing request, refreshing it silently if expired.
 *
 * For Companion App endpoints (`app/com.dimapp.wearos`), also attaches the raw session token
 * as a `?token=` query parameter because Homey `ManagerApiLocal` does not forward HTTP headers
 * to `api.js` handlers.
 *
 * @property authRepo [HomeyAuthRepository] used to obtain a valid Bearer token.
 */
class TokenInjectorInterceptor(
    private val authRepo: HomeyAuthRepository
) : Interceptor {

    /**
     * Intercepts each request, injects the Bearer token (and `?token=` for companion endpoints),
     * and retries once upon HTTP 401 after silent token rotation.
     *
     * @public
     * @param chain The OkHttp [Interceptor.Chain].
     * @return The [Response] from the upstream server.
     */
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val hubId = if (originalRequest.url.host.contains(".connect.athom.com")) {
            originalRequest.url.host.substringBefore(".connect.athom.com")
        } else {
            null
        }

        val bearer = runBlocking { authRepo.getValidDelegationToken(hubId) }

        val request = if (bearer != null) {
            val reqBuilder = originalRequest.newBuilder()
            if (originalRequest.header("Authorization") == null) {
                reqBuilder.header("Authorization", bearer)
            }
            if (originalRequest.url.encodedPath.contains("app/com.dimapp.wearos")) {
                val rawToken = bearer.removePrefix("Bearer ").trim()
                val urlWithToken = originalRequest.url.newBuilder()
                    .setQueryParameter("token", rawToken)
                    .build()
                reqBuilder.url(urlWithToken)
            }
            reqBuilder.build()
        } else {
            originalRequest
        }

        val response = chain.proceed(request)

        // If the Homey Hub returns 401 (token expired), attempt a silent refresh
        if (response.code == 401) {
            val isRefreshed = runBlocking { authRepo.refreshHubSessionToken(bearer, hubId) }
            if (isRefreshed) {
                val newBearer = runBlocking { authRepo.getValidDelegationToken(hubId) }
                if (newBearer != null) {
                    response.close()

                    val newReqBuilder = originalRequest.newBuilder()
                        .header("Authorization", newBearer)
                    if (originalRequest.url.encodedPath.contains("app/com.dimapp.wearos")) {
                        val newRawToken = newBearer.removePrefix("Bearer ").trim()
                        val urlWithToken = originalRequest.url.newBuilder()
                            .setQueryParameter("token", newRawToken)
                            .build()
                        newReqBuilder.url(urlWithToken)
                    }
                    return chain.proceed(newReqBuilder.build())
                }
            }
        }

        return response
    }
}
