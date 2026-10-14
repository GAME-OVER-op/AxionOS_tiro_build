/*
 * SPDX-FileCopyrightText: 2025-2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.lighting

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import org.lineageos.settings.R
import org.lineageos.settings.utils.getInt
import org.lineageos.settings.utils.getString

class LightingFragment : SettingsBasePreferenceFragment(), Preference.OnPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.lighting_preferences)
        setupGroup(
            LightingController.KEY_FAN_LED_ENABLE,
            LightingController.KEY_FAN_LED_MODE,
            LightingController.KEY_FAN_LED_CODE,
            LightingController.FAN_MODE_STATIC_SOLID,
            LightingController.fanModes,
            true,
        )
        setupGroup(
            LightingController.KEY_TRIGGER_LED_ENABLE,
            LightingController.KEY_TRIGGER_LED_MODE,
            LightingController.KEY_TRIGGER_LED_CODE,
            LightingController.EXTERNAL_MODE_STATIC,
            LightingController.externalModes,
            false,
        )
        setupGroup(
            LightingController.KEY_NOTIFICATION_LED_ENABLE,
            LightingController.KEY_NOTIFICATION_LED_MODE,
            LightingController.KEY_NOTIFICATION_LED_CODE,
            LightingController.EXTERNAL_MODE_BREATHE,
            LightingController.externalModes,
            false,
        )
    }

    private fun setupGroup(
        enableKey: String,
        modeKey: String,
        codeKey: String,
        defaultMode: String,
        modes: LinkedHashMap<String, String>,
        fan: Boolean,
    ) {
        val enabled = getInt(requireContext(), enableKey, 0) == 1
        val mode = getString(requireContext(), modeKey, defaultMode)

        findPreference<SwitchPreferenceCompat>(enableKey)!!.apply {
            isChecked = enabled
            onPreferenceChangeListener = this@LightingFragment
        }
        findPreference<ListPreference>(modeKey)!!.apply {
            entries = modes.values.toTypedArray()
            entryValues = modes.keys.toTypedArray()
            value = if (mode in modes) mode else defaultMode
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            isEnabled = enabled
            onPreferenceChangeListener = this@LightingFragment
        }
        updateEffects(modeKey, codeKey, fan, enabled)
    }

    private fun updateEffects(modeKey: String, codeKey: String, fan: Boolean, enabled: Boolean) {
        val mode = findPreference<ListPreference>(modeKey)!!.value
        val effects = if (fan) {
            LightingController.fanEffects(mode)
        } else {
            LightingController.externalEffects(mode)
        }
        findPreference<ListPreference>(codeKey)!!.apply {
            entries = effects.map { it.label }.toTypedArray()
            entryValues = effects.map { it.code }.toTypedArray()
            val stored = getString(requireContext(), codeKey, effects.first().code).lowercase()
            value = effects.firstOrNull { it.code == stored }?.code ?: effects.first().code
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            isEnabled = enabled
            onPreferenceChangeListener = this@LightingFragment
        }
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        val key = preference.key
        return when (key) {
            LightingController.KEY_FAN_LED_ENABLE,
            LightingController.KEY_TRIGGER_LED_ENABLE,
            LightingController.KEY_NOTIFICATION_LED_ENABLE -> {
                val enabled = newValue as Boolean
                LightingController.setEnabled(requireContext(), key, enabled)
                val prefix = key.removeSuffix("_enable")
                findPreference<ListPreference>("${prefix}_mode")?.isEnabled = enabled
                findPreference<ListPreference>("${prefix}_code")?.isEnabled = enabled
                true
            }
            LightingController.KEY_FAN_LED_MODE -> {
                val mode = newValue as String
                LightingController.setMode(requireContext(), key, mode)
                findPreference<ListPreference>(key)!!.value = mode
                updateEffects(key, LightingController.KEY_FAN_LED_CODE, true, true)
                true
            }
            LightingController.KEY_TRIGGER_LED_MODE -> {
                val mode = newValue as String
                LightingController.setMode(requireContext(), key, mode)
                findPreference<ListPreference>(key)!!.value = mode
                updateEffects(key, LightingController.KEY_TRIGGER_LED_CODE, false, true)
                true
            }
            LightingController.KEY_NOTIFICATION_LED_MODE -> {
                val mode = newValue as String
                LightingController.setMode(requireContext(), key, mode)
                findPreference<ListPreference>(key)!!.value = mode
                updateEffects(key, LightingController.KEY_NOTIFICATION_LED_CODE, false, true)
                true
            }
            LightingController.KEY_FAN_LED_CODE,
            LightingController.KEY_TRIGGER_LED_CODE,
            LightingController.KEY_NOTIFICATION_LED_CODE -> {
                LightingController.setCode(requireContext(), key, newValue as String)
                true
            }
            else -> false
        }
    }
}
