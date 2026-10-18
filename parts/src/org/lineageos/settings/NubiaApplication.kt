/*
 * SPDX-FileCopyrightText: 2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings

import android.app.Application
import org.lineageos.settings.lighting.LightingRuntimeController
import org.lineageos.settings.trigger.TriggerRuntimeController

class NubiaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LightingRuntimeController.initialize(this)
        TriggerRuntimeController.initialize(this)
    }
}
