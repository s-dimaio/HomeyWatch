package com.dimapp.android.homeywatch.tile

import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.wear.tiles.TileService
import com.dimapp.android.homeywatch.core.DependencyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Invisible trampoline Activity to handle instant background device toggles from Wear OS Tiles.
 */
class TileActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        val capabilityId = intent.getStringExtra(EXTRA_CAPABILITY_ID)
        val targetActive = intent.getBooleanExtra(EXTRA_TARGET_ACTIVE, true)
        val hubId = intent.getStringExtra(EXTRA_HUB_ID)
        val slotNumber = intent.getIntExtra(EXTRA_SLOT_NUMBER, 1)

        Log.d("TileActionActivity", "[TileActionActivity:onCreate] deviceId=$deviceId, capabilityId=$capabilityId, targetActive=$targetActive, hubId=$hubId, slotNumber=$slotNumber")

        if (!deviceId.isNullOrBlank() && !capabilityId.isNullOrBlank()) {
            val appCtx = applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val repo = DependencyManager.getDeviceRepository(appCtx)
                    val result = repo.toggleDeviceState(
                        deviceId = deviceId,
                        capabilityId = capabilityId,
                        makeActive = targetActive,
                        hubId = hubId
                    )
                    Log.d("TileActionActivity", "[TileActionActivity:onCreate] toggleDeviceState completed: $result")
                    TileSlotManager.updateAllSlots(appCtx)
                } catch (e: Exception) {
                    Log.e("TileActionActivity", "[TileActionActivity:onCreate] Error during toggle: ${e.message}", e)
                } finally {
                    finish()
                }
            }
        } else {
            finish()
        }
    }

    companion object {
        const val EXTRA_DEVICE_ID = "EXTRA_DEVICE_ID"
        const val EXTRA_CAPABILITY_ID = "EXTRA_CAPABILITY_ID"
        const val EXTRA_TARGET_ACTIVE = "EXTRA_TARGET_ACTIVE"
        const val EXTRA_HUB_ID = "EXTRA_HUB_ID"
        const val EXTRA_SLOT_NUMBER = "EXTRA_SLOT_NUMBER"
    }
}
