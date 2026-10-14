/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.fan

import android.content.Context
import org.lineageos.settings.service.HardwareControlService
import org.lineageos.settings.utils.*

object FanController {

    const val KEY_FAN_ENABLE = "fan_enable"
    const val KEY_FAN_SPEED = "fan_speed_level"
    const val KEY_FAN_MODE = "fan_mode"
    const val KEY_FAN_CURVE = "fan_curve"

    const val FAN_ENABLE_NODE = "/sys/kernel/fan/fan_enable"
    const val FAN_SPEED_NODE = "/sys/kernel/fan/fan_speed_level"

    const val FAN_MIN_SPEED = 1
    const val FAN_MAX_SPEED = 5
    const val FAN_DEFAULT_SPEED = 3
    const val FAN_MODE_MANUAL = "manual"
    const val FAN_MODE_AUTO = "auto"
    const val DEFAULT_CURVE = "50,60,70,80,90"

    /*
     * Enable or disable the fan
     * @return puts the value in place
     */
    fun setFanEnabled(context: Context, enabled: Boolean) {
        putInt(context, KEY_FAN_ENABLE, if (enabled) 1 else 0)
        HardwareControlService.requestUpdate(context)
    }

    /*
     * Set the fan speed level
     * @return puts the value in place
     */
    fun setFanSpeed(context: Context, speed: Int) {
        putInt(context, KEY_FAN_SPEED, speed.coerceIn(FAN_MIN_SPEED, FAN_MAX_SPEED))
        HardwareControlService.requestUpdate(context)
    }

    fun setFanMode(context: Context, mode: String) {
        putString(
            context,
            KEY_FAN_MODE,
            if (mode == FAN_MODE_AUTO) FAN_MODE_AUTO else FAN_MODE_MANUAL,
        )
        HardwareControlService.requestUpdate(context)
    }

    fun setFanCurve(context: Context, curve: IntArray) {
        val normalized = normalizeCurve(curve)
        putString(context, KEY_FAN_CURVE, normalized.joinToString(","))
        HardwareControlService.requestUpdate(context)
    }

    fun getFanCurve(context: Context): IntArray {
        val parsed = getString(context, KEY_FAN_CURVE, DEFAULT_CURVE)
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .toIntArray()
        return normalizeCurve(parsed)
    }

    private fun normalizeCurve(input: IntArray): IntArray {
        val defaults = intArrayOf(50, 60, 70, 80, 90)
        if (input.size != 5) return defaults
        val output = IntArray(5)
        input.forEachIndexed { index, value ->
            val minimum = if (index == 0) 20 else output[index - 1] + 1
            val maximum = 110 - (4 - index)
            output[index] = value.coerceIn(minimum, maximum)
        }
        return output
    }

    /*
     * Apply both fan state and speed settings
     * @return puts the value in place
     */
    fun applySettings(context: Context, enabled: Boolean, speed: Int) {
        putInt(context, KEY_FAN_ENABLE, if (enabled) 1 else 0)
        putInt(context, KEY_FAN_SPEED, speed.coerceIn(FAN_MIN_SPEED, FAN_MAX_SPEED))
        HardwareControlService.requestUpdate(context)
    }

    /*
     * Restore settings on boot / resume
     * @return puts the value in place
     */
    fun restoreSettings(context: Context) {
        val fanEnabled = getInt(context, KEY_FAN_ENABLE, 0) == 1
        val fanSpeed = getInt(context, KEY_FAN_SPEED, FAN_DEFAULT_SPEED)

        applySettings(context, fanEnabled, fanSpeed)
    }
}
