/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.trigger

import android.content.Context
import org.lineageos.settings.utils.*

object TriggerController {

    const val KEY_TRIGGER_ENABLE = "trigger_enable"

    /** Hardware sysfs is owned solely by the native trigger service. */
    fun setTriggerEnabled(context: Context, enabled: Boolean) {
        putInt(context, KEY_TRIGGER_ENABLE, if (enabled) 1 else 0)
        TriggerRuntimeController.publish()
    }

    fun applySettings(context: Context, enabled: Boolean) = setTriggerEnabled(context, enabled)

    fun restoreSettings(context: Context) {
        TriggerRuntimeController.initialize(context)
        TriggerRuntimeController.publish()
    }
}
