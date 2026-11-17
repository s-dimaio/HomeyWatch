package com.dimapp.android.homeywatch.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.Keep
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

private const val _PREFS_FILE_NAME = "homey_secure_prefs"

// Active hub and multi-hub tokens map
private const val _KEY_HUB_TOKENS = "hub_tokens_json"
private const val _KEY_ACTIVE_HOMEY_ID = "active_homey_id"
private const val _KEY_ACTIVE_HOMEY_NAME = "active_homey_name"
private const val _KEY_ACTIVE_HOMEY_API_URL = "active_homey_api_url"

// OAuth2 session state
private const val _KEY_OAUTH_STATE = "oauth_state"

// User profile (display name only)
private const val _KEY_USER_NAME = "user_name"

// UI preferences
private const val _KEY_SYNC_INTERVAL     = "homey_sync_interval"

// Demo mode
private const val _KEY_IS_DEMO_MODE        = "is_demo_mode"

/**
 * Entry for a single authenticated Homey hub.
 */
@Keep
data class HubTokenEntry(
    @SerializedName("sessionToken") val sessionToken: String? = null,
    @SerializedName("refreshSecret") val refreshSecret: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("apiUrl") val apiUrl: String? = null,
    @SerializedName("isReauthRequired") val isReauthRequired: Boolean = false,
    @SerializedName("reauthReason") val reauthReason: String? = null
)

/**
 * Secure encrypted storage for all Homey authentication and configuration data.
 *
 * Wraps [EncryptedSharedPreferences] backed by AES-256-GCM for value encryption
 * and AES-256-SIV for key encryption, using the Android Keystore as the master key.
 *
 * Stores:
 * - OAuth2 access token, refresh token, and expiry timestamp
 * - Temporary OAuth2 `state` parameter for CSRF protection
 * - Selected Homey hub ID, name, and API URL
 * - UI preferences (sync interval)
 *
 * Usage:
 * ```
 * val storage = TokenStorage(context)
 * if (storage.isConfigured()) {
 *     val apiUrl = storage.getSelectedHomeyApiUrl()
 *     val token  = storage.getHubSessionToken()
 * }
 * ```
 *
 * @property context The [Context] used to build the encrypted preferences file.
 * @constructor Creates a new [TokenStorage] instance for the given [context].
 */
class TokenStorage(context: Context) {

    private val _context: Context = context

    /** Lazily initialised encrypted preferences instance. */
    private val _prefs: SharedPreferences by lazy { _buildEncryptedPrefs() }

    private val _gson = Gson()

    // ── Public Methods — OAuth2 State ────────────────────────────────────────

    /**
     * Saves the temporary OAuth2 `state` parameter used for CSRF protection.
     * Must be cleared (via [clearOAuthState]) after successful validation.
     *
     * @public
     * @param state Random CSRF state token generated for the authorization flow.
     * @example
     * ```kotlin
     * storage.saveOAuthState("csrf-token-123")
     * ```
     */
    fun saveOAuthState(state: String) {
        _prefs.edit().putString(_KEY_OAUTH_STATE, state).apply()
    }

    /**
     * Retrieves the saved OAuth2 `state` parameter.
     *
     * @public
     * @return Saved CSRF state string, or `null` if none is stored.
     */
    fun getOAuthState(): String? = _prefs.getString(_KEY_OAUTH_STATE, null)

    /**
     * Removes the temporary OAuth2 `state` parameter after successful validation.
     *
     * @public
     */
    fun clearOAuthState() {
        _prefs.edit().remove(_KEY_OAUTH_STATE).apply()
    }

    /**
     * Clears all authentication credentials, active hub selection, user profile,
     * demo mode flags, and Tile slot assignments.
     *
     * @public
     * @example
     * ```kotlin
     * storage.clearAllAuth()
     * ```
     */
    fun clearAllAuth() {
        _prefs.edit()
            .remove(_KEY_HUB_TOKENS)
            .remove(_KEY_ACTIVE_HOMEY_ID)
            .remove(_KEY_ACTIVE_HOMEY_NAME)
            .remove(_KEY_ACTIVE_HOMEY_API_URL)
            .remove(_KEY_OAUTH_STATE)
            .remove(_KEY_USER_NAME)
            .remove(_KEY_IS_DEMO_MODE)
            .apply {
                for (i in 1..4) {
                    remove("tile_slot_${i}_hub_id")
                    remove("tile_slot_${i}_hub_name")
                }
            }
            .apply()
    }

