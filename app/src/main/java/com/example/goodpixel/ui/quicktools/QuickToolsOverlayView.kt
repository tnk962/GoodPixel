package com.example.goodpixel.ui.quicktools

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.example.goodpixel.service.GoodAccessibilityService
import com.example.goodpixel.ui.notilog.NotiLogActivity

/**
 * Galaxy One Hand Operation + 風「クイックツール（操作パネル）」
 * 水平長押しスワイプで呼び出し。親指の届く位置で主要な設定・メディア・機能をアイコンだけで即座に操作可能。
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
    private var isAllMuted = false
    private var savedMusicVol = 0

    companion object {
        private const val AUTO_DISMISS_DELAY_MS = 8000L // 8秒間無操作で自動クローズ
    }

    private val autoDismissHandler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable {
        Log.d("QuickTools", "Auto-dismissing QuickTools due to 8s inactivity")
        onDismiss()
    }

    private fun resetAutoDismissTimer() {
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        autoDismissHandler.postDelayed(autoDismissRunnable, AUTO_DISMISS_DELAY_MS)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        // パネルを触るたびにタイマーを延長
        resetAutoDismissTimer()
        return super.dispatchTouchEvent(ev)
    }

    private var isReceiverRegistered = false
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == Intent.ACTION_SCREEN_OFF || action == Intent.ACTION_USER_PRESENT) {
                Log.d("QuickTools", "Screen off or user present received, closing QuickTools")
                onDismiss()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 既にロック画面が表示されている場合は即座に閉じる
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km?.isKeyguardLocked == true) {
            onDismiss()
            return
        }

        resetAutoDismissTimer()

        // ロック画面移行（画面消灯）を検知して閉じる
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(screenOffReceiver, filter)
            }
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.w("QuickTools", "Failed to register screenOffReceiver", e)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(screenOffReceiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }
    }

    init {
        val density = context.resources.displayMetrics.density

        // 背景タップで閉じるための全画面リスナー
        setBackgroundColor(Color.argb(90, 0, 0, 0))
        setOnClickListener { onDismiss() }

        // パネル本体（角丸ダークカード）
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(Color.argb(240, 28, 30, 38))
                cornerRadius = 28 * density
                setStroke((1.5f * density).toInt(), Color.argb(80, 255, 255, 255))
            }
            background = bg
            val padH = (24 * density).toInt()
            val padV = (20 * density).toInt()
            setPadding(padH, padV, padH, padV)
            elevation = 32f
            // パネル内のタップが外側に抜けて閉じないようにする
            setOnClickListener { /* consume */ }
        }

        val panelParams = LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.90f).toInt(),
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
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.argb(180, 200, 200, 200))
            textSize = 18f
            setPadding((12 * density).toInt(), (4 * density).toInt(), (12 * density).toInt(), (4 * density).toInt())
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
            textSize = 12f
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

        Spacer(panel, 8)

        // 3. 画面の明るさスライダー
        val currentBrightness = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) {
            128
        }
        val brightLabel = TextView(context).apply {
            text = "☀️ 画面の明るさ"
            setTextColor(Color.LTGRAY)
            textSize = 12f
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

        // 4. アイコンボタングリッド（4列 × 4行 = 16個のタイル、アイコンのみで表現）
        // 行1: メディア＆通信 [⏯ 再生/一時停止] [⏭ 次の曲] [🛜 Wi-Fi] [ᛒ Bluetooth]
        val row1 = createRow(
            createIconButton("⏯", "再生 / 一時停止") {
                sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                Toast.makeText(context, "⏯ 再生 / 一時停止", Toast.LENGTH_SHORT).show()
            },
            createIconButton("⏭", "次の曲") {
                sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
                Toast.makeText(context, "⏭ 次の曲", Toast.LENGTH_SHORT).show()
            },
            createIconButton("🛜", "Wi-Fi") {
                openWifiPanel()
            },
            createIconButton("ᛒ", "Bluetooth") {
                openBluetoothPanel()
            }
        )
        panel.addView(row1)

        Spacer(panel, 8)

        // 行2: サウンド＆画面 [🔊 スピーカー/サウンド] [🔇 全ミュート] [🔕 通知完全オフ] [🔄 画面自動回転]
        val row2 = createRow(
            createIconButton("🔊", "サウンドモード (通常 / バイブ)") {
                toggleSoundMode()
            },
            createIconButton("🔇", "全ミュート") {
                toggleAllMute()
            },
            createIconButton("🔕", "通知完全オフ (サイレント)") {
                toggleDoNotDisturb()
            },
            createIconButton("🔄", "自動回転 ON/OFF") {
                onToggleRotation()
                onDismiss()
            }
        )
        panel.addView(row2)

        Spacer(panel, 8)

        // 行3: キャプチャ＆ツール [🔦 ライト] [🎥 画面録画] [✂️ スマート選択] [📸 全面スクショ]
        val row3 = createRow(
            createIconButton("🔦", "ライト (懐中電灯)") {
                toggleTorch()
            },
            createIconButton("🎥", "画面録画") {
                triggerScreenRecord()
            },
            createIconButton("✂️", "スマート選択 (範囲切り抜き)") {
                onDismiss()
                onTriggerSmartCapture()
            },
            createIconButton("📸", "全面スクリーンショット") {
                onDismiss()
                onTriggerScreenshot()
            }
        )
        panel.addView(row3)

        Spacer(panel, 8)

        // 行4: システム＆ログ [📜 通知ログ] [⚏ 画面分割] [⏻ 電源メニュー] [⚙️ 設定]
        val row4 = createRow(
            createIconButton("📜", "通知ログ") {
                onDismiss()
                val intent = Intent(context, NotiLogActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            },
            createIconButton("⚏", "画面分割") {
                onDismiss()
                GoodAccessibilityService.instance?.performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
                )
            },
            createIconButton("⏻", "電源メニュー") {
                onDismiss()
                GoodAccessibilityService.instance?.performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
                )
            },
            createIconButton("⚙️", "設定") {
                onDismiss()
                val intent = Intent(context, com.example.goodpixel.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("open_tab", "settings")
                }
                context.startActivity(intent)
            }
        )
        panel.addView(row4)

        addView(panel, panelParams)
    }

    private fun createRow(vararg buttons: View): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            for (btn in buttons) {
                addView(btn)
            }
        }
    }

    private fun createIconButton(icon: String, tooltip: String, onClick: () -> Unit): View {
        val density = context.resources.displayMetrics.density
        val btnHeight = (48 * density).toInt()

        return TextView(context).apply {
            text = icon
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            val bg = GradientDrawable().apply {
                setColor(Color.argb(130, 52, 56, 68))
                cornerRadius = 14 * density
            }
            background = bg

            setOnClickListener {
                vibrateClick()
                onClick()
            }

            setOnLongClickListener {
                vibrateClick()
                Toast.makeText(context, "$icon $tooltip", Toast.LENGTH_SHORT).show()
                true
            }

            layoutParams = LinearLayout.LayoutParams(0, btnHeight, 1f).apply {
                val marginH = (4 * density).toInt()
                setMargins(marginH, 0, marginH, 0)
            }
        }
    }

    private fun vibrateClick() {
        try {
            if (GoodAccessibilityService.isVibrationEnabled(context)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                } else {
                    @Suppress("DEPRECATION")
                    val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    v?.vibrate(30)
                }
            }
        } catch (_: Exception) {}
    }

    private fun sendMediaKeyEvent(keyCode: Int) {
        try {
            val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            audioManager.dispatchMediaKeyEvent(down)
            val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            audioManager.dispatchMediaKeyEvent(up)
        } catch (e: Exception) {
            Log.e("QuickTools", "Error dispatching media key $keyCode", e)
        }
    }

    private fun openWifiPanel() {
        onDismiss()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } else {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private fun openBluetoothPanel() {
        onDismiss()
        try {
            // Android 14+ (API 34+) の Bluetooth Panel
            val panelIntent = Intent("android.settings.panel.action.BLUETOOTH").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (context.packageManager.resolveActivity(panelIntent, 0) != null) {
                context.startActivity(panelIntent)
                return
            }
        } catch (_: Exception) {}

        try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("QuickTools", "Failed to open bluetooth settings", e)
        }
    }

    private fun toggleSoundMode() {
        try {
            val current = audioManager.ringerMode
            if (current == AudioManager.RINGER_MODE_NORMAL) {
                audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                Toast.makeText(context, "📳 バイブレーション", Toast.LENGTH_SHORT).show()
            } else {
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                Toast.makeText(context, "🔊 サウンド (通常)", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            try {
                audioManager.adjustVolume(AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
            } catch (_: Exception) {}
        }
    }

    private fun toggleAllMute() {
        try {
            isAllMuted = !isAllMuted
            if (isAllMuted) {
                savedMusicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                try {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                } catch (_: Exception) {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                }
                Toast.makeText(context, "🔇 全ミュート ON", Toast.LENGTH_SHORT).show()
            } else {
                val restoreVol = if (savedMusicVol > 0) savedMusicVol else audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVol, 0)
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                Toast.makeText(context, "🔊 ミュート解除", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "ミュート切り替えに失敗しました", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleDoNotDisturb() {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!nm.isNotificationPolicyAccessGranted) {
                    Toast.makeText(context, "サイレントモード操作の権限を許可してください", Toast.LENGTH_LONG).show()
                    val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    onDismiss()
                    return
                }

                val current = nm.currentInterruptionFilter
                if (current == NotificationManager.INTERRUPTION_FILTER_ALL) {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                    Toast.makeText(context, "🔕 通知の完全オフ (サイレント) ON", Toast.LENGTH_SHORT).show()
                } else {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    Toast.makeText(context, "🔔 通常モード (通知許可)", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("QuickTools", "Error toggling DND", e)
        }
    }

    private fun triggerScreenRecord() {
        onDismiss()
        val success = GoodAccessibilityService.instance?.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        ) ?: false
        if (success) {
            Toast.makeText(context, "🎥 クイック設定から「画面レコード」をタップしてください", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleTorch() {
        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
            isTorchOn = !isTorchOn
            cameraManager.setTorchMode(cameraId, isTorchOn)
            Toast.makeText(context, if (isTorchOn) "🔦 ライト点灯" else "🔦 ライト消灯", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "ライトの操作に失敗しました", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addDivider(panel: LinearLayout) {
        val density = context.resources.displayMetrics.density
        val divider = View(context).apply {
            setBackgroundColor(Color.argb(35, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (1 * density).toInt().coerceAtLeast(1)
            ).apply {
                val marginV = (14 * density).toInt()
                setMargins(0, marginV, 0, marginV)
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
