/*
 * SPDX-FileCopyrightText: 2025-2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.trigger

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.hypot
import org.lineageos.settings.R

class TriggerCalibrationActivity : Activity() {

    private lateinit var packageName: String
    private lateinit var mapping: TriggerConfigStore.Mapping

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageName = intent.getStringExtra(TriggerAppActivity.EXTRA_PACKAGE).orEmpty()
        if (packageName.isEmpty()) {
            finish()
            return
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )
        window.setDimAmount(0f)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setDecorFitsSystemWindows(false)

        mapping = TriggerConfigStore.read(this, packageName)
        setContentView(CalibrationView())
    }

    private fun save(leftX: Int, leftY: Int, rightX: Int, rightY: Int, width: Int, height: Int) {
        val rotation = when (display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 1
            Surface.ROTATION_180 -> 2
            Surface.ROTATION_270 -> 3
            else -> 0
        }
        mapping = mapping.copy(
            left = if (mapping.left.enabled) {
                mapping.left.copy(
                    x = leftX.coerceIn(0, width - 1),
                    y = leftY.coerceIn(0, height - 1),
                    rotation = rotation,
                    width = width,
                    height = height,
                )
            } else {
                mapping.left
            },
            right = if (mapping.right.enabled) {
                mapping.right.copy(
                    x = rightX.coerceIn(0, width - 1),
                    y = rightY.coerceIn(0, height - 1),
                    rotation = rotation,
                    width = width,
                    height = height,
                )
            } else {
                mapping.right
            },
        )
        TriggerConfigStore.write(this, packageName, mapping)
        Toast.makeText(this, R.string.trigger_coordinates_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private inner class CalibrationView : View(this) {
        private val density = resources.displayMetrics.density
        private val markerRadius = 34f * density
        private val hitRadius = 62f * density
        private val buttonHeight = 52f * density
        private val buttonWidth = 116f * density
        private val margin = 18f * density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var leftX = mapping.left.x.toFloat()
        private var leftY = mapping.left.y.toFloat()
        private var rightX = mapping.right.x.toFloat()
        private var rightY = mapping.right.y.toFloat()
        private var initialized = false
        private var dragging = NONE

        init {
            systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            setBackgroundColor(0x22000000)
        }

        override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
            invalidate()
            return insets
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            if (!initialized) {
                if (!mapping.left.hasCoordinates) {
                    leftX = width * 0.32f
                    leftY = height * 0.55f
                }
                if (!mapping.right.hasCoordinates) {
                    rightX = width * 0.68f
                    rightY = height * 0.55f
                }
                initialized = true
            } else if (oldWidth > 0 && oldHeight > 0) {
                leftX *= width.toFloat() / oldWidth
                leftY *= height.toFloat() / oldHeight
                rightX *= width.toFloat() / oldWidth
                rightY *= height.toFloat() / oldHeight
            }
            constrainMarkers(width, height)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawHeader(canvas)
            if (mapping.left.enabled) drawMarker(canvas, leftX, leftY, CYAN, "L")
            if (mapping.right.enabled) drawMarker(canvas, rightX, rightY, RED, "R")
        }

        private fun drawHeader(canvas: Canvas) {
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = 17f * density
            paint.color = Color.WHITE
            paint.setShadowLayer(5f * density, 0f, 1f * density, Color.BLACK)
            canvas.drawText(getString(R.string.trigger_calibration_hint), margin, margin + 22f * density, paint)
            paint.clearShadowLayer()

            val saveLeft = width - margin - buttonWidth
            drawButton(canvas, saveLeft, margin, getString(R.string.action_save), 0xcc1b5e20.toInt())
            drawButton(canvas, saveLeft - margin - buttonWidth, margin, getString(R.string.action_cancel), 0xcc424242.toInt())
        }

        private fun drawButton(canvas: Canvas, left: Float, top: Float, label: String, color: Int) {
            paint.color = color
            canvas.drawRoundRect(left, top, left + buttonWidth, top + buttonHeight, 13f * density, 13f * density, paint)
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 15f * density
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText(label, left + buttonWidth / 2f, top + buttonHeight / 2f + 5f * density, paint)
        }

        private fun drawMarker(canvas: Canvas, x: Float, y: Float, color: Int, label: String) {
            paint.style = Paint.Style.FILL
            paint.color = color
            paint.alpha = 210
            canvas.drawCircle(x, y, markerRadius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * density
            paint.color = Color.WHITE
            paint.alpha = 255
            canvas.drawCircle(x, y, markerRadius, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 24f * density
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(label, x, y + 8f * density, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val saveLeft = width - margin - buttonWidth
                    val cancelLeft = saveLeft - margin - buttonWidth
                    if (event.y in margin..(margin + buttonHeight)) {
                        if (event.x in saveLeft..(saveLeft + buttonWidth)) {
                            save(leftX.toInt(), leftY.toInt(), rightX.toInt(), rightY.toInt(), width, height)
                            return true
                        }
                        if (event.x in cancelLeft..(cancelLeft + buttonWidth)) {
                            finish()
                            return true
                        }
                    }
                    dragging = nearestMarker(event.x, event.y)
                    return dragging != NONE
                }
                MotionEvent.ACTION_MOVE -> {
                    when (dragging) {
                        LEFT -> {
                            leftX = event.x
                            leftY = event.y
                        }
                        RIGHT -> {
                            rightX = event.x
                            rightY = event.y
                        }
                    }
                    constrainMarkers(width, height)
                    invalidate()
                    return dragging != NONE
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = NONE
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun nearestMarker(x: Float, y: Float): Int {
            val leftDistance = if (mapping.left.enabled) {
                hypot((x - leftX).toDouble(), (y - leftY).toDouble())
            } else {
                Double.MAX_VALUE
            }
            val rightDistance = if (mapping.right.enabled) {
                hypot((x - rightX).toDouble(), (y - rightY).toDouble())
            } else {
                Double.MAX_VALUE
            }
            return when {
                leftDistance <= hitRadius.toDouble() && leftDistance <= rightDistance -> LEFT
                rightDistance <= hitRadius.toDouble() -> RIGHT
                else -> NONE
            }
        }

        private fun constrainMarkers(width: Int, height: Int) {
            leftX = leftX.coerceIn(markerRadius, width - markerRadius)
            leftY = leftY.coerceIn(markerRadius, height - markerRadius)
            rightX = rightX.coerceIn(markerRadius, width - markerRadius)
            rightY = rightY.coerceIn(markerRadius, height - markerRadius)
        }
    }

    companion object {
        private const val NONE = 0
        private const val LEFT = 1
        private const val RIGHT = 2
        private val CYAN = 0xff00cfd5.toInt()
        private val RED = 0xffff3448.toInt()
    }
}
