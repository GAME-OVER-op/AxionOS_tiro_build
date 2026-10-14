/*
 * SPDX-FileCopyrightText: 2025-2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.fan

import android.app.AlertDialog
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import androidx.preference.Preference
import org.lineageos.settings.R

class FanCurvePreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {

    init {
        refreshSummary()
        setOnPreferenceClickListener {
            showEditor()
            true
        }
    }

    private fun showEditor() {
        val values = FanController.getFanCurve(context)
        val pickers = ArrayList<NumberPicker>(5)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }

        values.forEachIndexed { index, value ->
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(82), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            column.addView(TextView(context).apply {
                text = context.getString(R.string.fan_curve_speed, index + 1)
                gravity = Gravity.CENTER
            })
            val picker = NumberPicker(context).apply {
                minValue = 20
                maxValue = 110
                this.value = value
                wrapSelectorWheel = false
            }
            pickers += picker
            column.addView(picker)
            column.addView(TextView(context).apply {
                text = context.getString(R.string.fan_curve_temperature_unit)
                gravity = Gravity.CENTER
            })
            row.addView(column)
        }

        val scroll = HorizontalScrollView(context).apply { addView(row) }
        AlertDialog.Builder(context)
            .setTitle(R.string.fan_curve_dialog_title)
            .setMessage(R.string.fan_curve_dialog_message)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                FanController.setFanCurve(context, pickers.map { it.value }.toIntArray())
                refreshSummary()
            }
            .show()
    }

    private fun refreshSummary() {
        summary = FanController.getFanCurve(context)
            .mapIndexed { index, value -> "${index + 1} → ${value}°C" }
            .joinToString("  ·  ")
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
