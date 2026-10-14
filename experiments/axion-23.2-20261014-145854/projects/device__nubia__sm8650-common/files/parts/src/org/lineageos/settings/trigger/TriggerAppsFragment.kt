/*
 * SPDX-FileCopyrightText: 2025-2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.trigger

import android.content.Intent
import android.os.Bundle
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import org.lineageos.settings.R

class TriggerAppsFragment : SettingsBasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        rebuild()
    }

    private fun rebuild() {
        val context = requireContext()
        val pm = context.packageManager
        val selected = TriggerConfigStore.selectedApps(context)
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcherIntent, 0)
            .distinctBy { it.activityInfo?.packageName }
            .mapNotNull { resolveInfo ->
                // Broken third-party labels/icons must not crash Nubia Settings.
                runCatching {
                    val packageName = resolveInfo.activityInfo.packageName
                    if (packageName == context.packageName) return@runCatching null
                    AppEntry(
                        packageName,
                        resolveInfo.loadLabel(pm).toString().ifBlank { packageName },
                        packageName in selected,
                    )
                }.getOrNull()
            }
            .sortedWith(compareByDescending<AppEntry> { it.selected }.thenBy { it.label.lowercase() })

        preferenceScreen = preferenceManager.createPreferenceScreen(context).apply {
            addPreference(PreferenceCategory(context).apply {
                title = getString(R.string.trigger_apps_list_title)
                apps.forEach { app ->
                    addPreference(SwitchPreferenceCompat(context).apply {
                        key = "trigger_app_${app.packageName}"
                        title = app.label
                        summary = app.packageName
                        isChecked = app.selected
                        setOnPreferenceChangeListener { _, newValue ->
                            TriggerConfigStore.setSelected(context, app.packageName, newValue as Boolean)
                            true
                        }
                    })
                }
            })
        }
    }

    private data class AppEntry(
        val packageName: String,
        val label: String,
        val selected: Boolean,
    )
}
