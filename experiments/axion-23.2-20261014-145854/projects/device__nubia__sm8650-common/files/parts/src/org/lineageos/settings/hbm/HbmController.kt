/*
 * SPDX-FileCopyrightText: 2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.hbm

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.quicksettings.TileService

object HbmController {

    const val KEY_MODE = "hbm_mode"
    const val KEY_AUTO_ENABLED = "auto_hbm_enabled"
    const val KEY_FORCE_ENABLED = "force_hbm_enabled"
    const val KEY_LUX_ON = "auto_hbm_lux_on"
    const val KEY_LUX_OFF = "auto_hbm_lux_off"

    const val MODE_OFF = "off"
    const val MODE_AUTO = "auto"
    const val MODE_FORCED = "forced"

    const val LUX_STEP = 500
    const val DEFAULT_LUX_ON = 110_000
    const val DEFAULT_LUX_OFF = 20_000
    const val MIN_LUX_OFF = 5_000
    const val MIN_LUX_ON = MIN_LUX_OFF + LUX_STEP
    const val MAX_LUX = 120_000

    fun currentMode(context: Context): String = when {
        isForced(context) -> MODE_FORCED
        isAuto(context) -> MODE_AUTO
        else -> MODE_OFF
    }

    fun isForced(context: Context): Boolean =
        Settings.Secure.getInt(context.contentResolver, KEY_FORCE_ENABLED, 0) == 1

    fun isAuto(context: Context): Boolean =
        Settings.Secure.getInt(context.contentResolver, KEY_AUTO_ENABLED, 0) == 1

    fun setMode(context: Context, mode: String) {
        when (mode) {
            MODE_OFF -> {
                putSecureInt(context, KEY_AUTO_ENABLED, 0)
                putSecureInt(context, KEY_FORCE_ENABLED, 0)
            }
            MODE_AUTO -> {
                putSecureInt(context, KEY_AUTO_ENABLED, 1)
                putSecureInt(context, KEY_FORCE_ENABLED, 0)
            }
            MODE_FORCED -> putSecureInt(context, KEY_FORCE_ENABLED, 1)
            else -> return
        }
        requestTileRefresh(context)
    }

    /**
     * The QS tile is a temporary override. Disabling it returns to the user's
     * previously selected automatic/off state because that setting is left intact.
     */
    fun setForced(context: Context, enabled: Boolean) {
        putSecureInt(context, KEY_FORCE_ENABLED, if (enabled) 1 else 0)
        requestTileRefresh(context)
    }

    fun getLuxOn(context: Context): Int =
        Settings.Secure.getInt(context.contentResolver, KEY_LUX_ON, DEFAULT_LUX_ON)

    fun getLuxOff(context: Context): Int =
        Settings.Secure.getInt(context.contentResolver, KEY_LUX_OFF, DEFAULT_LUX_OFF)

    fun setLuxOn(context: Context, value: Int) {
        val lux = value.coerceIn(MIN_LUX_ON, MAX_LUX)
        putSecureInt(context, KEY_LUX_ON, lux)
        if (getLuxOff(context) >= lux) {
            putSecureInt(
                context,
                KEY_LUX_OFF,
                (lux - LUX_STEP).coerceAtLeast(MIN_LUX_OFF),
            )
        }
    }

    fun setLuxOff(context: Context, value: Int) {
        val maximum = (getLuxOn(context) - LUX_STEP).coerceAtLeast(MIN_LUX_OFF)
        putSecureInt(context, KEY_LUX_OFF, value.coerceIn(MIN_LUX_OFF, maximum))
    }

    fun requestTileRefresh(context: Context) {
        TileService.requestListeningState(
            context,
            ComponentName(context, HbmTileService::class.java),
        )
    }

    private fun putSecureInt(context: Context, key: String, value: Int) {
        Settings.Secure.putInt(context.contentResolver, key, value)
    }
}
