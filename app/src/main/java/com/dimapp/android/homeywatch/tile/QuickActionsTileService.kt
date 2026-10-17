package com.dimapp.android.homeywatch.tile

/**
 * Dedicated Wear OS Tile for Homey Hub Slot 1.
 *
 * Inherits all 2x2 grid rendering, Homey circular logo header, and dynamic hub name logic
 * from [BaseQuickActionsTileService].
 */
class QuickActionsTileService : BaseQuickActionsTileService(slotNumber = 1)
