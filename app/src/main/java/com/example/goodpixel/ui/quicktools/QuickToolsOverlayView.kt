package com.example.goodpixel.ui.quicktools

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.example.goodpixel.ui.notilog.NotiLogActivity

/**
 * Galaxy One Hand Operation + 風「クイックツール（操作パネル）」
 * 水平長押しスワイプで呼び出し。親指の届く位置で主要な設定を即座に操作可能。
 */
@SuppressLint("ViewConstructor")
class QuickToolsOverlayView(
    context: Context,
    private val onDismiss: () -> Unit,
    private val onTriggerSmartCapture: () -> Unit,
    private val onTriggerScreenshot: () -> Unit,
    private val onToggleRotation: () -> Unit
) : FrameLayout(context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var isTorchOn = false

    init {
        // 背景タップで閉じるための全画面リスナー
        setBackgroundColor(Color.argb(80, 0, 0, 0))
        setOnClickListener { onDismiss() }

        // パネル本体（角丸ダークカード）
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(Color.argb(235, 30, 32, 38))
                cornerRadius = 36f
                setStroke(2, Color.argb(100, 255, 255, 255))
            }
            background = bg
            setPadding(40, 32, 40, 32)
            elevation = 24f
            // パネル内のタップが外側に抜けて閉じないようにする
            setOnClickListener { /* consume */ }
        }

        val panelParams = LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.88f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }

        // 1. タイトルバー
        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleText = TextView(context).apply {
            text = "⚡ クイックツール"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.GRAY)
            textSize = 20f
            setPadding(16, 8, 16, 8)
            setOnClickListener { onDismiss() }
        }
        titleBar.addView(titleText)
        titleBar.addView(closeBtn)
        panel.addView(titleBar)

        addDivider(panel)

        // 2. 音量スライダー
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        val volLabel = TextView(context).apply {
            text = "🔊 メディア音量 ($currentVol / $maxVol)"
            setTextColor(Color.LTGRAY)
            textSize = 13f
        }
        panel.addView(volLabel)

        val volSeekBar = SeekBar(context).apply {
            max = maxVol
            progress = currentVol
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                        volLabel.text = "🔊 メディア音量 ($progress / $maxVol)"
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        panel.addView(volSeekBar)

        Spacer(panel, 16)

        // 3. 画面の明るさスライダー
        val currentBrightness = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) {
            128
        }
        val brightLabel = TextView(context).apply {
            text = "☀️ 画面の明るさ"
            setTextColor(Color.LTGRAY)
            textSize = 13f
        }
        panel.addView(brightLabel)

        val brightSeekBar = SeekBar(context).apply {
            max = 255
            progress = currentBrightness
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        try {
                            if (Settings.System.canWrite(context)) {
                                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, progress)
                            }
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        panel.addView(brightSeekBar)

        addDivider(panel)

        // 4. クイックアクションボタン群（グリッド）
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row1.addView(createActionButton("🔄 自動回転") {
            onToggleRotation()
            onDismiss()
        })
        row1.addView(createActionButton("🔦 ライト") {
            toggleTorch()
        })
        panel.addView(row1)

        Spacer(panel, 12)

        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row2.addView(createActionButton("✂️ スマート選択") {
            onDismiss()
            onTriggerSmartCapture()
        })
        row2.addView(createActionButton("📸 全面スクショ") {
            onDismiss()
            onTriggerScreenshot()
        })
        panel.addView(row2)

        Spacer(panel, 12)

        val row3 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row3.addView(createActionButton("📜 NotiStar 通知ログ") {
            onDismiss()
            val intent = Intent(context, NotiLogActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        })
        panel.addView(row3)

        addView(panel, panelParams)
    }

    private fun toggleTorch() {
        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
            isTorchOn = !isTorchOn
            cameraManager.setTorchMode(cameraId, isTorchOn)
            Toast.makeText(context, if (isTorchOn) "ライト点灯" else "ライト消灯", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "ライトの操作に失敗しました", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createActionButton(text: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(24, 20, 24, 20)
            val bg = GradientDrawable().apply {
                setColor(Color.argb(120, 60, 64, 75))
                cornerRadius = 20f
            }
            background = bg
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(8, 0, 8, 0)
            }
        }
    }

    private fun addDivider(panel: LinearLayout) {
        val divider = View(context).apply {
            setBackgroundColor(Color.argb(40, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                2
            ).apply {
                setMargins(0, 20, 0, 20)
            }
        }
        panel.addView(divider)
    }

    private fun Spacer(panel: LinearLayout, heightDp: Int) {
        val density = context.resources.displayMetrics.density
        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (heightDp * density).toInt()
            )
        }
        panel.addView(spacer)
    }
}