    // ── Public Methods — Multi-Hub Token Management ───────────────────────────────

    /**
     * Retrieves the map of all authenticated hubs indexed by Homey Hub ID.
     *
     * @public
     * @return Map from hub ID to validated [HubTokenEntry].
     * @example
     * ```kotlin
     * val hubs = storage.getAllHubTokens()
     * ```
     */
    fun getAllHubTokens(): Map<String, HubTokenEntry> {
        val json = _prefs.getString(_KEY_HUB_TOKENS, null) ?: return emptyMap()
        val type = object : TypeToken<Map<String, HubTokenEntry>>() {}.type
        return try {
            val map: Map<String, HubTokenEntry>? = _gson.fromJson(json, type)
            map?.filterValues { it.sessionToken != null && it.name != null && it.apiUrl != null } ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Retrieves the token entry for a specific hub.
     *
     * @public
     * @param homeyId Target Homey Hub ID.
     * @return Matching [HubTokenEntry] or `null` if not stored.
     */
    fun getHubToken(homeyId: String): HubTokenEntry? {
        return getAllHubTokens()[homeyId]
    }

    /**
     * Returns true if the specified hub has valid tokens stored.
     *
     * @public
     * @param homeyId Target Homey Hub ID.
     * @return `true` if credentials exist for [homeyId].
     */
    fun isHubAuthenticated(homeyId: String): Boolean {
        return getHubToken(homeyId) != null
    }

    /**
     * Removes a single hub's authentication data from storage.
     *
     * If the removed hub was the currently active one, automatically switches to the
     * first remaining hub in the map. If no hubs remain after removal, [clearAllAuth]
     * is called for a full reset.
     *
     * @public
     * @param homeyId The ID of the hub to disconnect.
     * @return `true` if at least one authenticated hub still exists after removal;
     *         `false` if all hubs have been removed (app must redirect to initial setup).
     * @example
     * ```
     * val hasMore = storage.removeHubToken(homeyId)
     * if (!hasMore) navigateToSetup() else invalidate()
     * ```
     */
    fun removeHubToken(homeyId: String): Boolean {
        val tokensMap = getAllHubTokens().toMutableMap()
        tokensMap.remove(homeyId)

        val editor = _prefs.edit()

        if (homeyId == "demo") {
            editor.remove(_KEY_IS_DEMO_MODE)
        }

        if (tokensMap.isEmpty()) {
            editor.apply()
            clearAllAuth()
            return false
        }

        val wasActive = getSelectedHomeyId() == homeyId
        editor.putString(_KEY_HUB_TOKENS, _gson.toJson(tokensMap))

        if (wasActive) {
            // Auto-switch to the first remaining hub
            val (newId, newEntry) = tokensMap.entries.first()
            editor
                .putString(_KEY_ACTIVE_HOMEY_ID, newId)
                .putString(_KEY_ACTIVE_HOMEY_NAME, newEntry.name)
                .putString(_KEY_ACTIVE_HOMEY_API_URL, newEntry.apiUrl)
        }

        editor.apply()
        return true
    }

    /**
     * Submits a fresh token for a hub, implicitly making it the active hub if saveAsActive is true.
     * Clears any active re-authentication flags for this hub.
     *
     * @public
     * @param homeyId The target hub ID.
     * @param name Display name of the hub.
     * @param sessionToken Delegated user session token.
     * @param refreshSecret Athom Cloud refresh token for silent rotation.
     * @param apiUrl Base API URL of the hub.
     * @param saveAsActive Whether to set this hub as currently active.
     */
    fun saveHubToken(homeyId: String, name: String, sessionToken: String, refreshSecret: String, apiUrl: String, saveAsActive: Boolean = true) {
        val tokensMap = getAllHubTokens().toMutableMap()
        tokensMap[homeyId] = HubTokenEntry(
            sessionToken = sessionToken,
            refreshSecret = refreshSecret,
            name = name,
            apiUrl = apiUrl,
            isReauthRequired = false,
            reauthReason = null
        )
        
        val editor = _prefs.edit().putString(_KEY_HUB_TOKENS, _gson.toJson(tokensMap))
        
        if (saveAsActive) {
            editor.putString(_KEY_ACTIVE_HOMEY_ID, homeyId)
                .putString(_KEY_ACTIVE_HOMEY_NAME, name)
                .putString(_KEY_ACTIVE_HOMEY_API_URL, apiUrl)
        }
        
        editor.apply()
    }

    /**
     * Marks whether a specific hub requires user re-authentication, along with an optional reason.
     *
     * @public
     * @param homeyId The ID of the target hub.
     * @param required Whether re-authentication is required.
     * @param reason The diagnostic reason (e.g. `"COMPANION_UPDATED"` or `"EXPIRED"`).
     */
    fun setReauthRequired(homeyId: String, required: Boolean, reason: String? = null) {
        val tokensMap = getAllHubTokens().toMutableMap()
        val entry = tokensMap[homeyId] ?: return
        tokensMap[homeyId] = entry.copy(isReauthRequired = required, reauthReason = if (required) reason else null)
        _prefs.edit().putString(_KEY_HUB_TOKENS, _gson.toJson(tokensMap)).apply()
    }

    /**
     * Checks if re-authentication is required for the specified hub (or currently active hub).
     *
     * @public
     * @param hubId Optional target hub ID. Defaults to active hub.
     * @return `true` if re-authentication is needed, `false` otherwise.
     */
    fun isReauthRequired(hubId: String? = null): Boolean {
        val targetId = hubId ?: getSelectedHomeyId() ?: return false
        return getHubToken(targetId)?.isReauthRequired == true
    }

    /**
     * Retrieves the re-authentication reason for the specified hub (or currently active hub).
     *
     * @public
     * @param hubId Optional target hub ID. Defaults to active hub.
     * @return The reason string (e.g. `"COMPANION_UPDATED"`), or `null`.
     */
    fun getReauthReason(hubId: String? = null): String? {
        val targetId = hubId ?: getSelectedHomeyId() ?: return null
        return getHubToken(targetId)?.reauthReason
    }

    // ── Public Methods — Active Homey Selection ───────────────────────────────

    /**
     * Saves the active Homey selection details without requiring tokens yet.
     * Used during initial setup before OAuth2 is completed.
     *
     * @public
     * @param id Target Homey Hub ID.
     * @param name Display name of the Homey hub.
     * @param apiUrl Base API URL for the hub.
     */
    fun saveSelectedHomey(id: String, name: String, apiUrl: String) {
        _prefs.edit()
            .putString(_KEY_ACTIVE_HOMEY_ID, id)
            .putString(_KEY_ACTIVE_HOMEY_NAME, name)
            .putString(_KEY_ACTIVE_HOMEY_API_URL, apiUrl)
            .apply()
    }

    /**
     * Switches the active Homey hub. The hub MUST already have tokens stored.
     *
     * @public
     * @param homeyId ID of the authenticated hub to activate.
     * @return `true` if the switch succeeded, `false` if no token entry exists.
     * @example
     * ```kotlin
     * val switched = storage.switchActiveHomey(hubId)
     * ```
     */
    fun switchActiveHomey(homeyId: String): Boolean {
        val entry = getHubToken(homeyId) ?: return false
        _prefs.edit()
            .putString(_KEY_ACTIVE_HOMEY_ID, homeyId)
            .putString(_KEY_ACTIVE_HOMEY_NAME, entry.name)
            .putString(_KEY_ACTIVE_HOMEY_API_URL, entry.apiUrl)
            .apply()
        return true
    }

    /**
     * Returns the ID of the currently active Homey hub.
     *
     * @public
     * @return Active hub ID string or `null` if unconfigured.
     */
    fun getSelectedHomeyId(): String? = _prefs.getString(_KEY_ACTIVE_HOMEY_ID, null)
    
    /**
     * Returns the display name of the currently active Homey hub (or localized Demo Hub title).
     *
     * @public
     * @return Active hub name or `null` if unset.
     */
    fun getSelectedHomeyName(): String? {
        if (isDemoMode()) {
            return _context.getString(com.dimapp.android.homeywatch.R.string.demo_hub_title)
        }
        return _prefs.getString(_KEY_ACTIVE_HOMEY_NAME, null)
    }
    
    /**
     * Returns the base API URL of the currently active Homey hub.
     *
     * @public
     * @return Base API URL or `null` if unset.
     */
    fun getSelectedHomeyApiUrl(): String? = _prefs.getString(_KEY_ACTIVE_HOMEY_API_URL, null)

    /**
     * Checks whether the application has an active hub with stored authentication tokens.
     *
     * @public
     * @return `true` when configured and ready for API calls.
     */
    fun isConfigured(): Boolean {
        val activeId = getSelectedHomeyId() ?: return false
        return isHubAuthenticated(activeId)
    }

    /**
     * Alias returning the base API URL of the active Homey hub.
     *
     * @public
     * @return Base API URL or `null`.
     */
    fun getBaseUrl(): String? = getSelectedHomeyApiUrl()

    // ── Public Methods — Tile Slot Hub Assignment ───────────────────────────

    /**
     * Retrieves the hub ID assigned to a given Tile slot.
     *
     * @public
     * @param slotNumber 1-based slot number (e.g. 1..4).
     * @return The assigned Homey ID or "demo", or `null` if unassigned.
     */
    fun getTileSlotHubId(slotNumber: Int): String? {
        return _prefs.getString("tile_slot_${slotNumber}_hub_id", null)
    }

    /**
     * Retrieves the custom hub name assigned to a given Tile slot.
     *
     * @public
     * @param slotNumber 1-based slot number (e.g. 1..4).
     * @return The cached hub name, or `null` if unassigned.
     */
    fun getTileSlotHubName(slotNumber: Int): String? {
        return _prefs.getString("tile_slot_${slotNumber}_hub_name", null)
    }

    /**
     * Assigns a specific hub ID and name to a Tile slot.
     *
     * @public
     * @param slotNumber 1-based slot number (e.g. 1..4).
     * @param hubId The Homey ID or "demo".
     * @param hubName The human-readable name of the hub.
     */
    fun setTileSlotHub(slotNumber: Int, hubId: String, hubName: String) {
        _prefs.edit()
            .putString("tile_slot_${slotNumber}_hub_id", hubId)
            .putString("tile_slot_${slotNumber}_hub_name", hubName)
            .apply()
    }

    /**
     * Clears the assignment for a given Tile slot.
     *
     * @public
     * @param slotNumber 1-based slot number (e.g. 1..4).
     */
    fun clearTileSlot(slotNumber: Int) {
        _prefs.edit()
            .remove("tile_slot_${slotNumber}_hub_id")
            .remove("tile_slot_${slotNumber}_hub_name")
            .apply()
    }

    // ── Public Methods — Active Hub Session Tokens ─────────────────────────────

    /**
     * Retrieves the session token for the specified Homey hub, or the currently active hub if [hubId] is null.
     *
     * @public
     * @param hubId Optional target hub ID. Defaults to active hub.
     * @return The raw session token string, or `null` if not authenticated.
     */
    fun getHubSessionToken(hubId: String? = null): String? {
        val targetId = hubId ?: getSelectedHomeyId() ?: return null
        return getHubToken(targetId)?.sessionToken
    }

    /**
     * Retrieves the refresh secret for the specified Homey hub, or the currently active hub if [hubId] is null.
     *
     * @public
     * @param hubId Optional target hub ID. Defaults to active hub.
     * @return The refresh secret string, or `null` if not available.
     */
    fun getHubRefreshSecret(hubId: String? = null): String? {
        val targetId = hubId ?: getSelectedHomeyId() ?: return null
        return getHubToken(targetId)?.refreshSecret
    }

    /**
     * Overwrites the stored session token and refresh secret for the specified hub.
     * Used during silent token rotation (`/auth/refresh`).
     *
     * @public
     * @param homeyId The target hub ID.
     * @param newSessionToken The updated session token.
     * @param newRefreshSecret The updated refresh secret.
     */
    fun updateHubTokens(homeyId: String, newSessionToken: String, newRefreshSecret: String) {
        val entry = getHubToken(homeyId) ?: return
        val name = entry.name ?: (if (homeyId == getSelectedHomeyId()) getSelectedHomeyName() else null) ?: return
        val apiUrl = entry.apiUrl ?: (if (homeyId == getSelectedHomeyId()) getSelectedHomeyApiUrl() else null) ?: return
        val isCurrentlyActive = (homeyId == getSelectedHomeyId())
        saveHubToken(
            homeyId = homeyId,
            name = name,
            sessionToken = newSessionToken,
            refreshSecret = newRefreshSecret,
            apiUrl = apiUrl,
            saveAsActive = isCurrentlyActive
        )
    }

    /**
     * Overwrites the stored session token and refresh secret ONLY for the currently active hub.
     * Used exclusively during silent token rotation (`/auth/refresh`).
     */
    fun updateActiveHubTokens(newSessionToken: String, newRefreshSecret: String) {
        val activeId = getSelectedHomeyId() ?: return
        updateHubTokens(activeId, newSessionToken, newRefreshSecret)
    }

    // ── Public Methods — User and Homeys List (Persisted on first login) ──────

    /**
     * Saves the display name of the authenticated user.
     *
     * Only the user's display name is persisted. Email and user ID are not stored
     * as they are never displayed to the user in the current UI.
     *
     * @public
     * @param name The Athom account display name (e.g. "John Doe").
     */
    fun saveUserProfile(name: String?) {
        _prefs.edit()
            .putString(_KEY_USER_NAME, name ?: "Homey User")
            .apply()
    }

    /**
     * Returns the cached display name of the authenticated Athom user.
     *
     * @public
     * @return Display name or `null` if not cached.
     */
    fun getUserName(): String? = _prefs.getString(_KEY_USER_NAME, null)



    // ── Public Methods — UI Preferences ────────────────────────────────────

    /**
     * Saves the sync interval in seconds.
     *
     * @public
     * @param seconds Sync interval in seconds (e.g. 5, 10, 30).
     * @example storage.saveSyncInterval(10)
     */
    fun saveSyncInterval(seconds: Int) {
        _prefs.edit().putInt(_KEY_SYNC_INTERVAL, seconds).apply()
    }

    /**
     * Retrieves the sync interval in seconds, defaulting to 15 if not set.
     *
     * @public
     * @return Sync interval in seconds.
     * @example val interval = storage.getSyncInterval()
     */
    fun getSyncInterval(): Int = _prefs.getInt(_KEY_SYNC_INTERVAL, 15)

    // ── Public Methods — Demo Mode ───────────────────────────────────────────

    /**
     * Checks whether the app is currently running in mock Demo Mode.
     * Evaluates dynamically based on whether the active hub is "demo".
     *
     * @public
     * @return True if in demo mode.
     */
    fun isDemoMode(): Boolean = getSelectedHomeyId() == "demo"

    /**
     * Enables or disables demo mode based on [enabled].
     *
     * @public
     * @param enabled True to enable demo mode, false to clear it.
     * @example
     * storage.setDemoMode(true)
     */
    fun setDemoMode(enabled: Boolean) {
        if (enabled) {
            enableDemoMode()
        } else {
            clearDemoMode()
        }
    }

    /**
     * Activates Demo Mode, registering a mock hub with sample configuration.
     *
     * @public
     */
    fun enableDemoMode() {
        _prefs.edit()
            .putBoolean(_KEY_IS_DEMO_MODE, true)
            .apply()

        saveHubToken(
            homeyId = "demo",
            name = _context.getString(com.dimapp.android.homeywatch.R.string.demo_hub_title),
            sessionToken = "demo_session_token",
            refreshSecret = "demo_refresh_secret",
            apiUrl = "https://demo.connect.athom.com",
            saveAsActive = true
        )
    }

    /**
     * Deactivates Demo Mode and removes the mock hub from storage.
     *
     * @public
     */
    fun clearDemoMode() {
        _prefs.edit()
            .remove(_KEY_IS_DEMO_MODE)
            .apply()
        removeHubToken("demo")
    }

    /**
     * Clears all stored data (full factory reset).
     *
     * @public
     * @example storage.clearAll()
     */
    fun clearAll() {
        _prefs.edit().clear().apply()
    }

    /**
     * Builds and returns the [EncryptedSharedPreferences] instance.
     *
     * Creates the AES-256 master key in the Android Keystore on first call.
     *
     * @private
     * @return The [SharedPreferences] backed by encryption.
     */
    private fun _buildEncryptedPrefs(): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            _PREFS_FILE_NAME,
            masterKeyAlias,
            _context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
