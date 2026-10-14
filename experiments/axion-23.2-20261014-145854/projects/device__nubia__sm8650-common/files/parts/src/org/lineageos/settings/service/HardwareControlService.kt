/*
 * SPDX-FileCopyrightText: 2025-2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.service

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets
import org.lineageos.settings.fan.FanController
import org.lineageos.settings.lighting.LightingController
import org.lineageos.settings.trigger.TriggerConfigStore
import org.lineageos.settings.trigger.TriggerController
import org.lineageos.settings.utils.getInt
import org.lineageos.settings.utils.getString

class HardwareControlService : Service() {

    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private val stateFile = File(STATE_FILE)

    private val heartbeat = object : Runnable {
        override fun run() {
            writeState()
            worker.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("NubiaHardwareControl").apply { start() }
        worker = Handler(workerThread.looper)
        worker.post(heartbeat)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        worker.removeCallbacks(heartbeat)
        worker.post(heartbeat)
        return START_STICKY
    }

    override fun onDestroy() {
        worker.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun foregroundPackage(): String? = try {
        val activityManager = getSystemService(ActivityManager::class.java)
        activityManager.getRunningTasks(1).firstOrNull()?.topActivity?.packageName
    } catch (error: Throwable) {
        Log.w(TAG, "Unable to determine foreground package", error)
        null
    }

    private fun activeGamePerformanceMode(foreground: String?, screenOn: Boolean): Int {
        if (!screenOn || foreground.isNullOrEmpty()) return 0
        val raw = Settings.Global.getString(contentResolver, "NubiaperformanceMode")
            ?: return 0
        for (entry in raw.split(',')) {
            val index = entry.lastIndexOf('+')
            if (index < 1 || entry.substring(0, index) != foreground) continue
            return when (entry.substring(index + 1).toIntOrNull()) {
                100 -> 100 // Battery: 30% of native CPU maximum
                200 -> 200 // Standard: 70%
                300, 400 -> 300 // Performance: native maximum, no minimum boost
                else -> 0
            }
        }
        return 0
    }

    private fun writeState() {
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        val selectedApps = TriggerConfigStore.selectedApps(this)
        val foreground = foregroundPackage()
        val globalTriggerEnabled =
            getInt(this, TriggerController.KEY_TRIGGER_ENABLE, 0) == 1
        val candidateTriggerActive =
            screenOn && globalTriggerEnabled && foreground != null && foreground in selectedApps
        val mapping = if (candidateTriggerActive) {
            TriggerConfigStore.read(this, foreground!!)
        } else {
            TriggerConfigStore.Mapping()
        }
        val triggerActive = candidateTriggerActive &&
            (mapping.left.isReady || mapping.right.isReady)

        val curve = FanController.getFanCurve(this)
        val fanMode = getString(
            this,
            FanController.KEY_FAN_MODE,
            FanController.FAN_MODE_MANUAL,
        )

        val performanceMode = activeGamePerformanceMode(foreground, screenOn)
        val fields = ArrayList<String>(32).apply {
            add("v1")
            add(System.currentTimeMillis().toString())
            add(screenOn.asFlag())
            add(triggerActive.asFlag())
            addSide(mapping.left)
            addSide(mapping.right)
            add((getInt(this@HardwareControlService, FanController.KEY_FAN_ENABLE, 0) == 1).asFlag())
            add((fanMode == FanController.FAN_MODE_AUTO).asFlag())
            add(getInt(this@HardwareControlService, FanController.KEY_FAN_SPEED, FanController.FAN_DEFAULT_SPEED).coerceIn(1, 5).toString())
            curve.forEach { add(it.toString()) }
            add((getInt(this@HardwareControlService, LightingController.KEY_FAN_LED_ENABLE, 0) == 1).asFlag())
            add(LightingController.fanCode(this@HardwareControlService))
            add((getInt(this@HardwareControlService, LightingController.KEY_TRIGGER_LED_ENABLE, 0) == 1).asFlag())
            add(LightingController.triggerCode(this@HardwareControlService))
            add((getInt(this@HardwareControlService, LightingController.KEY_NOTIFICATION_LED_ENABLE, 0) == 1).asFlag())
            add(LightingController.notificationCode(this@HardwareControlService))
            add(NotificationStateStore.hasActiveNotifications().asFlag())
            add(performanceMode.toString())
        }

        val directory = File(STATE_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            Log.e(TAG, "Unable to create $STATE_DIRECTORY")
            return
        }

        try {
            // This is a heartbeat, so avoid AtomicFile's fsync on every second.
            // The native reader rejects a transient partial line and retries while
            // retaining the last valid state.
            stateFile.writeText(fields.joinToString(" ") + "\n", StandardCharsets.US_ASCII)
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to write hardware state", error)
        }
    }

    private fun MutableList<String>.addSide(side: TriggerConfigStore.Side) {
        add(side.isReady.asFlag())
        add(side.x.toString())
        add(side.y.toString())
        add(side.rotation.coerceIn(0, 3).toString())
        add(side.width.coerceAtLeast(0).toString())
        add(side.height.coerceAtLeast(0).toString())
    }

    private fun Boolean.asFlag(): String = if (this) "1" else "0"

    private val TriggerConfigStore.Side.isReady: Boolean
        get() = enabled && hasCoordinates && width > 0 && height > 0

    companion object {
        private const val TAG = "NubiaHardwareControl"
        private const val HEARTBEAT_MS = 1_000L
        private const val STATE_DIRECTORY = "/data/system/tiro_hardware"
        private const val STATE_FILE = "$STATE_DIRECTORY/state.conf"

        fun requestUpdate(context: Context) {
            try {
                context.startService(Intent(context, HardwareControlService::class.java))
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to start hardware service", error)
            }
        }
    }
}
