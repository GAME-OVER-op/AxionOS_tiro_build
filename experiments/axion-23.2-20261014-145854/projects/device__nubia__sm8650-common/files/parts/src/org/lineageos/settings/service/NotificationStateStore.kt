/*
 * SPDX-FileCopyrightText: 2025-2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.service

import java.util.concurrent.ConcurrentHashMap

object NotificationStateStore {
    private val activeKeys = ConcurrentHashMap.newKeySet<String>()

    fun replace(keys: Collection<String>) {
        activeKeys.clear()
        activeKeys.addAll(keys)
    }

    fun add(key: String) {
        activeKeys += key
    }

    fun remove(key: String) {
        activeKeys -= key
    }

    fun hasActiveNotifications(): Boolean = activeKeys.isNotEmpty()
}
