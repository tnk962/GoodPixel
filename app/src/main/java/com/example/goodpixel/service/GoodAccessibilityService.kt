package com.example.goodpixel.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.example.goodpixel.ui.crop.CropOverlayView
import com.example.goodpixel.ui.quicktools.QuickToolsOverlayView
import com.example.goodpixel.ui.edge.EdgeHandleView
import com.example.goodpixel.ui.edge.EdgePanelOverlayView
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2

/**
 * GoodPixel コアサービス
 * TYPE_ACCESSIBILITY_OVERLAY を使用し、Android OSの戻るジェスチャーよりも
 * 最前面で画面端スワイプを完全に掌握・実行する。
 */
class GoodAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GoodAccessibility"
        private const val PREFS_NAME = "goodpixel_prefs"
        private const val KEY_VIBRATION = "pref_vibration_enabled"
        private const val KEY_DEBUG_OVERLAY = "pref_debug_overlay_enabled"

        var instance: GoodAccessibilityService? = null
            private set

        private val _isServiceEnabled = MutableStateFlow(false)
        val isServiceEnabled: StateFlow<Boolean> = _isServiceEnabled.asStateFlow()

        private val _isCurrentAppGame = MutableStateFlow(false)
        val isCurrentAppGame: StateFlow<Boolean> = _isCurrentAppGame.asStateFlow()

        fun isVibrationEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_VIBRATION, true)
        }

        fun setVibrationEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_VIBRATION, enabled).apply()
            instance?.vibrationEnabled = enabled
        }

        private const val KEY_OHO_ENABLED = "pref_oho_enabled"
        private const val KEY_HIDE_NAVBAR = "pref_hide_navbar_enabled"

        fun isOhoEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_OHO_ENABLED, true)
        }

        fun setOhoEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_OHO_ENABLED, enabled).apply()
            instance?.updateOhoVisibility(enabled)
        }

        fun isHideNavBarEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_HIDE_NAVBAR, false)
        }

        fun setHideNavBarEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_HIDE_NAVBAR, enabled).apply()
            if (enabled) {
                instance?.showHideNavBarOverlay()
            } else {
                instance?.dismissHideNavBarOverlay()
            }
        }

        fun isDebugOverlayEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_DEBUG_OVERLAY, false)
        }

        fun setDebugOverlayEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_DEBUG_OVERLAY, enabled).apply()
            instance?.updateOverlayColors(enabled)
        }

        fun performBackAction(): Boolean {
            val result = instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
            Log.d(TAG, "performBackAction result: $result")
            return result
        }

        fun takeScreenshotAction(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val result = instance?.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) ?: false
                Log.d(TAG, "takeScreenshotAction result: $result")
                result
            } else {
                false
            }
        }

        fun toggleAutoRotation(context: Context): Boolean {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    Toast.makeText(context, "設定の変更権限を許可してください", Toast.LENGTH_SHORT).show()
                    false
                } else {
                    val current = Settings.System.getInt(
                        context.contentResolver,
                        Settings.System.ACCELEROMETER_ROTATION, 0
                    )
                    val next = if (current == 1) 0 else 1
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.ACCELEROMETER_ROTATION, next
                    )
                    val statusText = if (next == 1) "画面回転: 自動" else "画面回転: 固定"
                    Toast.makeText(context, statusText, Toast.LENGTH_SHORT).show()
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle rotation", e)
                false
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private var vibrationEnabled = true

    private var leftOverlayView: View? = null
    private var rightOverlayView: View? = null
    private var edgeHandleView: EdgeHandleView? = null
    private var cropOverlayView: CropOverlayView? = null
    private var quickToolsOverlayView: QuickToolsOverlayView? = null
    private var edgePanelOverlayView: EdgePanelOverlayView? = null
    private var hideNavBarOverlayView: View? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var gameMonitorJob: Job? = null

    private var isScreenLockReceiverRegistered = false
    private val screenLockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == Intent.ACTION_SCREEN_OFF || action == Intent.ACTION_USER_PRESENT) {
                Log.d(TAG, "Screen off / user present: dismissing overlays")
                dismissQuickToolsOverlay()
                dismissEdgePanelOverlay()
                dismissCropOverlay()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceEnabled.value = true
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        vibrationEnabled = prefs.getBoolean(KEY_VIBRATION, true)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        Log.i(TAG, "GoodAccessibilityService connected! Setting up TYPE_ACCESSIBILITY_OVERLAY...")

        setupOverlays()
        observeGameStatus()

        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenLockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenLockReceiver, filter)
            }
            isScreenLockReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register screenLockReceiver", e)
        }
    }

    /**
     * 最上位の TYPE_ACCESSIBILITY_OVERLAY で画面端ハンドルを配置
     * これにより OS の戻るジェスチャー（矢印）より前面でタッチを捕捉可能
     */
    private fun setupOverlays() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenHeight = metrics.heightPixels

        // OHO+準拠のスマート幅（48px = 約15dp）。Twitterの共有ボタン等（端から70px〜）を邪魔しない
        val handleWidth = 48
        // 画面下部のボタンやUIバーを塞がないよう、高さをコンパクト化し中央手元寄りに配置
        val handleHeight = (screenHeight * 0.35f).toInt()
        val yOffset = -(screenHeight * 0.02f).toInt()

        // TYPE_ACCESSIBILITY_OVERLAY: システム最上位
        val layoutFlag = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

        // 左ハンドル
        val leftParams = WindowManager.LayoutParams(
            handleWidth,
            handleHeight,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            y = yOffset
        }

        leftOverlayView = createHandleView(isLeft = true)
        windowManager.addView(leftOverlayView, leftParams)

        // 右ハンドル
        val rightParams = WindowManager.LayoutParams(
            handleWidth,
            handleHeight,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            y = yOffset
        }

        rightOverlayView = createHandleView(isLeft = false)
        windowManager.addView(rightOverlayView, rightParams)

        // エッジパネル引き出しタブ（右端・邪魔にならない手元やや上部）
        val edgeParams = WindowManager.LayoutParams(
            36, // タッチ幅をスリム化
            200, // タッチ高さ
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            y = -(screenHeight * 0.20f).toInt() // Twitterのボタンエリアから遠ざけた上部
        }

        edgeHandleView = EdgeHandleView(this) {
            showEdgePanelOverlay()
        }
        windowManager.addView(edgeHandleView, edgeParams)

        // OSの戻るジェスチャーを除外登録（Android 10+）
        excludeSystemGestures(leftOverlayView)
        excludeSystemGestures(rightOverlayView)
        excludeSystemGestures(edgeHandleView)

        updateOverlayColors(prefs.getBoolean(KEY_DEBUG_OVERLAY, false))
        updateOhoVisibility(isOhoEnabled(this))
        if (isHideNavBarEnabled(this)) {
            showHideNavBarOverlay()
        }
        Log.i(TAG, "Overlays successfully added!")
    }

    private fun excludeSystemGestures(view: View?) {
        if (view == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        view.post {
            try {
                val rect = Rect(0, 0, view.width, view.height)
                view.systemGestureExclusionRects = listOf(rect)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to exclude gesture", e)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createHandleView(isLeft: Boolean): View {
        val view = FrameLayout(this)

        // 完全透明(0x00000000)だとタッチが下のWindowに突き抜けるため、α=1のほぼ透明色を設定
        view.setBackgroundColor(Color.argb(1, 0, 0, 0))

        var startX = 0f
        var startY = 0f
        var touchDownTime = 0L
        var isLongPressTriggered = false
        var hasVibratedForSwipe = false
        var longPressJob: Job? = null

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    touchDownTime = System.currentTimeMillis()
                    isLongPressTriggered = false
                    hasVibratedForSwipe = false

                    // 350ms保持されたら「長押し成立」のバイブレーションをリアルタイム発生
                    longPressJob?.cancel()
                    longPressJob = serviceScope.launch {
                        kotlinx.coroutines.delay(350L)
                        isLongPressTriggered = true
                        triggerVibration(isHold = true)
                        Log.d(TAG, "Long-press threshold reached! Haptic fired.")
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    val dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

                    // 単なるタップではなく、明確なスワイプ操作が始まった瞬間に微振動（1回目）
                    if (dist >= 18f && !hasVibratedForSwipe) {
                        hasVibratedForSwipe = true
                        triggerVibration(isHold = false)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressJob?.cancel()
                    val endX = event.rawX
                    val endY = event.rawY
                    val duration = System.currentTimeMillis() - touchDownTime
                    val isLongPress = isLongPressTriggered || (duration >= 350L)

                    handleGesture(startX, startY, endX, endY, isLeft, isLongPress)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressJob?.cancel()
                    true
                }
                else -> false
            }
        }

        return view
    }

    /**
     * ハンドル領域内の単なるタップ時、下のアプリ（Twitter等）にクリックをパススルー送信する
     */
    private fun passThroughClick(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 40)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
            Log.d(TAG, "Passed through click to underlying app at ($x, $y)")
        }
    }

    private fun handleGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        isLeft: Boolean,
        isLongPress: Boolean
    ) {
        val dx = endX - startX
        val dy = endY - startY
        val distance = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

        Log.d(TAG, "Gesture end: dx=$dx, dy=$dy, dist=$distance, isLongPress=$isLongPress")

        // スワイプ移動がごく小さい場合、または長押しでない単なるタップの場合：
        // 下のアプリ（Twitterの共有ボタン・いいね等）にクリックを即座にパススルー！
        if (distance < 24f && !isLongPress) {
            passThroughClick(endX, endY)
            return
        }

        // 誤タップ防止（30px未満は無視、かつタップパススルー）
        if (distance < 30f) {
            if (!isLongPress) passThroughClick(endX, endY)
            return
        }

        // 画面端から内側へのスワイプ成分があるか確認
        val isSwipingInward = if (isLeft) dx > 15f else dx < -15f
        if (!isSwipingInward) {
            // 内側スワイプではない場合（縦スクロールや画面外向き）も、下のアプリへタップ透過
            if (!isLongPress && distance < 45f) {
                passThroughClick(endX, endY)
            }
            return
        }

        // スワイプ角度 (0°〜90°)
        val angle = Math.toDegrees(atan2(abs(dy).toDouble(), abs(dx).toDouble())).toFloat()
        Log.d(TAG, "Gesture recognized: angle=$angle, dy=$dy, isLongPress=$isLongPress")

        when {
            // 1. 水平スワイプ (±22度以内)
            angle < 22f -> {
                if (isLongPress) {
                    showQuickToolsOverlay()
                } else {
                    val success = performBackAction()
                    Log.i(TAG, "Executed: BACK (success=$success)")
                }
            }
            // 2. 上向き斜めスワイプ (22°〜85°)
            dy < 0 && angle in 22f..85f -> {
                if (isLongPress) {
                    // ユーザー指示により、斜め上長押しはいったん何もしない
                    Log.d(TAG, "Diagonal Up Long: No action assigned")
                } else {
                    val success = takeScreenshotAction()
                    Log.i(TAG, "Executed: SCREENSHOT (success=$success)")
                    if (!success) {
                        Toast.makeText(this, "スクリーンショットを実行できませんでした", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            // 3. 下向き斜めスワイプ (22°〜85°)
            dy > 0 && angle in 22f..85f -> {
                if (isLongPress) {
                    toggleAutoRotation(this)
                } else {
                    startSmartCapture()
                }
            }
        }
    }

    private fun triggerVibration(isHold: Boolean) {
        if (!vibrationEnabled) return

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        val duration = if (isHold) 45L else 20L
        val amplitude = if (isHold) 220 else 120

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(duration)
        }
    }

    fun updateOverlayColors(showDebug: Boolean) {
        // デバッグ時は半透明の青、通常時は「α=1のほぼ透明黒」
        val color = if (showDebug) Color.argb(90, 0, 150, 255) else Color.argb(1, 0, 0, 0)
        leftOverlayView?.setBackgroundColor(color)
        rightOverlayView?.setBackgroundColor(color)
    }

    private fun observeGameStatus() {
        gameMonitorJob = serviceScope.launch {
            _isCurrentAppGame.collect { isGame ->
                val ohoEnabled = isOhoEnabled(this@GoodAccessibilityService)
                val ohoVisibility = if (ohoEnabled && !isGame) View.VISIBLE else View.GONE
                leftOverlayView?.visibility = ohoVisibility
                rightOverlayView?.visibility = ohoVisibility
                edgeHandleView?.visibility = if (isGame) View.GONE else View.VISIBLE
            }
        }
    }

    private var isKeyboardActive = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkgName = event.packageName?.toString()
                if (pkgName != null && pkgName != packageName && pkgName != "com.android.systemui") {
                    checkIfGame(pkgName)
                    if (quickToolsOverlayView != null) {
                        dismissQuickToolsOverlay()
                    }
                    if (edgePanelOverlayView != null) {
                        dismissEdgePanelOverlay()
                    }
                }
                checkKeyboardVisibility()
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                checkKeyboardVisibility()
            }
        }
    }

    private fun checkKeyboardVisibility() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        try {
            val winList = windows
            var imeHeight = 0
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val screenHeight = metrics.heightPixels

            for (w in winList) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    val rect = Rect()
                    w.getBoundsInScreen(rect)
                    // キーボードが画面下部に展開されているか
                    if (rect.height() > 200 && rect.bottom >= screenHeight - 150) {
                        imeHeight = rect.height()
                        break
                    }
                }
            }

            val shouldShift = imeHeight > 200
            if (shouldShift != isKeyboardActive) {
                isKeyboardActive = shouldShift
                updateHandleLayoutForKeyboard(imeHeight)
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun updateHandleLayoutForKeyboard(imeHeight: Int) {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenHeight = metrics.heightPixels
        val handleWidth = 100

        if (imeHeight > 200) {
            // キーボード表示中：キーボード上端より上の領域に退避！（バックスペース等への被りを完全排除）
            val availableHeight = screenHeight - imeHeight
            val newHeight = (availableHeight * 0.55f).toInt()
            val newYOffset = -(imeHeight / 2) // 上にシフト退避

            updateOverlayViewParams(leftOverlayView, handleWidth, newHeight, newYOffset, isLeft = true)
            updateOverlayViewParams(rightOverlayView, handleWidth, newHeight, newYOffset, isLeft = false)
            Log.i(TAG, "Keyboard visible (height=$imeHeight): Shifted handles up (newH=$newHeight, yOffset=$newYOffset)")
        } else {
            // キーボード非表示：通常位置へ復帰
            val defaultHeight = (screenHeight * 0.45f).toInt()
            val defaultYOffset = (screenHeight * 0.08f).toInt()

            updateOverlayViewParams(leftOverlayView, handleWidth, defaultHeight, defaultYOffset, isLeft = true)
            updateOverlayViewParams(rightOverlayView, handleWidth, defaultHeight, defaultYOffset, isLeft = false)
            Log.i(TAG, "Keyboard hidden: Restored handles to default position")
        }
    }

    private fun updateOverlayViewParams(view: View?, width: Int, height: Int, yOffset: Int, isLeft: Boolean) {
        if (view == null) return
        try {
            val params = WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = (if (isLeft) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
                y = yOffset
            }
            windowManager.updateViewLayout(view, params)
            excludeSystemGestures(view)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update overlay params", e)
        }
    }

    private fun checkIfGame(pkgName: String) {
        try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(pkgName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                packageManager.getApplicationInfo(pkgName, 0)
            }

            val isGame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appInfo.category == ApplicationInfo.CATEGORY_GAME
            } else {
                @Suppress("DEPRECATION")
                (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            }

            _isCurrentAppGame.value = isGame
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun startSmartCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // キャプチャ直前にエッジパネルや両端のハンドル（青い枠、エッジハンドル含む）を確実に隠す
            dismissEdgePanelOverlay()
            dismissQuickToolsOverlay()
            leftOverlayView?.visibility = View.INVISIBLE
            rightOverlayView?.visibility = View.INVISIBLE
            edgeHandleView?.visibility = View.INVISIBLE
            hideNavBarOverlayView?.visibility = View.INVISIBLE

            // 描画更新がウィンドウマネージャに反映されてからスクリーンショットを取得（写り込み完全防止）
            serviceScope.launch(Dispatchers.Main) {
                delay(120)
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    applicationContext.mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            try {
                                val hardwareBuffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                val hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                val softwareBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                hardwareBuffer.close()

                                if (softwareBitmap != null) {
                                    showCropOverlay(softwareBitmap)
                                } else {
                                    Toast.makeText(this@GoodAccessibilityService, "画像処理に失敗しました", Toast.LENGTH_SHORT).show()
                                    restoreHandleVisibility()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to process screenshot bitmap", e)
                                Toast.makeText(this@GoodAccessibilityService, "エラー: ${e.message}", Toast.LENGTH_SHORT).show()
                                restoreHandleVisibility()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "SmartCapture takeScreenshot failed: $errorCode")
                            Toast.makeText(this@GoodAccessibilityService, "キャプチャの取得に失敗しました", Toast.LENGTH_SHORT).show()
                            restoreHandleVisibility()
                        }
                    }
                )
            }
        } else {
            Toast.makeText(this, "Android 11以上が必要です", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restoreHandleVisibility() {
        val isGame = _isCurrentAppGame.value
        val ohoEnabled = isOhoEnabled(this)
        val visibility = if (ohoEnabled && !isGame) View.VISIBLE else View.GONE
        leftOverlayView?.visibility = visibility
        rightOverlayView?.visibility = visibility
        edgeHandleView?.visibility = if (isGame) View.GONE else View.VISIBLE
        if (isHideNavBarEnabled(this)) {
            hideNavBarOverlayView?.visibility = View.VISIBLE
        }
    }

    fun showQuickToolsOverlay() {
        dismissQuickToolsOverlay()
        dismissEdgePanelOverlay()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        val overlay = QuickToolsOverlayView(
            context = this,
            onDismiss = { dismissQuickToolsOverlay() },
            onTriggerSmartCapture = { startSmartCapture() },
            onTriggerScreenshot = { takeScreenshotAction() },
            onToggleRotation = { toggleAutoRotation(this) }
        )
        quickToolsOverlayView = overlay
        windowManager.addView(overlay, params)
        Log.i(TAG, "QuickToolsOverlayView displayed")
    }

    fun dismissQuickToolsOverlay() {
        quickToolsOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing QuickToolsOverlayView", e)
            }
            quickToolsOverlayView = null
        }
    }

    fun showEdgePanelOverlay() {
        dismissEdgePanelOverlay()
        dismissQuickToolsOverlay()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        val overlay = EdgePanelOverlayView(
            context = this,
            onDismiss = { dismissEdgePanelOverlay() },
            onTriggerSmartCapture = { startSmartCapture() },
            onTriggerQuickTools = { showQuickToolsOverlay() }
        )
        edgePanelOverlayView = overlay
        windowManager.addView(overlay, params)
        Log.i(TAG, "EdgePanelOverlayView displayed")
    }

    fun dismissEdgePanelOverlay() {
        edgePanelOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing EdgePanelOverlayView", e)
            }
            edgePanelOverlayView = null
        }
    }

    private fun showCropOverlay(bitmap: Bitmap) {
        dismissCropOverlay()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val overlay = CropOverlayView(this, bitmap) {
            dismissCropOverlay()
        }
        cropOverlayView = overlay
        windowManager.addView(overlay, params)
        Log.i(TAG, "CropOverlayView displayed on screen (${bitmap.width}x${bitmap.height})")
    }

    private fun dismissCropOverlay() {
        cropOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing CropOverlayView", e)
            }
            cropOverlayView = null
        }
        restoreHandleVisibility()
    }

    fun updateOhoVisibility(enabled: Boolean = isOhoEnabled(this)) {
        val isGame = _isCurrentAppGame.value
        val visibility = if (enabled && !isGame) View.VISIBLE else View.GONE
        leftOverlayView?.visibility = visibility
        rightOverlayView?.visibility = visibility
        Log.d(TAG, "updateOhoVisibility: enabled=$enabled, isGame=$isGame -> visibility=$visibility")
    }

    fun showHideNavBarOverlay() {
        if (hideNavBarOverlayView != null) return

        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val navBarHeight = if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            (resources.displayMetrics.density * 32).toInt()
        }

        // FLAG_NOT_TOUCHABLE によりホーム画面スワイプやアプリ切り替えジェスチャーの操作性を100%維持
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            navBarHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 0
        }

        val overlay = View(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        try {
            windowManager.addView(overlay, params)
            hideNavBarOverlayView = overlay
            Log.i(TAG, "hideNavBarOverlayView displayed (height: $navBarHeight px)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add hideNavBarOverlayView", e)
        }
    }

    fun dismissHideNavBarOverlay() {
        hideNavBarOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing hideNavBarOverlayView", e)
            }
            hideNavBarOverlayView = null
            Log.i(TAG, "hideNavBarOverlayView dismissed")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "GoodAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        gameMonitorJob?.cancel()
        dismissCropOverlay()
        dismissQuickToolsOverlay()
        dismissEdgePanelOverlay()
        dismissHideNavBarOverlay()
        leftOverlayView?.let { windowManager.removeView(it) }
        rightOverlayView?.let { windowManager.removeView(it) }
        edgeHandleView?.let { windowManager.removeView(it) }
        leftOverlayView = null
        rightOverlayView = null
        edgeHandleView = null
        if (isScreenLockReceiverRegistered) {
            try {
                unregisterReceiver(screenLockReceiver)
            } catch (_: Exception) {}
            isScreenLockReceiverRegistered = false
        }
        instance = null
        _isServiceEnabled.value = false
        Log.i(TAG, "GoodAccessibilityService destroyed")
    }
}
