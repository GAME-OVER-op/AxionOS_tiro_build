/*
 * Copyright (C) 2026 AxionOS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.axion.gamespace.utils

import android.os.ServiceManager
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import vendor.lineage.touch.IHighTouchPollingRate

@Singleton
class HighTouchPollingController @Inject constructor() {
    fun setEnabled(enabled: Boolean): Boolean = try {
        val binder = ServiceManager.checkService(SERVICE_NAME)
            ?: error("$SERVICE_NAME is unavailable")
        IHighTouchPollingRate.Stub.asInterface(binder).setEnabled(enabled)
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to set high touch polling to $enabled", e)
        false
    }

    companion object {
        private const val TAG = "GameSpaceTouch"
        private const val SERVICE_NAME =
            "vendor.lineage.touch.IHighTouchPollingRate/default"
    }
}
