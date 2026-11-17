package com.dimapp.android.homeywatch.tile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import coil.Coil
import coil.decode.SvgDecoder
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.dimapp.android.homeywatch.api.SanitizedHttpLogger
import com.dimapp.android.homeywatch.core.DependencyManager
import com.dimapp.android.homeywatch.repository.models.HomeyDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Helper responsible for loading, rasterizing, caching, and preloading Homey device icons
 * for display inside Wear OS ProtoLayout Tiles and app components.
 * Aligned with the icon resolution and downloading architecture of HomeyAutomotive.
 */
object TileIconHelper {

    private const val _TAG = "TileIconHelper"

    /** Default resolution for Tile button icons (48x48 px is crisp and compact). */
    const val TILE_ICON_SIZE_PX = 48

    /** In-memory cache: iconUrl -> Raw ARGB_8888 byte buffer */
    private val _iconBytesCache = ConcurrentHashMap<String, ByteArray>()

    /** Cooldown tracker for failed icon downloads to prevent endless retries: fullUrl -> failureEpochMillis */
    private val _failedUrlTimestamps = ConcurrentHashMap<String, Long>()

    /** Cooldown duration for failed icon URLs (6 hours). */
    private const val _NEGATIVE_CACHE_DURATION_MS = 6 * 3600 * 1000L

    /** Set of in-flight download URLs to prevent redundant parallel HTTP requests. */
    private val _inFlightUrls = ConcurrentHashMap.newKeySet<String>()

    /** Per-cache-key locks preventing concurrent duplicate network downloads and disk writes. */
    private val _keyMutexes = ConcurrentHashMap<String, Mutex>()

