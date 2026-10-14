/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.trigger

import android.os.Bundle
import android.content.Intent
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import org.lineageos.settings.R
import org.lineageos.settings.utils.*

class TriggerFragment : SettingsBasePreferenceFragment(), Preference.OnPreferenceChangeListener {

    private lateinit var mSwitchBar: MainSwitchPreference
    private lateinit var mSelectedApps: PreferenceCategory

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.trigger_preferences)

        val triggerEnabled = getInt(requireContext(), TriggerController.KEY_TRIGGER_ENABLE, 0) == 1

        mSwitchBar =
            findPreference<MainSwitchPreference>(TriggerController.KEY_TRIGGER_ENABLE)!!.apply {
                setChecked(triggerEnabled)
                onPreferenceChangeListener = this@TriggerFragment
            }

        mSelectedApps = findPreference(TriggerConfigStore.KEY_SELECTED_APPS)!!
        findPreference<Preference>("trigger_choose_apps")!!.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), TriggerAppsActivity::class.java))
            true
        }
        refreshSelectedApps()
    }

    override fun onResume() {
        super.onResume()
        if (::mSelectedApps.isInitialized) refreshSelectedApps()
    }

    private fun refreshSelectedApps() {
        mSelectedApps.removeAll()
        val packageManager = requireContext().packageManager
        val selected = TriggerConfigStore.selectedApps(requireContext())
        if (selected.isEmpty()) {
            mSelectedApps.addPreference(Preference(requireContext()).apply {
                title = getString(R.string.trigger_no_apps_selected)
                isSelectable = false
            })
            return
        }

        selected.mapNotNull { packageName ->
            try {
                val info = packageManager.getApplicationInfo(packageName, 0)
                Triple(packageName, packageManager.getApplicationLabel(info).toString(), info)
            } catch (_: Throwable) {
                null
            }
        }.sortedBy { it.second.lowercase() }.forEach { (packageName, label, info) ->
            val mapping = TriggerConfigStore.read(requireContext(), packageName)
            mSelectedApps.addPreference(Preference(requireContext()).apply {
                title = label
                icon = packageManager.getApplicationIcon(info)
                summary = mappingSummary(mapping)
                setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(), TriggerAppActivity::class.java).apply {
                        putExtra(TriggerAppActivity.EXTRA_PACKAGE, packageName)
                    })
                    true
                }
            })
        }
    }

    private fun mappingSummary(mapping: TriggerConfigStore.Mapping): String {
        val left = if (mapping.left.enabled) getString(R.string.state_enabled) else getString(R.string.state_disabled)
        val right = if (mapping.right.enabled) getString(R.string.state_enabled) else getString(R.string.state_disabled)
        return getString(R.string.trigger_app_summary, left, right)
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        return when (preference.key) {
            TriggerController.KEY_TRIGGER_ENABLE -> {
                val isEnabled = newValue as Boolean
                TriggerController.setTriggerEnabled(requireContext(), isEnabled)
                true
            }

            else -> false
        }
    }
}
