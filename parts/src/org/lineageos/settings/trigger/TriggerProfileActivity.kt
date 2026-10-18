/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.settings.trigger

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.ScrollView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.lineageos.settings.R

class TriggerProfileActivity : Activity() {
    private lateinit var pkg: String
    private lateinit var left: Switch
    private lateinit var right: Switch
    private lateinit var status: TextView
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun toast(id: Int) = Toast.makeText(this, id, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        if (!TriggerProfileStore.allowedPackage(pkg)) {finish();return}
        window.setDecorFitsSystemWindows(false)
        title = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)) }
        catch (_: Exception) {pkg}
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        fun button(id: Int, action: () -> Unit) {
            val b = Button(this).apply {setText(id);setOnClickListener {action()} }
            root.addView(b, LinearLayout.LayoutParams(-1, dp(54)))
        }
        val name = TextView(this).apply { text=pkg; textSize=13f }
        root.addView(name)
        left = Switch(this).apply {setText(R.string.trigger_left)}
        right = Switch(this).apply {setText(R.string.trigger_right)}
        root.addView(left);root.addView(right)
        status = TextView(this).apply {textSize=13f;setPadding(0,dp(12),0,dp(12))}
        root.addView(status)
        button(R.string.trigger_save_profile) {
            val p = TriggerProfileStore.get(pkg)
            if (save(p.copy(left=p.left.copy(enabled=left.isChecked),
                    right=p.right.copy(enabled=right.isChecked)))) toast(R.string.trigger_saved)
        }
        button(R.string.trigger_edit_coordinates) {
            val p = TriggerProfileStore.get(pkg)
            if (!save(p.copy(left=p.left.copy(enabled=left.isChecked),
                    right=p.right.copy(enabled=right.isChecked)))) return@button
            if (!Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this).setMessage(R.string.trigger_overlay_permission)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                    }.setNegativeButton(android.R.string.cancel, null).show()
                return@button
            }
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch == null) {toast(R.string.trigger_unlaunchable);return@button}
            TriggerRuntimeController.setEditing(true)
            try {
                startService(Intent(this, TriggerEditorService::class.java)
                    .putExtra(EXTRA_PACKAGE, pkg))
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                TriggerRuntimeController.setEditing(false)
                toast(R.string.trigger_unlaunchable)
            }
        }
        button(R.string.trigger_reset_coordinates) {
            val options = arrayOf(getString(R.string.trigger_left),getString(R.string.trigger_right),
                getString(R.string.trigger_both))
            AlertDialog.Builder(this).setTitle(R.string.trigger_reset_coordinates)
                .setItems(options) { _, i ->
                    AlertDialog.Builder(this).setMessage(R.string.trigger_confirm_reset)
                        .setNegativeButton(android.R.string.cancel,null)
                        .setPositiveButton(R.string.trigger_reset_coordinates) { _, _ ->
                            val p = TriggerProfileStore.get(pkg)
                            save(p.copy(left=if(i==0||i==2)p.left.copy(valid=false) else p.left,
                                right=if(i==1||i==2)p.right.copy(valid=false) else p.right))
                            refresh()
                        }.show()
                }.show()
        }
        button(R.string.trigger_delete_profile) {
            AlertDialog.Builder(this).setMessage(R.string.trigger_confirm_delete)
                .setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.trigger_delete_profile) { _, _ ->
                    if (TriggerProfileStore.delete(pkg)) {TriggerRuntimeController.publish();finish()}
                }.show()
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(root)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
        refresh()
    }
    override fun onResume(){super.onResume();if(::pkg.isInitialized) refresh()}
    private fun refresh(){
        val p=TriggerProfileStore.get(pkg)
        left.isChecked=p.left.enabled;right.isChecked=p.right.enabled
        status.text=getString(R.string.trigger_coord_summary,
            if(p.left.valid)getString(R.string.trigger_assigned) else getString(R.string.trigger_unassigned),
            if(p.right.valid)getString(R.string.trigger_assigned) else getString(R.string.trigger_unassigned))
    }
    private fun save(p: TriggerProfileStore.Profile): Boolean {
        val ok=TriggerProfileStore.put(p)
        if(ok) TriggerRuntimeController.publish() else toast(R.string.trigger_save_failed)
        return ok
    }
    companion object { const val EXTRA_PACKAGE="trigger_package" }
}
