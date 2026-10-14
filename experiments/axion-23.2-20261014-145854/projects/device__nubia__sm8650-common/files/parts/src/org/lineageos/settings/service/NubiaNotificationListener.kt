/*
 * SPDX-FileCopyrightText: 2025-2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NubiaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationStateStore.replace(
            activeNotifications.orEmpty().filter(::isEligible).map { it.key },
        )
        HardwareControlService.requestUpdate(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isEligible(sbn)) NotificationStateStore.add(sbn.key)
        HardwareControlService.requestUpdate(this)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationStateStore.remove(sbn.key)
        HardwareControlService.requestUpdate(this)
    }

    override fun onListenerDisconnected() {
        NotificationStateStore.replace(emptyList())
        HardwareControlService.requestUpdate(this)
        super.onListenerDisconnected()
    }

    private fun isEligible(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification
        return sbn.isClearable &&
            sbn.packageName != packageName &&
            notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
            notification.flags and Notification.FLAG_ONGOING_EVENT == 0
    }
}
