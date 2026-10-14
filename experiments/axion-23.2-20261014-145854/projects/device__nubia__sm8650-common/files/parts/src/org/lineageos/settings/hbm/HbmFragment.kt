/*
 * SPDX-FileCopyrightText: 2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.hbm

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import com.android.settingslib.widget.SliderPreference
import org.lineageos.settings.R

class HbmFragment : SettingsBasePreferenceFragment(), Preference.OnPreferenceChangeListener {

    private lateinit var modePreference: ListPreference
    private lateinit var luxOnPreference: SliderPreference
    private lateinit var luxOffPreference: SliderPreference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.hbm_preferences, rootKey)

        modePreference = findPreference<ListPreference>(HbmController.KEY_MODE)!!.apply {
            isPersistent = false
            value = HbmController.currentMode(requireContext())
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            onPreferenceChangeListener = this@HbmFragment
        }

        luxOnPreference = findPreference<SliderPreference>(HbmController.KEY_LUX_ON)!!.apply {
            isPersistent = false
            setMin(HbmController.MIN_LUX_ON)
            setMax(HbmController.MAX_LUX)
            setSliderIncrement(HbmController.LUX_STEP)
            setValue(HbmController.getLuxOn(requireContext()))
            setShowSliderValue(true)
            onPreferenceChangeListener = this@HbmFragment
        }

        luxOffPreference = findPreference<SliderPreference>(HbmController.KEY_LUX_OFF)!!.apply {
            isPersistent = false
            setMin(HbmController.MIN_LUX_OFF)
            setMax(HbmController.MAX_LUX)
            setSliderIncrement(HbmController.LUX_STEP)
            setValue(HbmController.getLuxOff(requireContext()))
            setShowSliderValue(true)
            onPreferenceChangeListener = this@HbmFragment
        }

        updateThresholdState(modePreference.value)
    }

    override fun onResume() {
        super.onResume()
        val mode = HbmController.currentMode(requireContext())
        modePreference.value = mode
        luxOnPreference.setValue(HbmController.getLuxOn(requireContext()))
        luxOffPreference.setValue(HbmController.getLuxOff(requireContext()))
        updateThresholdState(mode)
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean =
        when (preference.key) {
            HbmController.KEY_MODE -> {
                val mode = newValue as String
                HbmController.setMode(requireContext(), mode)
                updateThresholdState(mode)
                true
            }
            HbmController.KEY_LUX_ON -> {
                HbmController.setLuxOn(requireContext(), newValue as Int)
                luxOffPreference.setValue(HbmController.getLuxOff(requireContext()))
                true
            }
            HbmController.KEY_LUX_OFF -> {
                HbmController.setLuxOff(requireContext(), newValue as Int)
                luxOffPreference.setValue(HbmController.getLuxOff(requireContext()))
                true
            }
            else -> false
        }

    private fun updateThresholdState(mode: String?) {
        val enabled = mode == HbmController.MODE_AUTO
        luxOnPreference.isEnabled = enabled
        luxOffPreference.isEnabled = enabled
    }
}