    /** Shared IO coroutine scope for asynchronous icon preloading tasks. */
    private val _preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Resolves and creates the persistent disk directory for tile icon raw ARGB buffers.
     * Automatically cleans up legacy cache directory.
     *
     * Private helper method.
     *
     * @private
     * @param context Application context.
     * @return File directory for persisted tile icons.
     */
    private fun _getCacheDirectory(context: Context): File {
        val legacyDir = File(context.applicationContext.filesDir, "tile_icons")
        if (legacyDir.exists()) {
            try {
                legacyDir.deleteRecursively()
            } catch (e: Exception) {
                Log.w(_TAG, "[TileIconHelper:_getCacheDirectory] Error deleting legacy cache: ${e.message}")
            }
        }
        val dir = File(context.applicationContext.filesDir, "tile_icons_v2")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Generates a versioned cache key for device icons.
     *
     * Private helper method.
     *
     * @private
     * @param iconUrl Target icon URL.
     * @param sizePx Target size in pixels.
     * @return Cache identifier string.
     */
    private fun _getCacheKey(iconUrl: String, sizePx: Int): String {
        return "${iconUrl}_${sizePx}_white_v2"
    }

    /**
     * Returns the persistent cache file corresponding to a specific cache key.
     *
     * Private helper method.
     *
     * @private
     * @param context Application context.
     * @param cacheKey Cache identifier string.
     * @return Cache File handle.
     */
    private fun _getCacheFile(context: Context, cacheKey: String): File {
        val safeFileName = _hashKey(cacheKey) + ".bin"
        return File(_getCacheDirectory(context), safeFileName)
    }

    /**
     * Generates an MD5 hex string for a given cache key to create safe filenames.
     *
     * Private helper method.
     *
     * @private
     * @param key Raw cache key.
     * @return MD5 hash string.
     */
    private fun _hashKey(key: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(key.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            key.hashCode().toString()
        }
    }

    /**
     * Resolves an icon URL or relative path into an absolute HTTPS URL.
     * Aligned with HomeyAutomotive's IconFetcher resolution.
     *
     * Private helper method.
     *
     * @private
     * @param urlOrPath Absolute URL (e.g. from CDN) or relative path from Homey driver.
     * @param hubId Target Homey hub ID used to construct the Athom Connect URL.
     * @return Fully qualified absolute URL.
     */
    private fun _resolveAbsoluteUrl(urlOrPath: String, hubId: String?): String {
        return if (urlOrPath.startsWith("http")) {
            urlOrPath
        } else if (!hubId.isNullOrBlank()) {
            val normalizedPath = if (urlOrPath.startsWith("/")) urlOrPath else "/$urlOrPath"
            "https://$hubId.connect.athom.com$normalizedPath"
        } else {
            urlOrPath
        }
    }

    /**
     * Retrieves cached icon bytes or loads and rasterizes the icon from [iconUrl].
     * Persists rasterized ARGB bytes to disk so the Wear OS Tile can access them synchronously.
     *
     * Public method.
     *
     * @public
     * @param context Application context.
     * @param iconUrl Target HTTP/HTTPS URL or relative path of the device icon (SVG or raster).
     * @param sizePx Target width/height in pixels.
     * @param hubId Optional target hub ID for relative path resolution.
     * @return Raw ARGB_8888 pixel byte array, or null if loading failed.
     * @example
     * val bytes = TileIconHelper.loadDeviceIconBytes(context, dev.iconUrl)
     */
    suspend fun loadDeviceIconBytes(
        context: Context,
        iconUrl: String?,
        sizePx: Int = TILE_ICON_SIZE_PX,
        hubId: String? = null
    ): ByteArray? {
        if (iconUrl.isNullOrBlank()) return null

        val appCtx = context.applicationContext
        val cacheKey = _getCacheKey(iconUrl, sizePx)

        // 1. Fast in-memory check
        _iconBytesCache[cacheKey]?.let { return it }

        // 2. Persistent disk check
        try {
            val diskFile = _getCacheFile(appCtx, cacheKey)
            if (diskFile.exists() && diskFile.length() > 0) {
                val bytes = diskFile.readBytes()
                _iconBytesCache[cacheKey] = bytes
                return bytes
            }
        } catch (e: Exception) {
            Log.w(_TAG, "[TileIconHelper:loadDeviceIconBytes] Error checking disk cache: ${e.message}")
        }

        return withContext(Dispatchers.IO) {
            val keyMutex = _keyMutexes.computeIfAbsent(cacheKey) { Mutex() }
            keyMutex.withLock {
                // Re-check caches inside lock in case another coroutine completed the fetch while waiting
                _iconBytesCache[cacheKey]?.let { return@withLock it }
                try {
                    val diskFile = _getCacheFile(appCtx, cacheKey)
                    if (diskFile.exists() && diskFile.length() > 0) {
                        val bytes = diskFile.readBytes()
                        _iconBytesCache[cacheKey] = bytes
                        return@withLock bytes
                    }
                } catch (e: Exception) {
                    Log.w(_TAG, "[TileIconHelper:loadDeviceIconBytes] Error re-checking disk cache: ${e.message}")
                }

                try {
                    val storage = DependencyManager.getTokenStorage(appCtx)
                    val effectiveHubId = hubId ?: storage.getSelectedHomeyId()
                    val fullUrl = _resolveAbsoluteUrl(iconUrl, effectiveHubId)

                    // Check negative cache cooldown to avoid endless rapid retries on 404 or bad icon URLs
                    val lastFailure = _failedUrlTimestamps[fullUrl]
                    if (lastFailure != null && System.currentTimeMillis() - lastFailure < _NEGATIVE_CACHE_DURATION_MS) {
                        Log.d(_TAG, "[TileIconHelper:loadDeviceIconBytes] Skipping failed icon due to negative cache cooldown: ${SanitizedHttpLogger.sanitize(fullUrl)}")
                        return@withLock null
                    }

                    val loader = Coil.imageLoader(appCtx)
                    val request = ImageRequest.Builder(appCtx)
                        .data(fullUrl)
                        .decoderFactory(SvgDecoder.Factory())
                        .size(sizePx, sizePx)
                        .allowHardware(false)
                        .build()

                    val response = loader.execute(request)
                    val result = (response as? SuccessResult)?.drawable
                    if (result == null) {
                        _failedUrlTimestamps[fullUrl] = System.currentTimeMillis()
                        val err = (response as? ErrorResult)?.throwable
                        Log.w(_TAG, "[TileIconHelper:loadDeviceIconBytes] Failed to load icon from: ${SanitizedHttpLogger.sanitize(fullUrl)} (reason: ${err?.message})")
                        return@withLock null
                    }

                    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    result.setBounds(0, 0, sizePx, sizePx)
                    result.draw(canvas)

                    // Convert all rendered non-transparent pixels to crisp pure white (#FFFFFF) preserving alpha
                    val pixels = IntArray(sizePx * sizePx)
                    bitmap.getPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
                    for (i in pixels.indices) {
                        val pixel = pixels[i]
                        val alpha = (pixel ushr 24) and 0xFF
                        if (alpha > 0) {
                            pixels[i] = (alpha shl 24) or 0x00FFFFFF
                        }
                    }
                    bitmap.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)

                    val byteBuffer = ByteBuffer.allocate(bitmap.byteCount)
                    bitmap.copyPixelsToBuffer(byteBuffer)
                    val byteArray = byteBuffer.array()

                    // Save to memory cache and clear any previous failure record
                    _iconBytesCache[cacheKey] = byteArray
                    _failedUrlTimestamps.remove(fullUrl)

                    // Save to persistent disk cache
                    try {
                        val file = _getCacheFile(appCtx, cacheKey)
                        file.writeBytes(byteArray)
                        Log.d(_TAG, "[TileIconHelper:loadDeviceIconBytes] Persisted ${byteArray.size} bytes to disk for icon: ${SanitizedHttpLogger.sanitize(fullUrl)}")
                    } catch (e: Exception) {
                        Log.w(_TAG, "[TileIconHelper:loadDeviceIconBytes] Error persisting icon to disk: ${e.message}")
                    }

                    byteArray
                } catch (e: Exception) {
                    try {
                        val storage = DependencyManager.getTokenStorage(appCtx)
                        val effectiveHubId = hubId ?: storage.getSelectedHomeyId()
                        val fullUrl = _resolveAbsoluteUrl(iconUrl, effectiveHubId)
                        _failedUrlTimestamps[fullUrl] = System.currentTimeMillis()
                    } catch (_: Exception) {}
                    Log.e(_TAG, "[TileIconHelper:loadDeviceIconBytes] Error rasterizing icon ($iconUrl): ${e.message}")
                    null
                }
            }
        }
    }

