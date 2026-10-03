package com.example.goodpixel.ui.edge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * Galaxy風「エッジパネル引き出しハンドル（タブ）」
 * 画面端にうっすら常駐し、タップまたは内側フリックでエッジパネルを展開する。
 */
@SuppressLint("ViewConstructor")
class EdgeHandleView(
    context: Context,
    private val onOpenPanel: () -> Unit
) : FrameLayout(context) {

    private val tabView = View(context)

    init {
        // ハンドル本体（半透明カプセルバー）
        tabView.apply {
            val bg = GradientDrawable().apply {
                setColor(Color.argb(140, 255, 255, 255)) // うっすら半透明白
                cornerRadii = floatArrayOf(20f, 20f, 0f, 0f, 0f, 0f, 20f, 20f)
            }
            background = bg
        }

        val tabParams = LayoutParams(14, 180).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        addView(tabView, tabParams)

        var startX = 0f
        var startY = 0f

        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    tabView.alpha = 1.0f
                    true
                }
                MotionEvent.ACTION_UP -> {
                    tabView.alpha = 0.6f
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    // タップまたは内側（左向き）へのスワイプで展開
                    if (dx < -10f || (Math.hypot(dx.toDouble(), dy.toDouble()) < 30.0)) {
                        onOpenPanel()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    tabView.alpha = 0.6f
                    true
                }
                else -> false
            }
        }
    }
}
