/*
 * SPDX-FileCopyrightText: 2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.lighting

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import org.lineageos.settings.service.NubiaNotificationListener

object LightingRuntimeController {
    private const val TAG = "LightingRuntimeController"
    private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"

    @Volatile
    private var initialized = false

    private lateinit var appContext: Context

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> LightingRuntimeStore.setScreenOn(true)
                Intent.ACTION_SCREEN_OFF -> LightingRuntimeStore.setScreenOn(false)
            }
        }
    }

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) {
            LightingController.ensureInitialized()
            syncScreenState()
            ensureNotificationListenerAccess()
            return
        }
        appContext = context.applicationContext

        LightingController.ensureInitialized()
        syncScreenState()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        appContext.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        ensureNotificationListenerAccess()
        initialized = true
    }

    fun setNotificationActive(active: Boolean) {
        LightingRuntimeStore.setNotificationActive(active)
    }


    private fun syncScreenState() {
        LightingRuntimeStore.setScreenOn(
            appContext.getSystemService(PowerManager::class.java).isInteractive,
        )
    }

    private fun ensureNotificationListenerAccess() {
        runCatching {
            val component = ComponentName(appContext, NubiaNotificationListener::class.java)
            val flattened = component.flattenToString()
            val resolver = appContext.contentResolver
            val current = Settings.Secure.getString(
                resolver,
                ENABLED_NOTIFICATION_LISTENERS,
            ).orEmpty()
            val listeners = current
                .split(':')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableSet()

            if (listeners.add(flattened) &&
                !Settings.Secure.putString(
                    resolver,
                    ENABLED_NOTIFICATION_LISTENERS,
                    listeners.joinToString(":"),
                )
            ) {
                Log.w(TAG, "Unable to grant notification-listener access")
            }

            NotificationListenerService.requestRebind(component)
        }.onFailure { error ->
            Log.w(TAG, "Unable to initialize notification-listener access", error)
        }
    }
}
