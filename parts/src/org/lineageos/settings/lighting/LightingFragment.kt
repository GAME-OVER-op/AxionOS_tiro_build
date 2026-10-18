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

class LightingFragment : SettingsBasePreferenceFragment(), Preference.OnPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.lighting_preferences)
        val config = LightingController.config(requireContext())

        setupGroup(
            LightingController.KEY_FAN_LED_ENABLE,
            LightingController.KEY_FAN_LED_MODE,
            LightingController.KEY_FAN_LED_CODE,
            config.fanEnabled,
            config.fanMode,
            config.fanCode,
            LightingController.fanModes,
            true,
        )
        setupGroup(
            LightingController.KEY_TRIGGER_LED_ENABLE,
            LightingController.KEY_TRIGGER_LED_MODE,
            LightingController.KEY_TRIGGER_LED_CODE,
            config.triggerEnabled,
            config.triggerMode,
            config.triggerCode,
            LightingController.externalModes,
            false,
        )
        setupGroup(
            LightingController.KEY_NOTIFICATION_LED_ENABLE,
            LightingController.KEY_NOTIFICATION_LED_MODE,
            LightingController.KEY_NOTIFICATION_LED_CODE,
            config.notificationEnabled,
            config.notificationMode,
            config.notificationCode,
            LightingController.externalModes,
            false,
        )
    }

    private fun setupGroup(
        enableKey: String,
        modeKey: String,
        codeKey: String,
        enabled: Boolean,
        mode: String,
        code: String,
        modes: LinkedHashMap<String, String>,
        fan: Boolean,
    ) {
        findPreference<SwitchPreferenceCompat>(enableKey)!!.apply {
            isChecked = enabled
            onPreferenceChangeListener = this@LightingFragment
        }
        findPreference<ListPreference>(modeKey)!!.apply {
            entries = modes.values.toTypedArray()
            entryValues = modes.keys.toTypedArray()
            value = mode.takeIf { it in modes } ?: modes.keys.first()
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            isEnabled = enabled
            onPreferenceChangeListener = this@LightingFragment
        }
        updateEffects(modeKey, codeKey, fan, enabled, code)
    }

    private fun updateEffects(
        modeKey: String,
        codeKey: String,
        fan: Boolean,
        enabled: Boolean,
        preferredCode: String? = null,
    ) {
        val mode = findPreference<ListPreference>(modeKey)!!.value
        val effects = if (fan) {
            LightingController.fanEffects(mode)
        } else {
            LightingController.externalEffects(mode)
        }
        findPreference<ListPreference>(codeKey)!!.apply {
            entries = effects.map { it.label }.toTypedArray()
            entryValues = effects.map { it.code }.toTypedArray()
            val requested = preferredCode?.lowercase()
            value = effects.firstOrNull { it.code == requested }?.code ?: effects.first().code
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
                val code = LightingController.config(requireContext()).fanCode
                updateEffects(key, LightingController.KEY_FAN_LED_CODE, true, true, code)
                true
            }
            LightingController.KEY_TRIGGER_LED_MODE -> {
                val mode = newValue as String
                LightingController.setMode(requireContext(), key, mode)
                findPreference<ListPreference>(key)!!.value = mode
                val code = LightingController.config(requireContext()).triggerCode
                updateEffects(key, LightingController.KEY_TRIGGER_LED_CODE, false, true, code)
                true
            }
            LightingController.KEY_NOTIFICATION_LED_MODE -> {
                val mode = newValue as String
                LightingController.setMode(requireContext(), key, mode)
                findPreference<ListPreference>(key)!!.value = mode
                val code = LightingController.config(requireContext()).notificationCode
                updateEffects(key, LightingController.KEY_NOTIFICATION_LED_CODE, false, true, code)
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
