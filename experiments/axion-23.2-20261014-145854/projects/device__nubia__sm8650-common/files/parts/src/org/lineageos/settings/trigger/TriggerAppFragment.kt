/*
 * SPDX-FileCopyrightText: 2025-2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.trigger

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import org.lineageos.settings.R

class TriggerAppFragment : SettingsBasePreferenceFragment(), Preference.OnPreferenceChangeListener {

    private lateinit var packageName: String
    private lateinit var mapping: TriggerConfigStore.Mapping

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        packageName = requireArguments().getString(ARG_PACKAGE).orEmpty()
        if (packageName.isEmpty()) {
            requireActivity().finish()
            return
        }
        addPreferencesFromResource(R.xml.trigger_app_preferences)
        refresh()

        findPreference<SwitchPreferenceCompat>(KEY_LEFT)!!.onPreferenceChangeListener = this
        findPreference<SwitchPreferenceCompat>(KEY_RIGHT)!!.onPreferenceChangeListener = this
        findPreference<Preference>(KEY_CALIBRATE)!!.setOnPreferenceClickListener {
            launchCalibration()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        if (::packageName.isInitialized && packageName.isNotEmpty()) refresh()
    }

    private fun refresh() {
        mapping = TriggerConfigStore.read(requireContext(), packageName)
        findPreference<SwitchPreferenceCompat>(KEY_LEFT)?.apply {
            isChecked = mapping.left.enabled
            summary = sideSummary(mapping.left)
        }
        findPreference<SwitchPreferenceCompat>(KEY_RIGHT)?.apply {
            isChecked = mapping.right.enabled
            summary = sideSummary(mapping.right)
        }
    }

    private fun sideSummary(side: TriggerConfigStore.Side): String =
        if (side.hasCoordinates) {
            getString(R.string.trigger_coordinates_summary, side.x, side.y)
        } else {
            getString(R.string.trigger_coordinates_missing)
        }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        val enabled = newValue as Boolean
        mapping = when (preference.key) {
            KEY_LEFT -> mapping.copy(left = mapping.left.copy(enabled = enabled))
            KEY_RIGHT -> mapping.copy(right = mapping.right.copy(enabled = enabled))
            else -> return false
        }
        TriggerConfigStore.write(requireContext(), packageName, mapping)
        return true
    }

    private fun launchCalibration() {
        if (!mapping.left.enabled && !mapping.right.enabled) {
            Toast.makeText(requireContext(), R.string.trigger_enable_side_first, Toast.LENGTH_LONG).show()
            return
        }
        val launchIntent = requireContext().packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Toast.makeText(requireContext(), R.string.trigger_app_launch_failed, Toast.LENGTH_LONG).show()
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(requireContext(), TriggerCalibrationActivity::class.java).apply {
                putExtra(TriggerAppActivity.EXTRA_PACKAGE, packageName)
            })
        }, 900L)
    }

    companion object {
        private const val ARG_PACKAGE = "package"
        private const val KEY_LEFT = "trigger_left"
        private const val KEY_RIGHT = "trigger_right"
        private const val KEY_CALIBRATE = "trigger_calibrate"

        fun newInstance(packageName: String) = TriggerAppFragment().apply {
            arguments = Bundle().apply { putString(ARG_PACKAGE, packageName) }
        }
    }
}
