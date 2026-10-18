/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-FileCopyrightText: 2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.fan

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class FanTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateQsState()
    }

    override fun onClick() {
        super.onClick()
        FanController.setFanEnabled(this, !FanController.isFanEnabled(this))
        updateQsState()
    }

    private fun updateQsState() {
        qsTile
            .apply {
                state = if (FanController.isFanEnabled(this@FanTileService)) {
                    Tile.STATE_ACTIVE
                } else {
                    Tile.STATE_INACTIVE
                }
            }
            .updateTile()
    }
}
