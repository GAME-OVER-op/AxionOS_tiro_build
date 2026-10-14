/*
 * SPDX-FileCopyrightText: 2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.hbm

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import org.lineageos.settings.R

class HbmTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateQsState()
    }

    override fun onClick() {
        super.onClick()
        HbmController.setForced(this, !HbmController.isForced(this))
        updateQsState()
    }

    private fun updateQsState() {
        val forced = HbmController.isForced(this)
        val baseAuto = HbmController.isAuto(this)
        qsTile.apply {
            state = if (forced) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.hbm_tile_title)
            subtitle = getString(
                when {
                    forced -> R.string.hbm_mode_forced
                    baseAuto -> R.string.hbm_mode_auto
                    else -> R.string.hbm_mode_off
                },
            )
        }.updateTile()
    }
}
