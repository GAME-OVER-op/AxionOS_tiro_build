/*
 * Copyright (C) 2026 AxionOS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.axion.gamespace.gamebar

import android.app.Service
import android.content.Intent
import android.hardware.input.InputManager
import android.os.IBinder
import android.os.SystemClock
import android.os.SystemProperties
import android.util.Log
import android.view.KeyEvent
import com.android.axion.gamespace.gamebar.mapper.KeyInputMonitor
import com.android.axion.gamespace.settings.SettingsActivity

/** Bridges the Tiro hardware game slider (KEY_RED/KEY_GREEN) to GameSpace. */
class GameSliderService : Service() {
    private lateinit var inputMonitor: KeyInputMonitor
    private var lastEventAt = 0L

    override fun onCreate() {
        super.onCreate()
        inputMonitor = KeyInputMonitor(
            getSystemService(InputManager::class.java),
            receiveAllInput = true,
            onKeyEvent = { event ->
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    handleKey(event.keyCode)
                }
            },
        )
        runCatching { inputMonitor.start() }
            .onFailure { Log.e("GameSliderService", "Cannot monitor slider keys", it) }
    }

    private fun handleKey(keyCode: Int) {
        if (!SystemProperties.getBoolean(PROP_ENABLED, true)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastEventAt < DEBOUNCE_MS) return

        val inverted = SystemProperties.getBoolean(PROP_INVERTED, false)
        val enable = when (keyCode) {
            KeyEvent.KEYCODE_PROG_RED -> !inverted
            KeyEvent.KEYCODE_PROG_GREEN -> inverted
            else -> return
        }
        lastEventAt = now

        if (enable) {
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            })
        } else {
            sendBroadcast(Intent(SettingsActivity.ACTION_CLOSE_FROM_SLIDER).setPackage(packageName))
            startService(Intent(this, SessionService::class.java).setAction(SessionService.ACTION_STOP))
        }
    }

    override fun onDestroy() {
        inputMonitor.stop()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val DEBOUNCE_MS = 250L
        private const val PROP_ENABLED = "persist.axion.gamespace.slider_enabled"
        private const val PROP_INVERTED = "persist.axion.gamespace.slider_inverted"
    }
}
