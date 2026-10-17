package com.dimapp.android.homeywatch

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.dimapp.android.homeywatch.core.DependencyManager
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val TAG = "HomeyWatchApp"

/**
 * Main application class for HomeyWatch Wear OS app.
 */
class HomeyWatchApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "[HomeyWatchApp:onCreate] Initializing HomeyWatch application.")
        // Pre-warm DependencyManager
        DependencyManager.getTokenStorage(this)
        // Synchronize Wear OS Tile slots with configured hubs
        com.dimapp.android.homeywatch.tile.TileSlotManager.syncSlots(this)
    }

    override fun newImageLoader(): ImageLoader {
        val storage = DependencyManager.getTokenStorage(this)
        val okHttpClientBuilder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val requestBuilder = request.newBuilder()
                val host = request.url.host
                val token = if (host.endsWith("connect.athom.com")) {
                    val hubId = host.removeSuffix(".connect.athom.com")
                    storage.getHubToken(hubId)?.sessionToken ?: storage.getHubSessionToken()
                } else {
                    null
                }

                // Add token only for Athom Connect URLs (local/remote API)
                if (!token.isNullOrBlank()) {
                    requestBuilder.addHeader("Authorization", "Bearer $token")
                }
                chain.proceed(requestBuilder.build())
            }

        _configurePermissiveSsl(okHttpClientBuilder)
        val okHttpClient = okHttpClientBuilder.build()

        return ImageLoader.Builder(this)
            .components {
                add(SvgDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(10 * 1024 * 1024)
                    .build()
            }
            .okHttpClient(okHttpClient)
            .crossfade(true)
            .build()
    }

    /**
     * Configures a permissive SSL context for image loading to prevent "Chain validation failed"
     * errors on Wear OS emulators/devices with missing or expired root CA certificates.
     *
     * Private helper method.
     *
     * @private
     * @param builder OkHttpClient builder to configure.
     */
    private fun _configurePermissiveSsl(builder: OkHttpClient.Builder) {
        try {
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
            )
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            Log.e(TAG, "[HomeyWatchApp:_configurePermissiveSsl] Error setting permissive SSL: ${e.message}")
        }
    }
}
