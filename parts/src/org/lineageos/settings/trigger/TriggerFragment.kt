/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.trigger

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import org.lineageos.settings.R
import org.lineageos.settings.utils.*

class TriggerFragment : SettingsBasePreferenceFragment(), Preference.OnPreferenceChangeListener {
    private lateinit var mSwitchBar: MainSwitchPreference
    private lateinit var selectedCategory: PreferenceCategory

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.trigger_preferences)
        mSwitchBar = findPreference<MainSwitchPreference>(TriggerController.KEY_TRIGGER_ENABLE)!!.apply {
            setChecked(getInt(requireContext(), TriggerController.KEY_TRIGGER_ENABLE, 0) == 1)
            onPreferenceChangeListener = this@TriggerFragment
        }
        selectedCategory = findPreference<PreferenceCategory>("trigger_selected")!!
        findPreference<Preference>("trigger_apps")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), TriggerAppsActivity::class.java))
            true
        }
    }

    override fun onResume() {
        super.onResume()
        if (::mSwitchBar.isInitialized) {
            mSwitchBar.setChecked(getInt(requireContext(), TriggerController.KEY_TRIGGER_ENABLE, 0) == 1)
        }
        if (::selectedCategory.isInitialized) showSelectedProfiles()
    }

    private fun showSelectedProfiles() {
        val context = requireContext()
        selectedCategory.removeAll()
        val profiles = TriggerProfileStore.load().sortedBy { profile ->
            try { context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(profile.pkg, 0)).toString() }
            catch (_: Exception) { profile.pkg }
        }
        if (profiles.isEmpty()) {
            selectedCategory.addPreference(Preference(context).apply {
                title = context.getString(R.string.trigger_no_selected_apps)
                isEnabled = false
                isIconSpaceReserved = false
            })
            return
        }
        profiles.forEach { profile ->
            val label = try {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(profile.pkg, 0)).toString()
            } catch (_: Exception) { profile.pkg }
            selectedCategory.addPreference(object : Preference(context) {
                init {
                    key = "trigger_profile_${profile.pkg}"
                    layoutResource = R.layout.trigger_selected_profile
                    isSelectable = false
                    isIconSpaceReserved = false
                }
                override fun onBindViewHolder(holder: PreferenceViewHolder) {
                    super.onBindViewHolder(holder)
                    holder.itemView.findViewById<ImageView>(R.id.trigger_selected_icon).setImageDrawable(
                        try { context.packageManager.getApplicationIcon(profile.pkg) }
                        catch (_: Exception) { null }
                    )
                    holder.itemView.findViewById<TextView>(R.id.trigger_selected_name).text = label
                    holder.itemView.findViewById<TextView>(R.id.trigger_selected_state).text =
                        context.getString(R.string.trigger_coord_summary,
                            bindingStatus(profile.left), bindingStatus(profile.right))
                    holder.itemView.findViewById<TextView>(R.id.trigger_selected_configure)
                        .setOnClickListener { openProfile(profile.pkg) }
                    holder.itemView.findViewById<TextView>(R.id.trigger_selected_reset)
                        .setOnClickListener { showResetMenu(profile.pkg) }
                    holder.itemView.findViewById<TextView>(R.id.trigger_selected_delete)
                        .setOnClickListener { confirmDelete(profile.pkg) }
                }
            })
        }
    }

    private fun bindingStatus(binding: TriggerProfileStore.Binding): String = when {
        !binding.enabled -> getString(R.string.trigger_disabled)
        binding.valid -> getString(R.string.trigger_assigned)
        else -> getString(R.string.trigger_unassigned)
    }

    private fun openProfile(pkg: String) {
        startActivity(Intent(requireContext(), TriggerProfileActivity::class.java)
            .putExtra(TriggerProfileActivity.EXTRA_PACKAGE, pkg))
    }

    private fun showSaveFailure() {
        Toast.makeText(requireContext(), R.string.trigger_save_failed, Toast.LENGTH_SHORT).show()
    }

    private fun showResetMenu(pkg: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.trigger_reset_coordinates)
            .setItems(arrayOf(getString(R.string.trigger_left),
                getString(R.string.trigger_right), getString(R.string.trigger_both))) { _, selection ->
                AlertDialog.Builder(requireContext())
                    .setMessage(R.string.trigger_confirm_reset)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.trigger_reset_coordinates) { _, _ ->
                        val profile = TriggerProfileStore.get(pkg)
                        val updated = profile.copy(
                            left = if (selection == 0 || selection == 2)
                                profile.left.copy(valid = false) else profile.left,
                            right = if (selection == 1 || selection == 2)
                                profile.right.copy(valid = false) else profile.right,
                        )
                        if (TriggerProfileStore.put(updated)) {
                            TriggerRuntimeController.publish()
                            showSelectedProfiles()
                        } else showSaveFailure()
                    }.show()
            }.show()
    }

    private fun confirmDelete(pkg: String) {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.trigger_confirm_delete)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.trigger_delete_profile) { _, _ ->
                if (TriggerProfileStore.delete(pkg)) {
                    TriggerRuntimeController.publish()
                    showSelectedProfiles()
                } else showSaveFailure()
            }.show()
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        return when (preference.key) {
            TriggerController.KEY_TRIGGER_ENABLE -> {
                TriggerController.setTriggerEnabled(requireContext(), newValue as Boolean)
                true
            }
            else -> false
        }
    }
}
