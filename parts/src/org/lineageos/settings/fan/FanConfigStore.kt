/*
 * SPDX-FileCopyrightText: 2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.fan

import android.util.Log
import java.io.File
import java.io.FileOutputStream

internal object FanConfigStore {
    private const val TAG = "FanConfigStore"
    private const val CONFIG_DIR = "/data/system/nubia_fan"
    private const val CONFIG_PATH = "$CONFIG_DIR/config.conf"
    private const val TEMP_PATH = "$CONFIG_DIR/config.conf.tmp"

    const val MIN_CURVE_TEMP = 32
    const val MAX_CURVE_TEMP = 110
    const val MIN_SPEED = 1
    const val MAX_SPEED = 5
    const val DEFAULT_SPEED = 3

    val DEFAULT_CURVE = intArrayOf(50, 60, 70, 80, 90)

    data class Config(
        val enabled: Boolean = false,
        val mode: String = FanController.FAN_MODE_MANUAL,
        val manualLevel: Int = DEFAULT_SPEED,
        val curve: IntArray = DEFAULT_CURVE.copyOf(),
    ) {
        fun normalized(): Config =
            copy(
                mode = if (mode == FanController.FAN_MODE_AUTO) {
                    FanController.FAN_MODE_AUTO
                } else {
                    FanController.FAN_MODE_MANUAL
                },
                manualLevel = manualLevel.coerceIn(MIN_SPEED, MAX_SPEED),
                curve = normalizeCurve(curve),
            )
    }

    fun load(): Config {
        val file = File(CONFIG_PATH)
        if (!file.isFile) return Config()

        var enabled = false
        var mode = FanController.FAN_MODE_MANUAL
        var manualLevel = DEFAULT_SPEED
        var curve = DEFAULT_CURVE.copyOf()

        runCatching {
            file.forEachLine { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val separator = line.indexOf('=')
                if (separator <= 0) return@forEachLine

                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                when (key) {
                    "enabled" -> enabled = value == "1" || value.equals("true", true)
                    "mode" -> mode = value
                    "manual_level" -> manualLevel = value.toIntOrNull() ?: manualLevel
                    "curve" -> {
                        val parsed = value
                            .split(',')
                            .mapNotNull { it.trim().toIntOrNull() }
                        if (parsed.size == 5) curve = parsed.toIntArray()
                    }
                }
            }
        }.onFailure { error ->
            Log.e(TAG, "Unable to read fan configuration", error)
            return Config()
        }

        return Config(enabled, mode, manualLevel, curve).normalized()
    }

    fun save(config: Config): Boolean {
        val normalized = config.normalized()
        val directory = File(CONFIG_DIR)
        if (!directory.exists() && !directory.mkdirs()) {
            Log.e(TAG, "Unable to create $CONFIG_DIR")
            return false
        }

        val content = buildString {
            appendLine("version=1")
            appendLine("enabled=${if (normalized.enabled) 1 else 0}")
            appendLine("mode=${normalized.mode}")
            appendLine("manual_level=${normalized.manualLevel}")
            appendLine("curve=${normalized.curve.joinToString(",")}")
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
            Log.e(TAG, "Unable to save fan configuration", error)
        }.getOrDefault(false)
    }

    fun update(transform: (Config) -> Config): Config {
        val updated = transform(load()).normalized()
        save(updated)
        return updated
    }

    fun normalizeCurve(input: IntArray): IntArray {
        if (input.size != 5) return DEFAULT_CURVE.copyOf()

        val output = IntArray(5)
        input.forEachIndexed { index, value ->
            val minimum = if (index == 0) MIN_CURVE_TEMP else output[index - 1] + 1
            val maximum = MAX_CURVE_TEMP - (4 - index)
            output[index] = value.coerceIn(minimum, maximum)
        }
        return output
    }
}
