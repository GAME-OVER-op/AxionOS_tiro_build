/*
 * SPDX-FileCopyrightText: 2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.lighting

import android.util.Log
import java.io.File
import java.io.FileOutputStream

internal object LightingRuntimeStore {
    private const val TAG = "LightingRuntimeStore"
    private const val CONFIG_DIR = "/data/system/nubia_lighting"
    private const val RUNTIME_PATH = "$CONFIG_DIR/runtime.conf"
    private const val TEMP_PATH = "$CONFIG_DIR/runtime.conf.tmp"

    data class State(
        val screenOn: Boolean = true,
        val notificationActive: Boolean = false,
    )

    @Synchronized
    fun load(): State {
        val file = File(RUNTIME_PATH)
        if (!file.isFile) return State()

        var state = State()
        runCatching {
            file.forEachLine { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val separator = line.indexOf('=')
                if (separator <= 0) return@forEachLine
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                state = when (key) {
                    "screen_on" -> state.copy(screenOn = parseBool(value, state.screenOn))
                    "notification_active" -> state.copy(
                        notificationActive = parseBool(value, state.notificationActive),
                    )
                    else -> state
                }
            }
        }.onFailure { error ->
            Log.e(TAG, "Unable to read lighting runtime state", error)
            return State()
        }
        return state
    }

    @Synchronized
    fun update(transform: (State) -> State) {
        val current = load()
        val updated = transform(current)
        if (updated != current) save(updated)
    }

    @Synchronized
    fun save(state: State): Boolean {
        val directory = File(CONFIG_DIR)
        if (!directory.exists() && !directory.mkdirs()) {
            Log.e(TAG, "Unable to create $CONFIG_DIR")
            return false
        }

        val content = buildString {
            appendLine("version=1")
            appendLine("screen_on=${state.screenOn.asFlag()}")
            appendLine("notification_active=${state.notificationActive.asFlag()}")
        }

        val temporary = File(TEMP_PATH)
        val target = File(RUNTIME_PATH)
        return runCatching {
            FileOutputStream(temporary, false).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            true
        }.onFailure { error ->
            temporary.delete()
            Log.e(TAG, "Unable to save lighting runtime state", error)
        }.getOrDefault(false)
    }

    fun setScreenOn(screenOn: Boolean) = update { it.copy(screenOn = screenOn) }

    fun setNotificationActive(active: Boolean) = update { it.copy(notificationActive = active) }

    private fun parseBool(value: String, fallback: Boolean): Boolean = when {
        value == "1" || value.equals("true", true) || value.equals("on", true) -> true
        value == "0" || value.equals("false", true) || value.equals("off", true) -> false
        else -> fallback
    }

    private fun Boolean.asFlag(): Int = if (this) 1 else 0
}
