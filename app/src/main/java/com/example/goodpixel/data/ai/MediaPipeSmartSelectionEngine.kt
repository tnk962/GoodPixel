package com.example.goodpixel.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * Google MediaPipe Tasks の Interactive Segmenter (MagicTouch) を利用した
 * AIスマート選択エンジン実装
 */
class MediaPipeSmartSelectionEngine(
    private val context: Context
) : SmartSelectionEngine {

    companion object {
        private const val TAG = "MediaPipeSmartEngine"
        private const val MODEL_NAME = "magic_touch.tflite"

        // デフォルト定数
        const val DEFAULT_MASK_THRESHOLD = 0.5f // 二値化閾値
        const val DEFAULT_PADDING_RATIO = 0.02f // 選択領域の余白比率 (2%)
        const val MIN_AREA_RATIO = 0.0008f      // 画面の0.08%未満はノイズとして排除
        const val MAX_AREA_RATIO = 0.95f        // 画面の95%超は全画面誤検出として排除
    }

    var maskThreshold: Float = DEFAULT_MASK_THRESHOLD
    var paddingRatio: Float = DEFAULT_PADDING_RATIO
    var minAreaRatio: Float = MIN_AREA_RATIO
    var maxAreaRatio: Float = MAX_AREA_RATIO

    private var segmenter: InteractiveSegmenter? = null
    private var cachedMPImage: MPImage? = null
    private var originalBitmap: Bitmap? = null
    private var cachedBitmapWidth: Int = 0
    private var cachedBitmapHeight: Int = 0
    private val engineMutex = Mutex()
    private var isPrepared = false

    override suspend fun prepare(bitmap: Bitmap): Unit = withContext(Dispatchers.Default) {
        engineMutex.withLock {
            val startTime = SystemClock.uptimeMillis()
            try {
                // 1. モデルの初期化（未初期化時のみ）
                if (segmenter == null) {
                    val baseOptions = BaseOptions.builder()
                        .setModelAssetPath(MODEL_NAME)
                        .build()

                    val options = InteractiveSegmenter.InteractiveSegmenterOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setOutputConfidenceMasks(true)
                        .setOutputCategoryMask(false)
                        .build()

                    segmenter = InteractiveSegmenter.createFromOptions(context, options)
                    Log.i(TAG, "InteractiveSegmenter initialized successfully")
                }

                // 2. スクリーンショットBitmapの保持とMPImageの事前構築
                cachedMPImage?.close()
                originalBitmap = bitmap
                cachedBitmapWidth = bitmap.width
                cachedBitmapHeight = bitmap.height

                // ARGB_8888 への変換保証（MediaPipeの入力要件）
                val argbBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
                    bitmap.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    bitmap
                }
                cachedMPImage = BitmapImageBuilder(argbBitmap).build()
                isPrepared = true

                val elapsed = SystemClock.uptimeMillis() - startTime
                Log.i(TAG, "Prepared screenshot image in ${elapsed}ms (${cachedBitmapWidth}x${cachedBitmapHeight})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare MediaPipeSmartSelectionEngine", e)
                isPrepared = false
            }
        }
    }

    override suspend fun select(
        x: Float,
        y: Float,
        viewWidth: Float,
        viewHeight: Float
    ): SmartSelectionResult = withContext(Dispatchers.Default) {
        val startTime = SystemClock.uptimeMillis()

        // キャンセル確認
        coroutineContext.ensureActive()

        engineMutex.withLock {
            coroutineContext.ensureActive()

            val seg = segmenter
            val mpImage = cachedMPImage

            if (!isPrepared || seg == null || mpImage == null) {
                Log.w(TAG, "Segmenter not ready, fallback")
                return@withContext SmartSelectionResult(
                    rect = null,
                    confidence = null,
                    source = SmartSelectionSource.FALLBACK,
                    success = false,
                    errorMessage = "Engine not prepared"
                )
            }

            val actualViewW = if (viewWidth > 0f) viewWidth else cachedBitmapWidth.toFloat()
            val actualViewH = if (viewHeight > 0f) viewHeight else cachedBitmapHeight.toFloat()

            // 1. View座標からMediaPipeの正規化座標(0.0〜1.0)へ変換
            val normX = (x / actualViewW).coerceIn(0f, 1f)
            val normY = (y / actualViewH).coerceIn(0f, 1f)

            try {
                // 2. RegionOfInterest の作成
                val keypoint = NormalizedKeypoint.create(normX, normY)
                val roi = InteractiveSegmenter.RegionOfInterest.create(keypoint)

                // 3. 推論実行
                val inferStart = SystemClock.uptimeMillis()
                val result = seg.segment(mpImage, roi)
                val inferTime = SystemClock.uptimeMillis() - inferStart
                Log.d(TAG, "Inference completed in ${inferTime}ms for tap at ($normX, $normY)")

                coroutineContext.ensureActive()

                // 4. マスクの取得
                val masksOpt = result.confidenceMasks()
                if (!masksOpt.isPresent || masksOpt.get().isEmpty()) {
                    Log.w(TAG, "No confidence masks returned")
                    return@withContext fallbackResult(x, y, actualViewW, actualViewH, startTime, "No masks returned")
                }

                val maskList = masksOpt.get()
                Log.d(TAG, "Confidence masks count: ${maskList.size}")

                val maskImage = maskList[0]
                val maskW = maskImage.width
                val maskH = maskImage.height
                val totalMaskPixels = maskW * maskH

                val byteBuffer: ByteBuffer = ByteBufferExtractor.extract(maskImage)
                val isFloatBuffer = byteBuffer.limit() >= totalMaskPixels * 4
                val floatBuffer: FloatBuffer? = if (isFloatBuffer) byteBuffer.asFloatBuffer() else null

                // タップ位置に対応するマスク内ピクセル座標
                val tapMx = (normX * (maskW - 1)).toInt().coerceIn(0, maskW - 1)
                val tapMy = (normY * (maskH - 1)).toInt().coerceIn(0, maskH - 1)

                fun getConfAt(mx: Int, my: Int): Float {
                    val idx = my * maskW + mx
                    return if (isFloatBuffer && floatBuffer != null) {
                        floatBuffer.get(idx)
                    } else {
                        (byteBuffer.get(idx).toInt() and 0xFF) / 255.0f
                    }
                }

                // タップ位置周辺の最大信頼度を探索
                var seedConf = 0f
                var seedMx = tapMx
                var seedMy = tapMy
                val searchRadius = 16
                for (dy in -searchRadius..searchRadius) {
                    val sy = tapMy + dy
                    if (sy !in 0 until maskH) continue
                    for (dx in -searchRadius..searchRadius) {
                        val sx = tapMx + dx
                        if (sx !in 0 until maskW) continue
                        val c = getConfAt(sx, sy)
                        if (c > seedConf) {
                            seedConf = c
                            seedMx = sx
                            seedMy = sy
                        }
                    }
                }

                // マスク全体の統計量（min, max, avg, 最大値の座標）を計測
                var globalMin = Float.MAX_VALUE
                var globalMax = -Float.MAX_VALUE
                var globalSum = 0.0
                var maxCoordX = 0
                var maxCoordY = 0

                // サンプリング走査で全体傾向を即座に把握
                val step = 4
                for (sy in 0 until maskH step step) {
                    for (sx in 0 until maskW step step) {
                        val c = getConfAt(sx, sy)
                        if (c < globalMin) globalMin = c
                        if (c > globalMax) {
                            globalMax = c
                            maxCoordX = sx
                            maxCoordY = sy
                        }
                        globalSum += c
                    }
                }
                val sampleCount = (maskH / step) * (maskW / step)
                val globalAvg = if (sampleCount > 0) globalSum / sampleCount else 0.0

                Log.d(TAG, "Global mask stats: min=$globalMin, max=$globalMax, avg=$globalAvg, maxAt=($maxCoordX, $maxCoordY)")
                Log.d(TAG, "Mask size: ${maskW}x${maskH}, tap=($tapMx,$tapMy), seedConf=$seedConf at ($seedMx,$seedMy)")

                // 適応的閾値の決定（シードの信頼度に応じて柔軟に調整）
                val effectiveThreshold = if (seedConf > 0.15f) {
                    min(maskThreshold, seedConf * 0.35f).coerceAtLeast(0.12f)
                } else {
                    0.20f
                }

                // 5. タップ位置を起点とするBFS Flood Fill（近傍連結成分探索）
                // 画面端のノイズ（最上部や最下部）を巻き込まず、タップ対象のみを抽出
                val visited = java.util.BitSet(totalMaskPixels)
                val queue = java.util.ArrayDeque<Int>()

                val startIdx = seedMy * maskW + seedMx
                if (seedConf >= effectiveThreshold) {
                    queue.add(startIdx)
                    visited.set(startIdx)
                }

                var minPx = seedMx
                var minPy = seedMy
                var maxPx = seedMx
                var maxPy = seedMy
                var positivePixels = 0
                var confidenceSum = 0f
                val maxSearchPixels = 350_000 // パフォーマンス保護上限

                val dDirs = intArrayOf(0, -1, 0, 1, -1, 0, 1, 0) // 上下左右

                while (!queue.isEmpty() && positivePixels < maxSearchPixels) {
                    val curr = queue.poll() ?: break
                    val cy = curr / maskW
                    val cx = curr % maskW
                    val conf = getConfAt(cx, cy)

                    positivePixels++
                    confidenceSum += conf
                    if (cx < minPx) minPx = cx
                    if (cx > maxPx) maxPx = cx
                    if (cy < minPy) minPy = cy
                    if (cy > maxPy) maxPy = cy

                    for (d in 0 until 4) {
                        val nx = cx + dDirs[d * 2]
                        val ny = cy + dDirs[d * 2 + 1]
                        if (nx in 0 until maskW && ny in 0 until maskH) {
                            val nIdx = ny * maskW + nx
                            if (!visited.get(nIdx)) {
                                visited.set(nIdx)
                                val nc = getConfAt(nx, ny)
                                if (nc >= effectiveThreshold) {
                                    queue.add(nIdx)
                                }
                            }
                        }
                    }
                }

                Log.d(TAG, "BFS result: positivePixels=$positivePixels, bounds=[$minPx,$minPy,$maxPx,$maxPy], th=$effectiveThreshold")

                // 連結ピクセルが少なすぎる、または有効な領域がない場合はタップ近傍フォールバック矩形を生成
                val minPixels = (totalMaskPixels * 0.0001f).toInt().coerceAtLeast(30)
                if (positivePixels < minPixels || minPx >= maxPx || minPy >= maxPy) {
                    Log.d(TAG, "AI region too small ($positivePixels < $minPixels), using tap vicinity fallback")
                    return@withContext fallbackResult(x, y, actualViewW, actualViewH, startTime, "Vicinity fallback")
                }

                // 6. マスク座標系からView座標系へ変換
                val viewLeft = (minPx.toFloat() / maskW) * actualViewW
                val viewTop = (minPy.toFloat() / maskH) * actualViewH
                val viewRight = ((maxPx.toFloat() + 1f) / maskW) * actualViewW
                val viewBottom = ((maxPy.toFloat() + 1f) / maskH) * actualViewH

                val rect = RectF(viewLeft, viewTop, viewRight, viewBottom)

                // 7. Bounding Rectの品質補正（パディング付与）
                val pad = min(rect.width(), rect.height()) * paddingRatio
                rect.inset(-pad, -pad)

                // 画面外への飛び出しをクランプ
                rect.left = max(0f, rect.left)
                rect.top = max(0f, rect.top)
                rect.right = min(actualViewW, rect.right)
                rect.bottom = min(actualViewH, rect.bottom)

                // 8. 全画面誤検出（95%超）のチェック
                val finalAreaRatio = (rect.width() * rect.height()) / (actualViewW * actualViewH)
                if (finalAreaRatio > maxAreaRatio) {
                    Log.d(TAG, "Final rect area ratio too large: $finalAreaRatio, using fallback")
                    return@withContext fallbackResult(x, y, actualViewW, actualViewH, startTime, "Area too large")
                }

                val avgConfidence = if (positivePixels > 0) confidenceSum / positivePixels else seedConf
                val totalTime = SystemClock.uptimeMillis() - startTime
                Log.i(TAG, "SmartSelect Success! Rect: $rect, Conf: $avgConfidence, Time: ${totalTime}ms")

                return@withContext SmartSelectionResult(
                    rect = rect,
                    confidence = avgConfidence,
                    source = SmartSelectionSource.INTERACTIVE_SEGMENTER,
                    success = true,
                    inferenceTimeMs = totalTime
                )

            } catch (e: Exception) {
                Log.e(TAG, "Exception during select inference", e)
                return@withContext fallbackResult(x, y, actualViewW, actualViewH, startTime, e.message)
            }
        }
    }

    /**
     * AI未検出・低確信度時のスマート領域センシング
     * スクリーンショット画像そのもの（Bitmap）の色差・輝度勾配・輪郭を解析し、
     * タップ位置にあるUI要素（ボタン、アイコン、カード、テキストブロック等）の境界を自動検出する
     */
    private fun fallbackResult(
        tapX: Float,
        tapY: Float,
        viewW: Float,
        viewH: Float,
        startTime: Long,
        message: String?
    ): SmartSelectionResult {
        val totalTime = SystemClock.uptimeMillis() - startTime
        val bmp = originalBitmap

        if (bmp != null && !bmp.isRecycled) {
            try {
                val edgeRect = detectEdgeAwareRect(bmp, tapX, tapY, viewW, viewH)
                if (edgeRect != null && edgeRect.width() > 40 && edgeRect.height() > 40) {
                    Log.i(TAG, "Edge-Aware Sensing Succeeded! Detected Rect: $edgeRect for tap at ($tapX, $tapY)")
                    return SmartSelectionResult(
                        rect = edgeRect,
                        confidence = 0.85f,
                        source = SmartSelectionSource.EXISTING_DETECTOR,
                        success = true,
                        inferenceTimeMs = totalTime
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error in detectEdgeAwareRect", e)
            }
        }

        // 輪郭検出が万一失敗した場合のセーフティネット（タップ位置を中心とするデフォルト枠）
        val defaultSize = min(viewW, viewH) * 0.28f
        val halfW = defaultSize / 2f
        val halfH = defaultSize / 2f

        val left = max(0f, min(viewW - defaultSize, tapX - halfW))
        val top = max(0f, min(viewH - defaultSize, tapY - halfH))
        val fallbackRect = RectF(left, top, left + defaultSize, top + defaultSize)

        Log.i(TAG, "Generated Tap-Vicinity Fallback Rect: $fallbackRect for tap at ($tapX, $tapY)")

        return SmartSelectionResult(
            rect = fallbackRect,
            confidence = 0.5f,
            source = SmartSelectionSource.FALLBACK,
            success = true,
            inferenceTimeMs = totalTime,
            errorMessage = message
        )
    }

    /**
     * 画像の色勾配・コントラスト・境界線を多方向スキャンして対象物領域を推定する
     */
    private fun detectEdgeAwareRect(
        bitmap: Bitmap,
        tapX: Float,
        tapY: Float,
        viewW: Float,
        viewH: Float
    ): RectF? {
        val scaleX = bitmap.width.toFloat() / viewW
        val scaleY = bitmap.height.toFloat() / viewH

        val bx = (tapX * scaleX).toInt().coerceIn(0, bitmap.width - 1)
        val by = (tapY * scaleY).toInt().coerceIn(0, bitmap.height - 1)

        val centerPixel = bitmap.getPixel(bx, by)

        fun colorDist(c1: Int, c2: Int): Float {
            val r = (android.graphics.Color.red(c1) - android.graphics.Color.red(c2)).toFloat()
            val g = (android.graphics.Color.green(c1) - android.graphics.Color.green(c2)).toFloat()
            val b = (android.graphics.Color.blue(c1) - android.graphics.Color.blue(c2)).toFloat()
            return kotlin.math.sqrt(r * r + g * g + b * b)
        }

        val maxScanX = (bitmap.width * 0.45f).toInt()
        val maxScanY = (bitmap.height * 0.35f).toInt()

        // 1. 左方向の境界探索
        var leftBx = bx
        var prevP = centerPixel
        for (x in bx - 1 downTo max(0, bx - maxScanX)) {
            val p = bitmap.getPixel(x, by)
            val grad = colorDist(p, prevP)
            val diffFromCenter = colorDist(p, centerPixel)
            if (grad > 32f || diffFromCenter > 68f) {
                leftBx = x
                break
            }
            prevP = p
            leftBx = x
        }

        // 2. 右方向の境界探索
        var rightBx = bx
        prevP = centerPixel
        for (x in bx + 1 until min(bitmap.width, bx + maxScanX)) {
            val p = bitmap.getPixel(x, by)
            val grad = colorDist(p, prevP)
            val diffFromCenter = colorDist(p, centerPixel)
            if (grad > 32f || diffFromCenter > 68f) {
                rightBx = x
                break
            }
            prevP = p
            rightBx = x
        }

        // 3. 上方向の境界探索
        var topBy = by
        prevP = centerPixel
        for (y in by - 1 downTo max(0, by - maxScanY)) {
            val p = bitmap.getPixel(bx, y)
            val grad = colorDist(p, prevP)
            val diffFromCenter = colorDist(p, centerPixel)
            if (grad > 32f || diffFromCenter > 68f) {
                topBy = y
                break
            }
            prevP = p
            topBy = y
        }

        // 4. 下方向の境界探索
        var bottomBy = by
        prevP = centerPixel
        for (y in by + 1 until min(bitmap.height, by + maxScanY)) {
            val p = bitmap.getPixel(bx, y)
            val grad = colorDist(p, prevP)
            val diffFromCenter = colorDist(p, centerPixel)
            if (grad > 32f || diffFromCenter > 68f) {
                bottomBy = y
                break
            }
            prevP = p
            bottomBy = y
        }

        // View座標系へ変換
        var vLeft = leftBx / scaleX
        var vRight = rightBx / scaleX
        var vTop = topBy / scaleY
        var vBottom = bottomBy / scaleY

        // 最小サイズの補正（タップ周囲の極小ノイズを防止）
        val minDim = min(viewW, viewH) * 0.14f
        if (vRight - vLeft < minDim) {
            val add = (minDim - (vRight - vLeft)) / 2f
            vLeft = max(0f, vLeft - add)
            vRight = min(viewW, vRight + add)
        }
        if (vBottom - vTop < minDim) {
            val add = (minDim - (vBottom - vTop)) / 2f
            vTop = max(0f, vTop - add)
            vBottom = min(viewH, vBottom + add)
        }

        // 適度な余白(3%)
        val pad = min(vRight - vLeft, vBottom - vTop) * 0.03f
        return RectF(
            max(0f, vLeft - pad),
            max(0f, vTop - pad),
            min(viewW, vRight + pad),
            min(viewH, vBottom + pad)
        )
    }

    override fun close() {
        try {
            cachedMPImage?.close()
            cachedMPImage = null
            originalBitmap = null
            segmenter?.close()
            segmenter = null
            isPrepared = false
            Log.i(TAG, "MediaPipeSmartSelectionEngine closed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing MediaPipeSmartSelectionEngine", e)
        }
    }
}
