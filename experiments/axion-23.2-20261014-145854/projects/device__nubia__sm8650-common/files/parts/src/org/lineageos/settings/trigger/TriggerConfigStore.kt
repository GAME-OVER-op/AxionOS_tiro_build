/*
 * SPDX-FileCopyrightText: 2025-2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.trigger

import android.content.Context
import org.lineageos.settings.service.HardwareControlService
import org.lineageos.settings.utils.getString
import org.lineageos.settings.utils.putString

object TriggerConfigStore {

    const val KEY_SELECTED_APPS = "trigger_selected_apps"
    private const val KEY_CONFIG_PREFIX = "trigger_config_"

    data class Side(
        val enabled: Boolean = false,
        val x: Int = 0,
        val y: Int = 0,
        val rotation: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
    ) {
        val hasCoordinates: Boolean
            get() = x > 0 || y > 0
    }

    data class Mapping(
        val left: Side = Side(),
        val right: Side = Side(),
    )

    fun selectedApps(context: Context): LinkedHashSet<String> =
        getString(context, KEY_SELECTED_APPS, "")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toCollection(LinkedHashSet())

    fun setSelectedApps(context: Context, packages: Collection<String>) {
        putString(context, KEY_SELECTED_APPS, packages.distinct().sorted().joinToString("\n"))
        HardwareControlService.requestUpdate(context)
    }

    fun setSelected(context: Context, packageName: String, selected: Boolean) {
        val apps = selectedApps(context)
        if (selected) apps += packageName else apps -= packageName
        setSelectedApps(context, apps)
    }

    fun read(context: Context, packageName: String): Mapping =
        decode(getString(context, configKey(packageName), ""))

    fun write(context: Context, packageName: String, mapping: Mapping) {
        putString(context, configKey(packageName), encode(mapping))
        HardwareControlService.requestUpdate(context)
    }

    private fun configKey(packageName: String): String =
        KEY_CONFIG_PREFIX + packageName.map { if (it.isLetterOrDigit() || it == '.') it else '_' }.joinToString("")

    private fun encodeSide(side: Side): String = listOf(
        if (side.enabled) 1 else 0,
        side.x,
        side.y,
        side.rotation.coerceIn(0, 3),
        side.width.coerceAtLeast(0),
        side.height.coerceAtLeast(0),
    ).joinToString(",")

    private fun decodeSide(value: String?): Side {
        val p = value.orEmpty().split(',')
        if (p.size != 6) return Side()
        return Side(
            enabled = p[0] == "1",
            x = p[1].toIntOrNull()?.coerceAtLeast(0) ?: 0,
            y = p[2].toIntOrNull()?.coerceAtLeast(0) ?: 0,
            rotation = p[3].toIntOrNull()?.coerceIn(0, 3) ?: 0,
            width = p[4].toIntOrNull()?.coerceAtLeast(0) ?: 0,
            height = p[5].toIntOrNull()?.coerceAtLeast(0) ?: 0,
        )
    }

    private fun encode(mapping: Mapping): String =
        "v1;${encodeSide(mapping.left)};${encodeSide(mapping.right)}"

    private fun decode(value: String): Mapping {
        val p = value.split(';')
        if (p.size != 3 || p[0] != "v1") return Mapping()
        return Mapping(decodeSide(p[1]), decodeSide(p[2]))
    }
}
