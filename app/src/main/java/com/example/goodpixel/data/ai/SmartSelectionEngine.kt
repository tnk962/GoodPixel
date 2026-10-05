package com.example.goodpixel.data.ai

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * AIによる選択領域推論エンジンのインターフェース
 */
interface SmartSelectionEngine {

    /**
     * スクリーンショットBitmapを受け取り、特徴量抽出など推論の準備を行う
     */
    suspend fun prepare(bitmap: Bitmap)

    /**
     * 指定されたタップ位置（View座標または正規化座標）における対象領域を推論する
     * @param x タップX座標
     * @param y タップY座標
     * @param viewWidth タップを受け取ったViewの幅（0以下の場合はBitmapの幅を使用）
     * @param viewHeight タップを受け取ったViewの高さ（0以下の場合はBitmapの高さを使用）
     * @return 推論結果（View座標系のRectFを含む）
     */
    suspend fun select(
        x: Float,
        y: Float,
        viewWidth: Float = 0f,
        viewHeight: Float = 0f
    ): SmartSelectionResult

    /**
     * エンジンおよびモデルリソースの解放
     */
    fun close()
}

/**
 * 選択結果のデータクラス
 */
data class SmartSelectionResult(
    val rect: RectF?,
    val confidence: Float?,
    val mask: Bitmap? = null,
    val source: SmartSelectionSource,
    val success: Boolean,
    val inferenceTimeMs: Long = 0L,
    val errorMessage: String? = null
)

/**
 * 選択結果の生成元
 */
enum class SmartSelectionSource {
    INTERACTIVE_SEGMENTER,
    EXISTING_DETECTOR,
    FALLBACK
}
