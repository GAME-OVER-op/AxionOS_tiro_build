/*
 * SPDX-FileCopyrightText: 2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.lighting

import android.util.Log
import java.io.File
import java.io.FileOutputStream

internal object LightingConfigStore {
    private const val TAG = "LightingConfigStore"
    private const val CONFIG_DIR = "/data/system/nubia_lighting"
    private const val CONFIG_PATH = "$CONFIG_DIR/config.conf"
    private const val TEMP_PATH = "$CONFIG_DIR/config.conf.tmp"

    data class Config(
        val fanEnabled: Boolean = false,
        val fanMode: String = LightingController.FAN_MODE_STATIC_SOLID,
        val fanCode: String = "a4",
        val triggerEnabled: Boolean = false,
        val triggerMode: String = LightingController.EXTERNAL_MODE_STATIC,
        val triggerCode: String = "64",
        val notificationEnabled: Boolean = false,
        val notificationMode: String = LightingController.EXTERNAL_MODE_BREATHE,
        val notificationCode: String = "74",
    ) {
        fun normalized(): Config {
            val normalizedFanMode =
                fanMode.takeIf { it in LightingController.fanModes }
                    ?: LightingController.FAN_MODE_STATIC_SOLID
            val normalizedTriggerMode =
                triggerMode.takeIf { it in LightingController.externalModes }
                    ?: LightingController.EXTERNAL_MODE_STATIC
            val normalizedNotificationMode =
                notificationMode.takeIf { it in LightingController.externalModes }
                    ?: LightingController.EXTERNAL_MODE_BREATHE

            return copy(
                fanMode = normalizedFanMode,
                fanCode = LightingController.validCode(
                    fanCode,
                    LightingController.fanEffects(normalizedFanMode),
                ),
                triggerMode = normalizedTriggerMode,
                triggerCode = LightingController.validCode(
                    triggerCode,
                    LightingController.externalEffects(normalizedTriggerMode),
                ),
                notificationMode = normalizedNotificationMode,
                notificationCode = LightingController.validCode(
                    notificationCode,
                    LightingController.externalEffects(normalizedNotificationMode),
                ),
            )
        }
    }

    @Synchronized
    fun load(): Config {
        val file = File(CONFIG_PATH)
        if (!file.isFile) return Config()

        var config = Config()
        runCatching {
            file.forEachLine { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val separator = line.indexOf('=')
                if (separator <= 0) return@forEachLine

                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                config = when (key) {
                    "fan_enabled" -> config.copy(fanEnabled = parseBool(value, config.fanEnabled))
                    "fan_mode" -> config.copy(fanMode = value)
                    "fan_code" -> config.copy(fanCode = value.lowercase())
                    "trigger_enabled" -> config.copy(triggerEnabled = parseBool(value, config.triggerEnabled))
                    "trigger_mode" -> config.copy(triggerMode = value)
                    "trigger_code" -> config.copy(triggerCode = value.lowercase())
                    "notification_enabled" -> config.copy(notificationEnabled = parseBool(value, config.notificationEnabled))
                    "notification_mode" -> config.copy(notificationMode = value)
                    "notification_code" -> config.copy(notificationCode = value.lowercase())
                    else -> config
                }
            }
        }.onFailure { error ->
            Log.e(TAG, "Unable to read lighting configuration", error)
            return Config()
        }

        return config.normalized()
    }

    @Synchronized
    fun save(config: Config): Boolean {
        val normalized = config.normalized()
        val directory = File(CONFIG_DIR)
        if (!directory.exists() && !directory.mkdirs()) {
            Log.e(TAG, "Unable to create $CONFIG_DIR")
            return false
        }

        val content = buildString {
            appendLine("version=1")
            appendLine("fan_enabled=${normalized.fanEnabled.asFlag()}")
            appendLine("fan_mode=${normalized.fanMode}")
            appendLine("fan_code=${normalized.fanCode}")
            appendLine("trigger_enabled=${normalized.triggerEnabled.asFlag()}")
            appendLine("trigger_mode=${normalized.triggerMode}")
            appendLine("trigger_code=${normalized.triggerCode}")
            appendLine("notification_enabled=${normalized.notificationEnabled.asFlag()}")
            appendLine("notification_mode=${normalized.notificationMode}")
            appendLine("notification_code=${normalized.notificationCode}")
        }

        val temporary = File(TEMP_PATH)
        val target = File(CONFIG_PATH)
        return runCatching {
            FileOutputStream(temporary, false).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            true
        }.onFailure { error ->
            temporary.delete()
            Log.e(TAG, "Unable to save lighting configuration", error)
        }.getOrDefault(false)
    }

    @Synchronized
    fun update(transform: (Config) -> Config): Config {
        val current = load()
        val updated = transform(current).normalized()
        if (updated != current) save(updated)
        return updated
    }

    fun ensureInitialized() {
        if (!File(CONFIG_PATH).isFile) save(Config())
    }

    private fun parseBool(value: String, fallback: Boolean): Boolean = when {
        value == "1" || value.equals("true", true) || value.equals("on", true) -> true
        value == "0" || value.equals("false", true) || value.equals("off", true) -> false
        else -> fallback
    }

    private fun Boolean.asFlag(): Int = if (this) 1 else 0
}
