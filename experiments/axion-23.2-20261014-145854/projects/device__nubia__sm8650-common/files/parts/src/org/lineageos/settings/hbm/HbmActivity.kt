/*
 * SPDX-FileCopyrightText: 2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.hbm

import android.os.Bundle
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import com.android.settingslib.collapsingtoolbar.R

class HbmActivity : CollapsingToolbarBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.content_frame, HbmFragment(), TAG)
                .commit()
        }
    }

    companion object {
        private const val TAG = "hbm"
    }
}
