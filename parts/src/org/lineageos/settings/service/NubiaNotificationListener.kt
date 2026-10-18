/*
 * SPDX-FileCopyrightText: 2025-2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.lineageos.settings.lighting.LightingRuntimeController

class NubiaNotificationListener : NotificationListenerService() {
    private val activeKeys = linkedSetOf<String>()
    private var lastPublishedActive: Boolean? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeKeys.clear()
        activeNotifications.orEmpty()
            .filter(::isEligible)
            .forEach { activeKeys += it.key }
        publishState()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isEligible(sbn)) {
            activeKeys += sbn.key
        } else {
            activeKeys -= sbn.key
        }
        publishState()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        activeKeys -= sbn.key
        publishState()
    }

    override fun onListenerDisconnected() {
        activeKeys.clear()
        publishState()
        super.onListenerDisconnected()
    }

    private fun publishState() {
        val active = activeKeys.isNotEmpty()
        if (lastPublishedActive == active) return
        lastPublishedActive = active
        LightingRuntimeController.setNotificationActive(active)
    }

    private fun isEligible(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification
        return sbn.isClearable &&
            sbn.packageName != packageName &&
            notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
            notification.flags and Notification.FLAG_ONGOING_EVENT == 0
    }
}
