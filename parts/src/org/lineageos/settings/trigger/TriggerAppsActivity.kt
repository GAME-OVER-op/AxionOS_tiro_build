/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.settings.trigger

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import org.lineageos.settings.R
import java.util.concurrent.Executors

/** Full-screen launcher picker with explicit system bar/cutout insets and an async query. */
class TriggerAppsActivity : Activity() {
    private data class App(val pkg: String, val label: String, val icon: Drawable?)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var live = false
    private var allApps: List<App> = emptyList()
    private var selectedPackages: Set<String> = emptySet()
    private lateinit var adapter: ArrayAdapter<App>
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar
    private lateinit var counter: TextView
    private lateinit var search: EditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun themeColor(attribute: Int, default: Int): Int {
        val array = obtainStyledAttributes(intArrayOf(attribute))
        return try { array.getColor(0, default) } finally { array.recycle() }
    }
    private fun card(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(18).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        live = true
        actionBar?.hide()
        window.setDecorFitsSystemWindows(false)
        title = getString(R.string.trigger_apps_title)
        val foreground = themeColor(android.R.attr.textColorPrimary, 0xffeeeeee.toInt())
        val muted = themeColor(android.R.attr.textColorSecondary, 0xff999999.toInt())
        val surface = themeColor(android.R.attr.colorBackgroundFloating, 0xff282828.toInt())
        val bgColor = themeColor(android.R.attr.colorBackground, 0xff111111.toInt())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or
                WindowInsets.Type.displayCutout())
            // Layout beneath the system bars, then inset ALL content. No overlap with
            // status icons, punch-hole/cutout or the gesture navigation region.
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(20), dp(4))
        }
        header.addView(TextView(this).apply {
            text = "\u2190"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(foreground)
            contentDescription = getString(R.string.trigger_back)
            val attributes = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = try { attributes.getDrawable(0) } finally { attributes.recycle() }
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(this).apply {
            setText(R.string.trigger_apps_title)
            textSize = 22f
            setTextColor(foreground)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)

        root.addView(TextView(this).apply {
            setText(R.string.trigger_apps_hint)
            textSize = 14f
            setTextColor(muted)
            setPadding(dp(20), dp(4), dp(20), dp(14))
        })
        search = EditText(this).apply {
            setSingleLine(true)
            setHint(R.string.trigger_apps_search)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            textSize = 16f
            setPadding(dp(16), 0, dp(16), 0)
            this.background = card(surface)
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(52)).apply {
            setMargins(dp(16), 0, dp(16), dp(8))
        })
        counter = TextView(this).apply {
            textSize = 13f
            setTextColor(muted)
            setPadding(dp(20), dp(2), dp(16), dp(8))
        }
        root.addView(counter)
        progress = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        message = TextView(this).apply {
            setText(R.string.trigger_apps_loading)
            textSize = 15f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        root.addView(message)

        val list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            clipToPadding = false
            setPadding(0, 0, 0, dp(16))
            visibility = View.GONE
        }
        adapter = object : ArrayAdapter<App>(this, android.R.layout.simple_list_item_1,
                mutableListOf<App>()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView as? LinearLayout ?: LinearLayout(this@TriggerAppsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(4), dp(16), dp(4))
                    addView(LinearLayout(this@TriggerAppsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        this.background = card(surface)
                        setPadding(dp(14), dp(10), dp(14), dp(10))
                        addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER },
                            LinearLayout.LayoutParams(dp(44), dp(44)))
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(12), 0, dp(6), 0)
                            addView(TextView(context).apply {
                                textSize = 16f
                                setTextColor(foreground)
                                maxLines = 1
                                ellipsize = android.text.TextUtils.TruncateAt.END
                            })
                            addView(TextView(context).apply {
                                textSize = 12f
                                setTextColor(muted)
                                maxLines = 1
                                ellipsize = android.text.TextUtils.TruncateAt.END
                            })
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(TextView(context).apply {
                            textSize = 12f
                            setTextColor(muted)
                            gravity = Gravity.CENTER
                        }, LinearLayout.LayoutParams(-2, dp(40)))
                    }, LinearLayout.LayoutParams(-1, dp(72)))
                }
                val app = getItem(position)!!
                val inside = row.getChildAt(0) as LinearLayout
                (inside.getChildAt(0) as ImageView).setImageDrawable(app.icon)
                val texts = inside.getChildAt(1) as LinearLayout
                (texts.getChildAt(0) as TextView).text = app.label
                (texts.getChildAt(1) as TextView).text = app.pkg
                val selected = app.pkg in selectedPackages
                (inside.getChildAt(2) as TextView).text = if (selected) {
                    getString(R.string.trigger_already_added)
                } else "\u203a"
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val app = adapter.getItem(position) ?: return@setOnItemClickListener
            startActivity(Intent(this, TriggerProfileActivity::class.java)
                .putExtra(TriggerProfileActivity.EXTRA_PACKAGE, app.pkg))
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.requestApplyInsets()

        fun filterApps() {
            if (!live) return
            val term = search.text.toString().trim()
            val filtered = if (term.isBlank()) allApps else allApps.filter {
                it.label.contains(term, ignoreCase = true) ||
                    it.pkg.contains(term, ignoreCase = true)
            }
            adapter.clear()
            adapter.addAll(filtered)
            adapter.notifyDataSetChanged()
            counter.text = getString(R.string.trigger_apps_count, filtered.size)
            message.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            message.setText(if (allApps.isEmpty()) R.string.trigger_apps_empty
                else R.string.trigger_apps_no_matches)
            list.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (progress.visibility == View.GONE) filterApps()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        // App loading and icon retrieval never block the main thread.
        worker.execute {
            val apps = try {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val results = packageManager.queryIntentActivities(intent, 0)
                val map = LinkedHashMap<String, App>()
                for (entry: ResolveInfo in results) {
                    val pkg = entry.activityInfo?.packageName ?: continue
                    if (!TriggerProfileStore.allowedPackage(pkg) || map.containsKey(pkg)) continue
                    try {
                        map[pkg] = App(pkg, entry.loadLabel(packageManager).toString(),
                            entry.loadIcon(packageManager))
                    } catch (_: Exception) { map[pkg] = App(pkg, pkg, null) }
                }
                map.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            } catch (_: Exception) { emptyList() }
            main.post {
                if (!live || isDestroyed || isFinishing) return@post
                allApps = apps
                progress.visibility = View.GONE
                filterApps()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        selectedPackages = TriggerProfileStore.load().mapTo(HashSet<String>()) { it.pkg }
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    override fun onDestroy() {
        live = false
        worker.shutdownNow()
        super.onDestroy()
    }
}
