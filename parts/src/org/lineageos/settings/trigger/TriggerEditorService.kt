/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.settings.trigger

import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.lineageos.settings.R

/** User-initiated, full-display overlay; no accessibility hooks or continuous polling. */
class TriggerEditorService : Service() {
    private lateinit var wm: WindowManager
    private var overlay: FrameLayout? = null
    private var pkg = ""
    private var position = arrayOf(floatArrayOf(.25f, .5f), floatArrayOf(.75f, .5f))
    private var enabled = booleanArrayOf(true,true)
    private var assigned = booleanArrayOf(false,false)
    private var markers = arrayOfNulls<TextView>(2)
    private fun dp(n: Int) = (n * resources.displayMetrics.density+.5f).toInt()
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(overlay!=null)return START_NOT_STICKY
        pkg=intent?.getStringExtra(TriggerProfileActivity.EXTRA_PACKAGE).orEmpty()
        if(!TriggerProfileStore.allowedPackage(pkg)){stopSelf();return START_NOT_STICKY}
        val p=TriggerProfileStore.get(pkg)
        enabled=booleanArrayOf(p.left.enabled,p.right.enabled)
        assigned=booleanArrayOf(p.left.valid,p.right.valid)
        position=arrayOf(floatArrayOf(if(p.left.valid)p.left.x else .25f,
            if(p.left.valid)p.left.y else .5f),
            floatArrayOf(if(p.right.valid)p.right.x else .75f,
                if(p.right.valid)p.right.y else .5f))
        wm=getSystemService(WINDOW_SERVICE) as WindowManager
        val root=FrameLayout(this)
        root.setBackgroundColor(0x22000000)
        overlay=root
        for(i in 0..1) if(enabled[i]) {
            val marker=TextView(this).apply {
                text=if(i==0)"L" else "R"
                gravity=Gravity.CENTER
                textSize=20f
                setTextColor(Color.WHITE)
                background=GradientDrawable().apply {
                    shape=GradientDrawable.OVAL
                    setColor(if(i==0)0xCC0284C7.toInt() else 0xCCDC2626.toInt())
                    setStroke(dp(2),Color.WHITE)
                }
            }
            marker.alpha=if(assigned[i])1f else .60f
            marker.setOnTouchListener(object: View.OnTouchListener {
                var lastX=0f;var lastY=0f
                override fun onTouch(v:View,event:MotionEvent):Boolean {
                    when(event.actionMasked){
                        MotionEvent.ACTION_DOWN->{lastX=event.rawX;lastY=event.rawY;return true}
                        MotionEvent.ACTION_MOVE->{
                            val area=overlay?:return true
                            val nx=(v.x+event.rawX-lastX).coerceIn(0f,
                                (area.width-v.width).coerceAtLeast(0).toFloat())
                            val ny=(v.y+event.rawY-lastY).coerceIn(0f,
                                (area.height-v.height).coerceAtLeast(0).toFloat())
                            v.x=nx;v.y=ny
                            lastX=event.rawX;lastY=event.rawY
                            position[i][0]=(nx+v.width/2f)/area.width.coerceAtLeast(1)
                            position[i][1]=(ny+v.height/2f)/area.height.coerceAtLeast(1)
                            assigned[i]=true
                            v.alpha=1f
                            return true
                        }
                        MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->return true
                    }
                    return true
                }
            })
            markers[i]=marker
            root.addView(marker,FrameLayout.LayoutParams(dp(52),dp(52)))
        }
        val bar=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            setBackgroundColor(0xD9222222.toInt())
            setPadding(dp(8),dp(4),dp(8),dp(4))
        }
        val title=TextView(this).apply {
            setText(R.string.trigger_edit_coordinates)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP,13f)
            gravity=Gravity.CENTER_VERTICAL
        }
        bar.addView(title,LinearLayout.LayoutParams(0,dp(48),1f))
        val cancel=Button(this).apply {
            setText(android.R.string.cancel)
            setOnClickListener {finishEditing()}
        }
        val save=Button(this).apply {
            setText(R.string.trigger_save_profile)
            setOnClickListener {
                val old=TriggerProfileStore.get(pkg)
                val rot=wm.defaultDisplay.rotation
                val new=old.copy(rotation=rot,
                    left=if(enabled[0])old.left.copy(valid=assigned[0],x=position[0][0],y=position[0][1]) else old.left,
                    right=if(enabled[1])old.right.copy(valid=assigned[1],x=position[1][0],y=position[1][1]) else old.right)
                if(TriggerProfileStore.put(new)) {
                    TriggerRuntimeController.publish()
                    finishEditing()
                } else Toast.makeText(this@TriggerEditorService,R.string.trigger_save_failed,
                    Toast.LENGTH_SHORT).show()
            }
        }
        bar.addView(cancel);bar.addView(save)
        root.addView(bar,FrameLayout.LayoutParams(-1,dp(60),Gravity.TOP))
        val params=WindowManager.LayoutParams(-1,-1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply {
                gravity=Gravity.TOP or Gravity.START
                layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        try {wm.addView(root,params)} catch (e:Exception) {
            overlay=null
            TriggerRuntimeController.setEditing(false)
            stopSelf();return START_NOT_STICKY
        }
        root.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> layoutMarkers() }
        root.post {layoutMarkers()}
        return START_NOT_STICKY
    }
    private fun layoutMarkers(){
        val area=overlay?:return
        if(area.width<=0||area.height<=0)return
        for(i in 0..1)markers[i]?.let {
            it.x=(position[i][0]*area.width-it.width/2f).coerceIn(0f,
                (area.width-it.width).toFloat().coerceAtLeast(0f))
            it.y=(position[i][1]*area.height-it.height/2f).coerceIn(dp(64).toFloat(),
                (area.height-it.height).toFloat().coerceAtLeast(dp(64).toFloat()))
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A game may switch to landscape while launching; retain the editor.
        overlay?.post { layoutMarkers() }
    }
    /** A visible, user-tapped overlay is permitted to return to its owner Activity.
     * Starting the Activity during onDestroy() was a background launch and Android
     * could silently reject it, leaving the game on screen after Save/Cancel.
     */
    private fun finishEditing() {
        val destination = Intent(this, TriggerProfileActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(TriggerProfileActivity.EXTRA_PACKAGE, pkg)
        try {
            // Keep the visible overlay attached until the user-initiated launch
            // has been requested. Do not launch from Service.onDestroy().
            startActivity(destination)
        } catch (e: Exception) {
            android.util.Log.e("TriggerEditor", "Cannot return to trigger settings", e)
            Toast.makeText(this, R.string.trigger_return_failed, Toast.LENGTH_LONG).show()
        }
        stopSelf()
    }

    override fun onDestroy() {
        overlay?.let { try {wm.removeViewImmediate(it)} catch (_:Exception){} }
        overlay = null
        TriggerRuntimeController.setEditing(false)
        super.onDestroy()
    }
}
