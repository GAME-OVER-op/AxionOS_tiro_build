/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-FileCopyrightText: 2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.fan

import android.content.Context

object FanController {

    const val KEY_FAN_ENABLE = "fan_enable"
    const val KEY_FAN_SPEED = "fan_speed_level"
    const val KEY_FAN_MODE = "fan_mode"
    const val KEY_FAN_CURVE = "fan_curve"

    const val FAN_MIN_SPEED = FanConfigStore.MIN_SPEED
    const val FAN_MAX_SPEED = FanConfigStore.MAX_SPEED
    const val FAN_DEFAULT_SPEED = FanConfigStore.DEFAULT_SPEED
    const val FAN_MODE_MANUAL = "manual"
    const val FAN_MODE_AUTO = "auto"
    const val FAN_MIN_CURVE_TEMP = FanConfigStore.MIN_CURVE_TEMP
    const val FAN_MAX_CURVE_TEMP = FanConfigStore.MAX_CURVE_TEMP

    fun isFanEnabled(context: Context): Boolean = FanConfigStore.load().enabled

    fun getFanSpeed(context: Context): Int = FanConfigStore.load().manualLevel

    fun getFanMode(context: Context): String = FanConfigStore.load().mode

    fun getFanCurve(context: Context): IntArray = FanConfigStore.load().curve.copyOf()

    fun setFanEnabled(context: Context, enabled: Boolean) {
        FanConfigStore.update { it.copy(enabled = enabled) }
    }

    fun setFanSpeed(context: Context, speed: Int) {
        FanConfigStore.update { it.copy(manualLevel = speed.coerceIn(FAN_MIN_SPEED, FAN_MAX_SPEED)) }
    }

    fun setFanMode(context: Context, mode: String) {
        FanConfigStore.update {
            it.copy(mode = if (mode == FAN_MODE_AUTO) FAN_MODE_AUTO else FAN_MODE_MANUAL)
        }
    }

    fun setFanCurve(context: Context, curve: IntArray) {
        FanConfigStore.update { it.copy(curve = FanConfigStore.normalizeCurve(curve)) }
    }

    fun applySettings(context: Context, enabled: Boolean, speed: Int) {
        FanConfigStore.update {
            it.copy(
                enabled = enabled,
                manualLevel = speed.coerceIn(FAN_MIN_SPEED, FAN_MAX_SPEED),
            )
        }
    }
}
