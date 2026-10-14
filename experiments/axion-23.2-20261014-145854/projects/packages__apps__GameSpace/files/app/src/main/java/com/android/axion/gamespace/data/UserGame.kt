/*
 * Copyright (C) 2021 Chaldeaprjkt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.axion.gamespace.data

import android.app.GameManager


data class UserGame(
    val packageName: String,
    val mode: Int = GameManager.GAME_MODE_STANDARD,
    val performanceMode: Int = PERFORMANCE_MODE_BALANCE,
    val force120Hz: Boolean = false,
    val highTouchPolling: Boolean = false,
) {
    override fun toString(): String = listOf(
        packageName,
        mode,
        cpuProfileForMode(mode),
        if (force120Hz) 1 else 0,
        if (highTouchPolling) 1 else 0,
    ).joinToString("=")

    companion object {
        fun cpuProfileForMode(mode: Int): Int = when (mode) {
            GameManager.GAME_MODE_BATTERY -> PERFORMANCE_MODE_ECO
            GameManager.GAME_MODE_PERFORMANCE -> PERFORMANCE_MODE_BOOST
            else -> PERFORMANCE_MODE_BALANCE
        }

        const val PERFORMANCE_MODE_ECO = 100
        const val PERFORMANCE_MODE_BALANCE = 200
        const val PERFORMANCE_MODE_BOOST = 300
        // Legacy "Beyond" becomes ordinary Performance; never pin minimum clocks.
        const val PERFORMANCE_MODE_BEYOND = 400

        fun fromSettings(data: String): UserGame {
            val fields = data.split("=")
            val packageName = fields.firstOrNull().orEmpty()
            if (packageName.isEmpty()) return UserGame(data)

            val mode = fields.getOrNull(1)?.toIntOrNull()
                ?: GameManager.GAME_MODE_STANDARD
            return UserGame(
                packageName = packageName,
                mode = mode,
                performanceMode = cpuProfileForMode(mode),
                force120Hz = fields.getOrNull(3) == "1",
                highTouchPolling = fields.getOrNull(4) == "1",
            )
        }
    }
}
