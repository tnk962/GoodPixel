package com.example.goodpixel.ui.crop

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.example.goodpixel.data.ai.MediaPipeSmartSelectionEngine
import com.example.goodpixel.data.ai.SmartSelectionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * スマート選択（部分切り抜き）用オーバーレイView
 * - 指でドラッグして四角く囲む
 * - 対象物をタップしてAI（Interactive Segmenter）による自動選択
 * - 枠内ドラッグで移動、四隅・4辺ドラッグでリサイズ
 * - 枠外タップで再選択
 * - 写真ファイル（PNG）として Pictures/GoodPixel フォルダに保存
 */
@SuppressLint("ViewConstructor")
class CropOverlayView(
    context: Context,
    private val screenshotBitmap: Bitmap,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    companion object {
        private const val TAG = "CropOverlayView"
    }

    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val smartSelectionEngine: SmartSelectionEngine = MediaPipeSmartSelectionEngine(context.applicationContext)

    private val cropCanvasView = CropCanvasView(context)
    private val buttonBar = LinearLayout(context)
    private val saveButton = Button(context)
    private val cancelButton = Button(context)
    private var isDismissed = false

    init {
        fitsSystemWindows = false
        @Suppress("DEPRECATION")
        systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        // 全画面キャンバス
        addView(cropCanvasView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 下部アクションボタンバー
        buttonBar.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(36, 16, 36, 16)
            val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(225, 25, 25, 25))
                cornerRadius = 60f
            }
            background = bgDrawable
            elevation = 16f
            visibility = View.GONE
        }

        cancelButton.apply {
            text = "キャンセル"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#444444"))
            setOnClickListener { dismiss() }
        }

        saveButton.apply {
            text = "✓ 写真を保存"
            setBackgroundColor(Color.parseColor("#1A73E8"))
            setTextColor(Color.WHITE)
            setOnClickListener { saveSelectedCrop() }
        }

        val buttonParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(12, 0, 12, 0)
        }

        buttonBar.addView(cancelButton, buttonParams)
        buttonBar.addView(saveButton, buttonParams)

        val barParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 80
        }
        addView(buttonBar, barParams)

        // AIエンジンの事前初期化と特徴量抽出をバックグラウンドで開始
        viewScope.launch {
            smartSelectionEngine.prepare(screenshotBitmap)
        }
    }

    private fun dismiss() {
        if (isDismissed) return
        isDismissed = true
        cleanup()
        onDismiss()
    }

    private fun cleanup() {
        try {
            viewScope.cancel()
            smartSelectionEngine.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error in cleanup", e)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cleanup()
    }

    private fun saveSelectedCrop() {
        val rect = cropCanvasView.selectedRect
        if (rect == null || rect.width() < 10 || rect.height() < 10) {
            Toast.makeText(context, "保存する範囲を選択してください", Toast.LENGTH_SHORT).show()
            return
        }

        val viewW = cropCanvasView.width.toFloat()
        val viewH = cropCanvasView.height.toFloat()
        if (viewW <= 0 || viewH <= 0) return

        // 画面View座標系から、元のBitmap実ピクセル座標系へ正確にスケール変換
        val scaleX = screenshotBitmap.width.toFloat() / viewW
        val scaleY = screenshotBitmap.height.toFloat() / viewH

        val cropLeft = max(0, (rect.left * scaleX).toInt())
        val cropTop = max(0, (rect.top * scaleY).toInt())
        val cropWidth = min(screenshotBitmap.width - cropLeft, (rect.width() * scaleX).toInt())
        val cropHeight = min(screenshotBitmap.height - cropTop, (rect.height() * scaleY).toInt())

        if (cropWidth <= 0 || cropHeight <= 0) {
            Toast.makeText(context, "選択範囲が無効です", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // 指定領域をピクセル単位で正確にクロップ
            val croppedBitmap = Bitmap.createBitmap(screenshotBitmap, cropLeft, cropTop, cropWidth, cropHeight)

            // MediaStore (Pictures/GoodPixel) に保存
            val filename = "SmartCapture_${System.currentTimeMillis()}.png"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GoodPixel")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            if (imageUri != null) {
                resolver.openOutputStream(imageUri)?.use { out ->
                    croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(imageUri, contentValues, null, null)
                }

                Toast.makeText(context, "写真を保存しました 📷", Toast.LENGTH_SHORT).show()
                Log.i(TAG, "Saved image to: $imageUri ($cropWidth x $cropHeight)")
            } else {
                Toast.makeText(context, "保存に失敗しました", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save error", e)
            Toast.makeText(context, "エラー: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            dismiss()
        }
    }

    private enum class TouchMode {
        NONE, CREATE, MOVE,
        RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR,
        RESIZE_L, RESIZE_R, RESIZE_T, RESIZE_B
    }

    /**
     * 高度な矩形選択キャンバス（移動・リサイズ・再選択対応）
     */
    inner class CropCanvasView(context: Context) : View(context) {

        var selectedRect: RectF? = null
            private set

        private var touchMode = TouchMode.NONE
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private val handleRadius = 48f // 角ハンドルの当たり判定サイズ
        private val edgeMargin = 42f // 4辺の当たり判定幅
        private val snapThreshold = 36f // 画面端への吸着（スナップ）しきい値(px)
        private var wasSnapped = false

        // AI選択関連
        private var aiSelectJob: Job? = null
        private var touchDownX = 0f
        private var touchDownY = 0f
        private var isPotentialTap = false

        // AI思考中パルスアニメーション
        private var aiPulseCenter: PointF? = null
        private var aiPulseRadius = 0f
        private var aiPulseAlpha = 0
        private var pulseAnimator: ValueAnimator? = null

        private val pulseStrokePaint = Paint().apply {
            color = Color.parseColor("#4285F4")
            style = Paint.Style.STROKE
            strokeWidth = 5f
            isAntiAlias = true
        }

        private val pulseFillPaint = Paint().apply {
            color = Color.parseColor("#1A73E8")
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val dimPaint = Paint().apply {
            color = Color.argb(135, 0, 0, 0)
        }

        private val clearPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }

        private val borderPaint = Paint().apply {
            color = Color.parseColor("#1A73E8") // Google Blue
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
        }

        private val cornerOuterPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val cornerInnerPaint = Paint().apply {
            color = Color.parseColor("#1A73E8")
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        init {
            fitsSystemWindows = false
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        /**
         * 画面端への吸着および「剥がし」処理
         * 端に向かうときはスナップし、端から引き離すときは吸着を解除してスムーズに動かせる
         */
        private fun snapDimension(targetVal: Float, curVal: Float, maxLimit: Float): Float {
            // 0f 端（左端または上端）
            if (curVal <= 0f) {
                // すでに端に吸着している場合：内側へ12px以上引っ張ったら吸着を解除して剥がす
                return if (targetVal > 12f) targetVal else 0f
            } else if (targetVal < snapThreshold && targetVal < curVal) {
                // 端に向かって移動している時のみスナップ
                return 0f
            }

            // maxLimit 端（右端または下端）
            if (curVal >= maxLimit) {
                // すでに端に吸着している場合：内側へ12px以上引っ張ったら吸着を解除して剥がす
                return if (targetVal < maxLimit - 12f) targetVal else maxLimit
            } else if (targetVal > maxLimit - snapThreshold && targetVal > curVal) {
                // 端に向かって移動している時のみスナップ
                return maxLimit
            }

            return targetVal
        }

        private fun snapX(valX: Float): Float {
            return when {
                valX < snapThreshold -> 0f
                valX > width - snapThreshold -> width.toFloat()
                else -> valX
            }
        }

        private fun snapY(valY: Float): Float {
            return when {
                valY < snapThreshold -> 0f
                valY > height - snapThreshold -> height.toFloat()
                else -> valY
            }
        }

        private fun checkSnapFeedback(rect: RectF) {
            val isSnapped = (rect.left == 0f || rect.right == width.toFloat() ||
                    rect.top == 0f || rect.bottom == height.toFloat())
            if (isSnapped && !wasSnapped) {
                // 画面端にカチッと吸着した瞬間に微小なハプティック振動
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            }
            wasSnapped = isSnapped
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // 1. 静止画Bitmapを画面サイズに合わせて描画
            val srcRect = Rect(0, 0, screenshotBitmap.width, screenshotBitmap.height)
            val dstRect = Rect(0, 0, width, height)
            canvas.drawBitmap(screenshotBitmap, srcRect, dstRect, null)

            // 2. 選択領域があれば切り抜き効果を描画
            val rect = selectedRect
            if (rect != null && rect.width() > 10 && rect.height() > 10) {
                // 背景全体を半透明黒で暗転
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

                // 選択範囲をくり抜いて明るく表示
                canvas.drawRect(rect, clearPaint)

                // 境界線
                canvas.drawRect(rect, borderPaint)

                // 四隅にGalaxyスマート選択風の操作ハンドルを描画
                drawHandle(canvas, rect.left, rect.top)
                drawHandle(canvas, rect.right, rect.top)
                drawHandle(canvas, rect.left, rect.bottom)
                drawHandle(canvas, rect.right, rect.bottom)

                // 4辺の中央にも小さな丸ハンドルを描画
                val midX = (rect.left + rect.right) / 2
                val midY = (rect.top + rect.bottom) / 2
                drawHandle(canvas, midX, rect.top, 10f)
                drawHandle(canvas, midX, rect.bottom, 10f)
                drawHandle(canvas, rect.left, midY, 10f)
                drawHandle(canvas, rect.right, midY, 10f)
            }

            // 3. AI思考中パルスアニメーションを描画
            val pulse = aiPulseCenter
            if (pulse != null && aiPulseRadius > 0f) {
                pulseFillPaint.alpha = (aiPulseAlpha * 0.25f).toInt()
                pulseStrokePaint.alpha = aiPulseAlpha
                canvas.drawCircle(pulse.x, pulse.y, aiPulseRadius, pulseFillPaint)
                canvas.drawCircle(pulse.x, pulse.y, aiPulseRadius, pulseStrokePaint)
            }
        }

        private fun startAiPulse(tapX: Float, tapY: Float) {
            pulseAnimator?.cancel()
            aiPulseCenter = PointF(tapX, tapY)
            pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 600
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    val fraction = anim.animatedFraction
                    aiPulseRadius = 15f + fraction * 75f
                    aiPulseAlpha = ((1f - fraction) * 220).toInt()
                    invalidate()
                }
                start()
            }
        }

        private fun stopAiPulse() {
            pulseAnimator?.cancel()
            pulseAnimator = null
            aiPulseCenter = null
            invalidate()
        }

        private fun triggerAiSelection(tapX: Float, tapY: Float) {
            // 前回の推論タスクをキャンセル（連打対策）
            aiSelectJob?.cancel()
            startAiPulse(tapX, tapY)
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)

            aiSelectJob = viewScope.launch {
                val result = smartSelectionEngine.select(
                    x = tapX,
                    y = tapY,
                    viewWidth = width.toFloat(),
                    viewHeight = height.toFloat()
                )
                stopAiPulse()

                if (result.success && result.rect != null) {
                    selectedRect = result.rect
                    buttonBar.visibility = View.VISIBLE
                    performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                    invalidate()
                    Log.i(TAG, "AI Auto-Select applied: ${result.rect} (source: ${result.source}, conf: ${result.confidence}, time: ${result.inferenceTimeMs}ms)")
                } else {
                    Log.d(TAG, "AI Auto-Select fallback/no-result: ${result.errorMessage}")
                    selectedRect = null
                    buttonBar.visibility = View.GONE
                    invalidate()
                }
            }
        }

        /**
         * 選択枠の内側タップ時に、枠を一回りスムーズに拡大する
         */
        private fun expandSelectedRect() {
            val cur = selectedRect ?: return
            val expandX = max(48f, cur.width() * 0.18f)
            val expandY = max(48f, cur.height() * 0.18f)

            val newLeft = max(0f, cur.left - expandX)
            val newTop = max(0f, cur.top - expandY)
            val newRight = min(width.toFloat(), cur.right + expandX)
            val newBottom = min(height.toFloat(), cur.bottom + expandY)

            selectedRect = RectF(newLeft, newTop, newRight, newBottom)
            buttonBar.visibility = View.VISIBLE
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
            Log.i(TAG, "Expanded selectedRect to: $selectedRect")
        }

        private fun drawHandle(canvas: Canvas, cx: Float, cy: Float, radius: Float = 14f) {
            canvas.drawCircle(cx, cy, radius + 4f, cornerOuterPaint)
            canvas.drawCircle(cx, cy, radius, cornerInnerPaint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            // ★ローカル座標 (event.x, event.y) を使用して位置ズレを完全解消
            val x = event.x
            val y = event.y

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = x
                    touchDownY = y
                    lastTouchX = x
                    lastTouchY = y
                    wasSnapped = false

                    val rect = selectedRect
                    touchMode = if (rect != null && rect.width() > 10 && rect.height() > 10) {
                        getHitMode(x, y, rect)
                    } else {
                        TouchMode.CREATE
                    }

                    // 枠外タップまたは枠内タップのとき、タップ判定候補とする
                    isPotentialTap = (touchMode == TouchMode.CREATE || touchMode == TouchMode.MOVE)

                    if (touchMode == TouchMode.CREATE) {
                        selectedRect = RectF(x, y, x, y)
                        buttonBar.visibility = View.GONE
                    }
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val moveDist = Math.hypot((x - touchDownX).toDouble(), (y - touchDownY).toDouble()).toFloat()
                    if (moveDist > 24f) {
                        isPotentialTap = false
                    }

                    val dx = x - lastTouchX
                    val dy = y - lastTouchY
                    val rect = selectedRect ?: return true

                    when (touchMode) {
                        TouchMode.CREATE -> {
                            val rawLeft = min(lastTouchX, x)
                            val rawTop = min(lastTouchY, y)
                            val rawRight = max(lastTouchX, x)
                            val rawBottom = max(lastTouchY, y)

                            val left = snapX(rawLeft)
                            val top = snapY(rawTop)
                            val right = snapX(rawRight)
                            val bottom = snapY(rawBottom)

                            val newRect = RectF(left, top, right, bottom)
                            checkSnapFeedback(newRect)
                            selectedRect = newRect
                        }
                        TouchMode.MOVE -> {
                            // 枠全体の移動
                            val rectW = rect.width()
                            val rectH = rect.height()
                            var newLeft = rect.left + dx
                            var newTop = rect.top + dy

                            // 左端の吸着 & 剥がし
                            if (rect.left <= 0f) {
                                // すでに左端に吸着している場合：右へ引っ張れば即座に剥がす
                                if (dx > 0) newLeft = rect.left + dx else newLeft = 0f
                            } else if (newLeft < snapThreshold && dx < 0) {
                                newLeft = 0f
                            }

                            // 右端の吸着 & 剥がし
                            val maxLeft = width - rectW
                            if (rect.right >= width.toFloat()) {
                                // すでに右端に吸着している場合：左へ引っ張れば即座に剥がす
                                if (dx < 0) newLeft = rect.left + dx else newLeft = maxLeft
                            } else if (newLeft > maxLeft - snapThreshold && dx > 0) {
                                newLeft = maxLeft
                            }

                            // 上端の吸着 & 剥がし
                            if (rect.top <= 0f) {
                                // すでに上端に吸着している場合：下へ引っ張れば即座に剥がす
                                if (dy > 0) newTop = rect.top + dy else newTop = 0f
                            } else if (newTop < snapThreshold && dy < 0) {
                                newTop = 0f
                            }

                            // 下端の吸着 & 剥がし
                            val maxTop = height - rectH
                            if (rect.bottom >= height.toFloat()) {
                                // すでに下端に吸着している場合：上へ引っ張れば即座に剥がす
                                if (dy < 0) newTop = rect.top + dy else newTop = maxTop
                            } else if (newTop > maxTop - snapThreshold && dy > 0) {
                                newTop = maxTop
                            }

                            // 画面外への飛び出し防止
                            newLeft = max(0f, min(newLeft, width - rectW))
                            newTop = max(0f, min(newTop, height - rectH))

                            rect.set(newLeft, newTop, newLeft + rectW, newTop + rectH)
                            checkSnapFeedback(rect)
                            lastTouchX = x
                            lastTouchY = y
                        }
                        TouchMode.RESIZE_TL -> {
                            rect.left = snapDimension(min(x, rect.right - 20), rect.left, width.toFloat())
                            rect.top = snapDimension(min(y, rect.bottom - 20), rect.top, height.toFloat())
                            checkSnapFeedback(rect)
                        }
                        TouchMode.RESIZE_TR -> {
                            rect.right = snapDimension(max(x, rect.left + 20), rect.right, width.toFloat())
                            rect.top = snapDimension(min(y, rect.bottom - 20), rect.top, height.toFloat())
                            checkSnapFeedback(rect)
                        }
                        TouchMode.RESIZE_BL -> {
                            rect.left = snapDimension(min(x, rect.right - 20), rect.left, width.toFloat())
                            rect.bottom = snapDimension(max(y, rect.top + 20), rect.bottom, height.toFloat())
                            checkSnapFeedback(rect)
                        }
                        TouchMode.RESIZE_BR -> {
                            rect.right = snapDimension(max(x, rect.left + 20), rect.right, width.toFloat())
                            rect.bottom = snapDimension(max(y, rect.top + 20), rect.bottom, height.toFloat())
                            checkSnapFeedback(rect)
                        }
                        TouchMode.RESIZE_L -> {
                            rect.left = snapDimension(min(x, rect.right - 20), rect.left, width.toFloat())
                            checkSnapFeedback(rect)
                        }
                        TouchMode.RESIZE_R -> {
                            rect.right = snapDimension(max(x, rect.left + 20), rect.right, width.toFloat())
                            checkSnapFeedback(rect)
                        }
                        TouchMode.RESIZE_T -> {
                            rect.top = snapDimension(min(y, rect.bottom - 20), rect.top, height.toFloat())
                            checkSnapFeedback(rect)
                        }
                        TouchMode.RESIZE_B -> {
                            rect.bottom = snapDimension(max(y, rect.top + 20), rect.bottom, height.toFloat())
                            checkSnapFeedback(rect)
                        }
                        TouchMode.NONE -> {}
                    }

                    invalidate()
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    val moveDist = Math.hypot((x - touchDownX).toDouble(), (y - touchDownY).toDouble()).toFloat()
                    if (isPotentialTap && moveDist <= 24f) {
                        val curRect = selectedRect
                        if (touchMode == TouchMode.MOVE && curRect != null && curRect.width() > 10 && curRect.height() > 10 && curRect.contains(x, y)) {
                            // 選択枠の内側を再タップ：枠を段階的に周囲へ拡大！
                            expandSelectedRect()
                        } else if (touchMode == TouchMode.CREATE) {
                            // 新規タップ（枠外または未選択）：AIスマート選択（失敗時は近傍選択）を実行
                            triggerAiSelection(x, y)
                        }
                    } else {
                        // ドラッグ操作による矩形選択確定または移動・リサイズ確定
                        val rect = selectedRect
                        if (rect != null && rect.width() > 30 && rect.height() > 30) {
                            buttonBar.visibility = View.VISIBLE
                        } else {
                            selectedRect = null
                            buttonBar.visibility = View.GONE
                        }
                    }
                    touchMode = TouchMode.NONE
                    wasSnapped = false
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    stopAiPulse()
                    aiSelectJob?.cancel()
                    selectedRect = null
                    buttonBar.visibility = View.GONE
                    touchMode = TouchMode.NONE
                    wasSnapped = false
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        /**
         * タッチされた位置が「四隅」「4辺」「枠内」「枠外」のどこかを判定
         */
        private fun getHitMode(x: Float, y: Float, rect: RectF): TouchMode {
            // 四隅の当たり判定
            if (isNear(x, y, rect.left, rect.top)) return TouchMode.RESIZE_TL
            if (isNear(x, y, rect.right, rect.top)) return TouchMode.RESIZE_TR
            if (isNear(x, y, rect.left, rect.bottom)) return TouchMode.RESIZE_BL
            if (isNear(x, y, rect.right, rect.bottom)) return TouchMode.RESIZE_BR

            // 4辺の当たり判定
            if (abs(x - rect.left) < edgeMargin && y >= rect.top && y <= rect.bottom) return TouchMode.RESIZE_L
            if (abs(x - rect.right) < edgeMargin && y >= rect.top && y <= rect.bottom) return TouchMode.RESIZE_R
            if (abs(y - rect.top) < edgeMargin && x >= rect.left && x <= rect.right) return TouchMode.RESIZE_T
            if (abs(y - rect.bottom) < edgeMargin && x >= rect.left && x <= rect.right) return TouchMode.RESIZE_B

            // 枠内
            if (rect.contains(x, y)) return TouchMode.MOVE

            // 枠外 → 新規作成
            return TouchMode.CREATE
        }

        private fun isNear(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
            val dx = x1 - x2
            val dy = y1 - y2
            return (dx * dx + dy * dy) <= (handleRadius * handleRadius)
        }
    }
}
