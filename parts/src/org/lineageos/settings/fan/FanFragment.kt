/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-FileCopyrightText: 2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.fan

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import com.android.settingslib.widget.SliderPreference
import org.lineageos.settings.R

class FanFragment : SettingsBasePreferenceFragment(), Preference.OnPreferenceChangeListener {

    private lateinit var mSwitchBar: MainSwitchPreference
    private lateinit var mFanSpeedBar: SliderPreference
    private lateinit var mFanMode: ListPreference
    private lateinit var mFanCurve: FanCurvePreference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.fan_preferences)

        val fanEnabled = FanController.isFanEnabled(requireContext())
        val savedSpeed = FanController.getFanSpeed(requireContext())
        val savedMode = FanController.getFanMode(requireContext())

        mSwitchBar =
            findPreference<MainSwitchPreference>(FanController.KEY_FAN_ENABLE)!!.apply {
                setChecked(fanEnabled)
                onPreferenceChangeListener = this@FanFragment
            }

        mFanMode =
            findPreference<ListPreference>(FanController.KEY_FAN_MODE)!!.apply {
                value = savedMode
                isEnabled = fanEnabled
                onPreferenceChangeListener = this@FanFragment
            }

        mFanSpeedBar =
            findPreference<SliderPreference>(FanController.KEY_FAN_SPEED)!!.apply {
                setMin(FanController.FAN_MIN_SPEED)
                setMax(FanController.FAN_MAX_SPEED)
                setSliderIncrement(1)
                setValue(savedSpeed)
                setShowSliderValue(true)
                onPreferenceChangeListener = this@FanFragment
            }

        mFanCurve =
            findPreference<FanCurvePreference>(FanController.KEY_FAN_CURVE)!!.apply {
                isEnabled = fanEnabled && savedMode == FanController.FAN_MODE_AUTO
            }

        updateModeState(savedMode, fanEnabled)
    }

    private fun updateModeState(mode: String, fanEnabled: Boolean = mSwitchBar.isChecked) {
        val automatic = mode == FanController.FAN_MODE_AUTO
        mFanMode.isEnabled = fanEnabled
        mFanSpeedBar.isVisible = !automatic
        mFanCurve.isVisible = automatic
        mFanSpeedBar.isEnabled = fanEnabled && !automatic
        mFanCurve.isEnabled = fanEnabled && automatic
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        return when (preference.key) {
            FanController.KEY_FAN_ENABLE -> {
                val enabled = newValue as Boolean
                FanController.setFanEnabled(requireContext(), enabled)
                updateModeState(mFanMode.value ?: FanController.FAN_MODE_MANUAL, enabled)
                true
            }

            FanController.KEY_FAN_SPEED -> {
                FanController.setFanSpeed(requireContext(), newValue as Int)
                true
            }

            FanController.KEY_FAN_MODE -> {
                val mode = newValue as String
                FanController.setFanMode(requireContext(), mode)
                updateModeState(mode)
                true
            }

            else -> false
        }
    }
}
