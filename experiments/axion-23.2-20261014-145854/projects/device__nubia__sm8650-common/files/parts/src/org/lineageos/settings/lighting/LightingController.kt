/*
 * SPDX-FileCopyrightText: 2025-2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.lighting

import android.content.Context
import org.lineageos.settings.service.HardwareControlService
import org.lineageos.settings.utils.getString
import org.lineageos.settings.utils.putInt
import org.lineageos.settings.utils.putString

object LightingController {

    const val KEY_FAN_LED_ENABLE = "fan_led_enable"
    const val KEY_FAN_LED_MODE = "fan_led_mode"
    const val KEY_FAN_LED_CODE = "fan_led_code"
    const val KEY_TRIGGER_LED_ENABLE = "trigger_led_enable"
    const val KEY_TRIGGER_LED_MODE = "trigger_led_mode"
    const val KEY_TRIGGER_LED_CODE = "trigger_led_code"
    const val KEY_NOTIFICATION_LED_ENABLE = "notification_led_enable"
    const val KEY_NOTIFICATION_LED_MODE = "notification_led_mode"
    const val KEY_NOTIFICATION_LED_CODE = "notification_led_code"

    const val FAN_MODE_STATIC_SOLID = "static_solid"
    const val FAN_MODE_STATIC_MIXED = "static_mixed"
    const val FAN_MODE_BREATHE = "breathe"
    const val FAN_MODE_FAST_BREATHE = "fast_breathe"
    const val FAN_MODE_FLOW = "flow"

    const val EXTERNAL_MODE_STATIC = "static"
    const val EXTERNAL_MODE_BREATHE = "breathe"
    const val EXTERNAL_MODE_FAST_BREATHE = "fast_breathe"

    data class Effect(val code: String, val label: String)

    val fanModes = linkedMapOf(
        FAN_MODE_STATIC_SOLID to "Static — solid",
        FAN_MODE_STATIC_MIXED to "Static — mixed",
        FAN_MODE_BREATHE to "Breathe",
        FAN_MODE_FAST_BREATHE to "Fast breathe",
        FAN_MODE_FLOW to "Flow",
    )

    val externalModes = linkedMapOf(
        EXTERNAL_MODE_STATIC to "Static",
        EXTERNAL_MODE_BREATHE to "Breathe",
        EXTERNAL_MODE_FAST_BREATHE to "Fast breathe",
    )

    fun fanEffects(mode: String): List<Effect> = when (mode) {
        FAN_MODE_STATIC_MIXED -> listOf(
            Effect("90", "Orange + green + blue/purple"),
            Effect("91", "Yellow + red + cyan + pink"),
            Effect("92", "Cyan + red + blue + pink"),
            Effect("93", "Blue + purple"),
            Effect("94", "Pink + cyan"),
            Effect("95", "Orange + cyan"),
            Effect("96", "Cyan"),
            Effect("97", "Green + yellow"),
        )
        FAN_MODE_BREATHE -> listOf(
            Effect("31", "Pink"), Effect("32", "Yellow"), Effect("33", "Green"),
            Effect("34", "Blue"), Effect("35", "Cyan"), Effect("36", "Purple"),
            Effect("37", "Orange"), Effect("38", "Orange + blue + green + purple"),
            Effect("39", "Cyan + pink + red + yellow"),
            Effect("3a", "Cyan + pink + blue + purple"), Effect("3b", "Orange + blue"),
            Effect("3c", "Cyan + purple"), Effect("3d", "Cyan + orange"),
            Effect("3f", "Yellow + green"),
        )
        FAN_MODE_FAST_BREATHE -> listOf(
            Effect("21", "Purple"), Effect("22", "Yellow"), Effect("23", "Green"),
            Effect("24", "Blue"), Effect("25", "Cyan"), Effect("26", "Pink"),
            Effect("27", "Orange"), Effect("28", "Orange + blue + purple + green"),
            Effect("29", "Yellow + purple + pink + cyan"),
            Effect("2a", "Cyan + red + pink + blue"), Effect("2b", "Purple + blue"),
            Effect("2c", "Pink + cyan"), Effect("2d", "Orange + cyan"),
            Effect("2f", "Green + yellow"),
        )
        FAN_MODE_FLOW -> listOf(
            Effect("41", "Pink"), Effect("42", "Yellow"), Effect("43", "Green"),
            Effect("44", "Blue"), Effect("45", "Cyan"), Effect("46", "Purple"),
            Effect("47", "Orange"), Effect("48", "Orange + purple + green + blue"),
            Effect("49", "Yellow + purple + blue + red"),
            Effect("4a", "Cyan + pink + purple + blue"), Effect("4b", "Blue + purple"),
            Effect("4c", "Pink + cyan"), Effect("4d", "Cyan + orange"),
            Effect("4f", "Yellow + green"),
        )
        else -> listOf(
            Effect("a0", "Red"), Effect("a2", "Yellow"), Effect("a3", "Green"),
            Effect("a4", "Blue"), Effect("a5", "Cyan"), Effect("a6", "Pink"),
            Effect("a7", "Orange"),
        )
    }

    fun externalEffects(mode: String): List<Effect> = when (mode) {
        EXTERNAL_MODE_BREATHE -> listOf(
            Effect("70", "Purple"), Effect("71", "Red"), Effect("72", "Yellow"),
            Effect("73", "Green"), Effect("74", "Blue"), Effect("75", "Cyan"),
            Effect("76", "Pink"), Effect("77", "Orange"),
        )
        EXTERNAL_MODE_FAST_BREATHE -> listOf(
            Effect("80", "Orange 1"), Effect("81", "Purple"), Effect("82", "Yellow"),
            Effect("83", "Green"), Effect("84", "Blue"), Effect("85", "Cyan"),
            Effect("86", "Pink"), Effect("87", "Orange 2"),
        )
        else -> listOf(
            Effect("60", "Red"), Effect("61", "Orange 1"), Effect("62", "Yellow"),
            Effect("63", "Green"), Effect("64", "Blue"), Effect("65", "Cyan"),
            Effect("66", "Pink"), Effect("67", "Orange 2"),
        )
    }

    fun setEnabled(context: Context, key: String, enabled: Boolean) {
        putInt(context, key, if (enabled) 1 else 0)
        HardwareControlService.requestUpdate(context)
    }

    fun setMode(context: Context, key: String, value: String) {
        putString(context, key, value)
        HardwareControlService.requestUpdate(context)
    }

    fun setCode(context: Context, key: String, value: String) {
        putString(context, key, value.lowercase())
        HardwareControlService.requestUpdate(context)
    }

    fun fanCode(context: Context): String {
        val mode = getString(context, KEY_FAN_LED_MODE, FAN_MODE_STATIC_SOLID)
        return validCode(getString(context, KEY_FAN_LED_CODE, "a4"), fanEffects(mode))
    }

    fun triggerCode(context: Context): String {
        val mode = getString(context, KEY_TRIGGER_LED_MODE, EXTERNAL_MODE_STATIC)
        return validCode(getString(context, KEY_TRIGGER_LED_CODE, "64"), externalEffects(mode))
    }

    fun notificationCode(context: Context): String {
        val mode = getString(context, KEY_NOTIFICATION_LED_MODE, EXTERNAL_MODE_BREATHE)
        return validCode(getString(context, KEY_NOTIFICATION_LED_CODE, "74"), externalEffects(mode))
    }

    private fun validCode(value: String, effects: List<Effect>): String {
        val normalized = value.lowercase()
        return effects.firstOrNull { it.code == normalized }?.code ?: effects.first().code
    }
}