    /**
     * Checks if icon bytes are already available in memory cache or persisted on disk for [iconUrl].
     * Never performs network requests or blocks the thread.
     *
     * Public method.
     *
     * @public
     * @param context Application context for reading persistent disk cache.
     * @param iconUrl Target icon URL.
     * @param sizePx Target size in pixels.
     * @return Cached byte array or null.
     * @example
     * ```kotlin
     * val bytes = TileIconHelper.getCachedIconBytes(context, device.iconUrl)
     * ```
     */
    fun getCachedIconBytes(context: Context? = null, iconUrl: String?, sizePx: Int = TILE_ICON_SIZE_PX): ByteArray? {
        if (iconUrl.isNullOrBlank()) return null
        val cacheKey = _getCacheKey(iconUrl, sizePx)

        // 1. Fast in-memory check
        _iconBytesCache[cacheKey]?.let { return it }

        // 2. Persistent disk check
        if (context != null) {
            try {
                val file = _getCacheFile(context, cacheKey)
                if (file.exists() && file.length() > 0) {
                    val bytes = file.readBytes()
                    _iconBytesCache[cacheKey] = bytes
                    return bytes
                }
            } catch (e: Exception) {
                Log.w(_TAG, "[TileIconHelper:getCachedIconBytes] Error reading icon from disk: ${e.message}")
            }
        }

        return null
    }

    /**
     * Preloads and rasterizes icons for a list of devices asynchronously in the background.
     * Icons are saved to persistent disk so Wear OS Tiles and the app always have them available offline.
     *
     * Public method.
     *
     * @public
     * @param context Application context.
     * @param devices List of devices to preload.
     * @param targetHubId Target Homey hub ID (or null for current active hub).
     * @example
     * ```kotlin
     * TileIconHelper.preloadDeviceIcons(context, devices)
     * ```
     */
    fun preloadDeviceIcons(
        context: Context,
        devices: List<HomeyDevice>,
        targetHubId: String? = null
    ) {
        val appCtx = context.applicationContext
        val storage = DependencyManager.getTokenStorage(appCtx)
        val hubId = targetHubId ?: storage.getSelectedHomeyId()

        // Filter devices with valid icon URLs, prioritizing favorites first
        val sortedCandidates = devices
            .filter { !it.iconUrl.isNullOrBlank() }
            .sortedByDescending { it.isFavorite }
        if (sortedCandidates.isEmpty()) return

        _preloadScope.launch {
            var anyLoaded = false
            for (dev in sortedCandidates) {
                val url = dev.iconUrl ?: continue
                val cacheKey = _getCacheKey(url, TILE_ICON_SIZE_PX)
                if (_iconBytesCache.containsKey(cacheKey)) continue

                // Check persistent disk cache before attempting network download
                val diskFile = _getCacheFile(appCtx, cacheKey)
                if (diskFile.exists() && diskFile.length() > 0) {
                    try {
                        val diskBytes = diskFile.readBytes()
                        _iconBytesCache[cacheKey] = diskBytes
                        continue
                    } catch (e: Exception) {
                        Log.w(_TAG, "[TileIconHelper:preloadDeviceIcons] Error reading disk cache for ${dev.name}: ${e.message}")
                    }
                }

                if (_inFlightUrls.add(url)) {
                    try {
                        val bytes = loadDeviceIconBytes(appCtx, url, sizePx = TILE_ICON_SIZE_PX, hubId = hubId)
                        if (bytes != null) {
                            anyLoaded = true
                        }
                    } catch (e: Exception) {
                        Log.e(_TAG, "[TileIconHelper:preloadDeviceIcons] Preload error for ${dev.name}: ${e.message}")
                    } finally {
                        _inFlightUrls.remove(url)
                    }
                }
            }

            if (anyLoaded) {
                _notifyTileSlotsUpdated(appCtx)
            }
        }
    }

    /**
     * Requests update on all active Tile slots when icons have finished preloading.
     *
     * Private method.
     *
     * @private
     * @param context Application context.
     */
    private fun _notifyTileSlotsUpdated(context: Context) {
        try {
            TileSlotManager.updateAllSlots(context)
            Log.d(_TAG, "[TileIconHelper:_notifyTileSlotsUpdated] Requested Tile updates after preloading icons.")
        } catch (e: Exception) {
            Log.w(_TAG, "[TileIconHelper:_notifyTileSlotsUpdated] Could not trigger Tile update: ${e.message}")
        }
    }
}
