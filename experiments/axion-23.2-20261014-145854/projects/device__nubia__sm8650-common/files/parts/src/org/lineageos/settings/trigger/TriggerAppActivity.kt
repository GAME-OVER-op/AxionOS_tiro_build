/*
 * SPDX-FileCopyrightText: 2025-2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.trigger

import android.os.Bundle
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import com.android.settingslib.collapsingtoolbar.R

class TriggerAppActivity : CollapsingToolbarBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: run {
            finish()
            return
        }
        title = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))
        } catch (_: Throwable) {
            packageName
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.content_frame, TriggerAppFragment.newInstance(packageName), "trigger_app")
                .commit()
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "package_name"
    }
}
