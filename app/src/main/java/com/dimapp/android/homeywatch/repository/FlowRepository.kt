package com.dimapp.android.homeywatch.repository

import android.content.Context
import android.util.Log
import com.dimapp.android.homeywatch.R
import com.dimapp.android.homeywatch.api.SanitizedHttpLogger
import com.dimapp.android.homeywatch.api.models.FlowDto
import com.dimapp.android.homeywatch.repository.models.HomeyResult
import com.dimapp.android.homeywatch.auth.HomeyAuthRepository
import com.dimapp.android.homeywatch.storage.TokenStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Repository handling flow-related operations (fetching, caching, and triggering).
 *
 * @param context Application context for resources.
 * @param storage Storage instance for tokens and demo mode check.
 * @param authRepo Authentication repository for credentials.
 */
class FlowRepository(
    context: Context,
    storage: TokenStorage,
    authRepo: HomeyAuthRepository
) : HomeyBaseRepository(context, storage, authRepo) {

    private val _TAG = "FlowRepository"
    private val _cacheMutex = Mutex()
    private var _cachedFlows: List<FlowDto>? = null
    private var _cachedHubId: String? = null

    /**
     * Alias for [getTriggerableFlows].
     *
     * @public
     * @param forceRefresh If `true`, forces a network fetch bypassing the local cache.
     * @return [HomeyResult.Success] containing the list of [FlowDto] instances, or [HomeyResult.Error].
     * @example
     * ```kotlin
     * val flows = flowRepo.getFlows(forceRefresh = false)
     * ```
     */
    suspend fun getFlows(forceRefresh: Boolean = false): HomeyResult<List<FlowDto>> = getTriggerableFlows(forceRefresh)

    /**
     * Returns all standard Flows that can be manually triggered, using an in-memory cache
     * to avoid unnecessary network queries during tab transitions.
     *
     * Only flows with `triggerable = true` and `enabled = true` are included.
     *
     * @public
     * @param forceRefresh If true, forces a network fetch bypassing the local cache.
     * @return [HomeyResult.Success] containing the list of [FlowDto] instances, or [HomeyResult.Error].
     * @example
     * ```kotlin
     * val result = flowRepo.getTriggerableFlows(forceRefresh = true)
     * ```
     */
    suspend fun getTriggerableFlows(forceRefresh: Boolean = false): HomeyResult<List<FlowDto>> = withContext(Dispatchers.IO) {
        _cacheMutex.withLock {
            if (storage.isDemoMode()) {
                return@withLock HomeyResult.Success(_createDemoFlows())
            }

            val currentHubId = storage.getSelectedHomeyId()
                ?: return@withLock HomeyResult.Error(context.getString(R.string.repo_error_not_configured))

            if (!forceRefresh && _cachedHubId == currentHubId && _cachedFlows != null) {
                Log.d(_TAG, "[FlowRepository:getTriggerableFlows] Returning ${_cachedFlows?.size} flows from in-memory cache.")
                return@withLock HomeyResult.Success(_cachedFlows!!)
            }

            val service = _buildService()
                ?: return@withLock HomeyResult.Error(context.getString(R.string.repo_error_not_configured))

            Log.d(_TAG, "[FlowRepository:getTriggerableFlows] Fetching flows, advanced flows, and folders from network (forceRefresh=$forceRefresh)...")
            try {
                coroutineScope {
                    val flowsDeferred = async {
                        try { service.getFlows() } catch (e: Exception) { null }
                    }
                    val advFlowsDeferred = async {
                        try { service.getAdvancedFlows() } catch (e: Exception) { null }
                    }
                    val foldersDeferred = async {
                        try { service.getFlowFolders() } catch (e: Exception) { null }
                    }

                    val flowsResponse = flowsDeferred.await()
                    val advFlowsResponse = advFlowsDeferred.await()
                    val foldersResponse = foldersDeferred.await()

                    if (flowsResponse?.isSuccessful != true && advFlowsResponse?.isSuccessful != true) {
                        val code = flowsResponse?.code() ?: advFlowsResponse?.code() ?: 500
                        Log.e(_TAG, "[FlowRepository:getTriggerableFlows] Flows fetch failed: HTTP $code")
                        if (code == 401 && !currentHubId.isNullOrBlank()) {
                            storage.setReauthRequired(currentHubId, true, "EXPIRED")
                        }
                        return@coroutineScope HomeyResult.Error(
                            context.getString(R.string.repo_error_flow_http, code)
                        )
                    }

                    val foldersMap = if (foldersResponse?.isSuccessful == true) {
                        foldersResponse.body() ?: emptyMap()
                    } else {
                        emptyMap()
                    }

                    val standardFlows = if (flowsResponse?.isSuccessful == true) {
                        (flowsResponse.body() ?: emptyMap()).values
                            .filter { it.triggerable == true && it.enabled != false && it.broken != true }
                            .map { it.copy(isAdvanced = false) }
                    } else {
                        emptyList()
                    }

                    val advancedFlows = if (advFlowsResponse?.isSuccessful == true) {
                        (advFlowsResponse.body() ?: emptyMap()).values
                            .filter { it.triggerable == true && it.enabled != false && it.broken != true }
                            .map { it.copy(isAdvanced = true) }
                    } else {
                        emptyList()
                    }

                    val flows = (standardFlows + advancedFlows)
                        .map { flow ->
                            val resolvedFolderName = flow.folder?.let { foldersMap[it]?.effectiveName }
                            flow.copy(folderName = resolvedFolderName)
                        }
                        .sortedWith(
                            compareBy<FlowDto> { it.folderName ?: "" }
                                .thenBy { it.name }
                        )

                    _cachedFlows = flows
                    _cachedHubId = currentHubId
                    Log.d(_TAG, "[FlowRepository:getTriggerableFlows] Fetched and cached ${flows.size} triggerable flows (${standardFlows.size} standard, ${advancedFlows.size} advanced).")
                    HomeyResult.Success(flows)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(_TAG, "[FlowRepository:getTriggerableFlows] Exception during getTriggerableFlows: ${e.message}", e)
                HomeyResult.Error(context.getString(R.string.repo_error_conn_failed, e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Clears the in-memory flow cache, forcing the next fetch from network.
     *
     * @public
     * @example
     * ```kotlin
     * flowRepo.invalidateCache()
     * ```
     */
    fun invalidateCache() {
        Log.d(_TAG, "[FlowRepository:invalidateCache] Flow cache invalidated.")
        _cachedFlows = null
        _cachedHubId = null
    }

    /**
     * Checks if the in-memory cache is currently empty.
     *
     * @public
     * @return `true` if no flows are currently cached.
     * @example
     * ```kotlin
     * val empty = flowRepo.isCacheEmpty()
     * ```
     */
    fun isCacheEmpty(): Boolean {
        return _cachedFlows == null
    }

    /**
     * Triggers a Homey Flow (standard or advanced) by its UUID.
     *
     * @public
     * @param flowId UUID of the target Flow to trigger.
     * @param isAdvanced Whether the flow is an Advanced Flow.
     * @return [HomeyResult.Success] with localized message on success, or [HomeyResult.Error].
     * @example
     * ```kotlin
     * val result = flowRepo.triggerFlow("flow-uuid-123", isAdvanced = true)
     * ```
     */
    suspend fun triggerFlow(flowId: String, isAdvanced: Boolean = false): HomeyResult<String> = withContext(Dispatchers.IO) {
        val maskedFlowId = SanitizedHttpLogger.maskId(flowId)
        if (storage.isDemoMode()) {
            Log.d(_TAG, "[FlowRepository:triggerFlow] Triggered mock demo flow: $maskedFlowId (isAdvanced=$isAdvanced)")
            return@withContext HomeyResult.Success(context.getString(R.string.repo_success_flow))
        }

        val service = _buildService()
            ?: return@withContext HomeyResult.Error(context.getString(R.string.repo_error_not_configured))

        try {
            val response = if (isAdvanced) {
                service.triggerAdvancedFlow(flowId)
            } else {
                service.triggerFlow(flowId)
            }

            if (response.isSuccessful) {
                Log.d(_TAG, "[FlowRepository:triggerFlow] Flow '$maskedFlowId' (isAdvanced=$isAdvanced) triggered successfully via direct Homey REST API.")
                HomeyResult.Success(context.getString(R.string.repo_success_flow))
            } else {
                val errorBody = response.errorBody()?.string().orEmpty()
                Log.e(_TAG, "[FlowRepository:triggerFlow] Trigger flow '$maskedFlowId' (isAdvanced=$isAdvanced) failed: HTTP ${response.code()} - errorBody: $errorBody")
                HomeyResult.Error(context.getString(R.string.repo_error_flow_trigger_http, response.code()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(_TAG, "[FlowRepository:triggerFlow] Exception triggering flow '$maskedFlowId': ${e.message}", e)
            HomeyResult.Error(context.getString(R.string.repo_error_network, e.message ?: "Unknown error"))
        }
    }

    // ── Private Methods ───────────────────────────────────────────────────────

    /**
     * Creates a predefined list of mock flows for Demo Mode / Google Play Store review.
     *
     * @private
     * @return List of mock [FlowDto] instances organized into folders.
     */
    private fun _createDemoFlows(): List<FlowDto> {
        val automationsFolder = context.getString(R.string.demo_folder_automations)
        val securityFolder = context.getString(R.string.demo_folder_security)

        return listOf(
            FlowDto(
                id = "demo_flow_1",
                name = context.getString(R.string.demo_flow_leaving_home),
                triggerable = true,
                enabled = true,
                folder = "demo_folder_automations",
                folderName = automationsFolder,
                isAdvanced = false
            ),
            FlowDto(
                id = "demo_flow_2",
                name = context.getString(R.string.demo_flow_arriving_home),
                triggerable = true,
                enabled = true,
                folder = "demo_folder_automations",
                folderName = automationsFolder,
                isAdvanced = false
            ),
            FlowDto(
                id = "demo_flow_3",
                name = context.getString(R.string.demo_flow_goodnight),
                triggerable = true,
                enabled = true,
                folder = "demo_folder_security",
                folderName = securityFolder,
                isAdvanced = true
            ),
            FlowDto(
                id = "demo_flow_4",
                name = context.getString(R.string.demo_flow_welcome),
                triggerable = true,
                enabled = true,
                folder = "demo_folder_security",
                folderName = securityFolder,
                isAdvanced = true
            )
        )
    }
}
