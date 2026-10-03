package com.example.goodpixel.ui.edge

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.goodpixel.data.edge.EdgeAppManager
import com.example.goodpixel.ui.notilog.NotiLogActivity

/**
 * Galaxy風「エッジパネル（Edge Panel）」
 * 画面端にうっすら表示されるタブを引き出すと、アプリランチャー＆機能ショートカットがスライド展開。
 * ゲーム起動中は自動で完全非表示になる。
 */
@SuppressLint("ViewConstructor")
class EdgePanelOverlayView(
    context: Context,
    private val onDismiss: () -> Unit,
    private val onTriggerSmartCapture: () -> Unit,
    private val onTriggerQuickTools: () -> Unit
) : FrameLayout(context) {

    private val panelWidth = (context.resources.displayMetrics.widthPixels * 0.32f).toInt()
    private val container = FrameLayout(context)
    private val panelLayout = LinearLayout(context)

    init {
        // 背景の半透明暗転（外側タップで収納）
        setBackgroundColor(Color.argb(100, 0, 0, 0))
        setOnClickListener { closePanel() }

        // スライドインする右側パネル
        panelLayout.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val bg = GradientDrawable().apply {
                setColor(Color.argb(240, 26, 28, 34))
                cornerRadii = floatArrayOf(40f, 40f, 0f, 0f, 0f, 0f, 40f, 40f) // 左側だけ角丸
            }
            background = bg
            setPadding(16, 48, 16, 48)
            elevation = 24f
            setOnClickListener { /* consume */ }
        }

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(panelLayout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val scrollParams = LayoutParams(panelWidth, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.END
        }
        addView(scroll, scrollParams)

        // 1. パネルヘッダー
        val header = TextView(context).apply {
            text = "Apps"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        panelLayout.addView(header)

        // 2. 特殊ショートカット（NotiStar, スマート選択, クイックツール）
        panelLayout.addView(createShortcutItem("📜", "NotiStar") {
            closePanel()
            val intent = Intent(context, NotiLogActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        })

        panelLayout.addView(createShortcutItem("✂️", "スマート選択") {
            closePanel()
            onTriggerSmartCapture()
        })

        panelLayout.addView(createShortcutItem("⚡", "クイックツール") {
            closePanel()
            onTriggerQuickTools()
        })

        // 区切り線
        val divider = View(context).apply {
            setBackgroundColor(Color.argb(40, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(16, 24, 16, 24)
            }
        }
        panelLayout.addView(divider)

        // 3. インストール済み主要アプリ一覧（ランチャー）
        loadLauncherApps()

        // アニメーションでスライドイン（右端から登場）
        translationX = panelWidth.toFloat()
        animate()
            .translationX(0f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun closePanel() {
        animate()
            .translationX(panelWidth.toFloat())
            .setDuration(180)
            .withEndAction { onDismiss() }
            .start()
    }

    private fun createShortcutItem(iconEmoji: String, label: String, onClick: () -> Unit): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(12, 16, 12, 16)
            val bg = GradientDrawable().apply {
                setColor(Color.argb(70, 60, 64, 75))
                cornerRadius = 24f
            }
            background = bg

            val iconView = TextView(context).apply {
                text = iconEmoji
                textSize = 24f
                gravity = Gravity.CENTER
            }
            val labelView = TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 10f
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, 6, 0, 0)
            }

            addView(iconView)
            addView(labelView)
            setOnClickListener { onClick() }

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(8, 6, 8, 6)
            }
        }
    }

    private fun loadLauncherApps() {
        val pm = context.packageManager
        val selectedPackages = EdgeAppManager.getSelectedAppPackages(context)

        for (pkg in selectedPackages) {
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val appLabel = pm.getApplicationLabel(appInfo).toString()
                val icon = pm.getApplicationIcon(appInfo)

                val appView = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(8, 12, 8, 12)

                    val img = ImageView(context).apply {
                        setImageDrawable(icon)
                        layoutParams = LinearLayout.LayoutParams(96, 96)
                    }
                    val txt = TextView(context).apply {
                        text = appLabel
                        setTextColor(Color.LTGRAY)
                        textSize = 10f
                        gravity = Gravity.CENTER
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, 4, 0, 0)
                    }

                    addView(img)
                    addView(txt)

                    setOnClickListener {
                        closePanel()
                        val launchIntent = pm.getLaunchIntentForPackage(pkg)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(launchIntent)
                        }
                    }

                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(8, 4, 8, 4)
                    }
                }
                panelLayout.addView(appView)
            } catch (e: Exception) {
                // アンインストールされたアプリなどは無視
            }
        }

        // 最下部に「✏️ アプリ編集」ボタンを配置
        val editButton = createShortcutItem("✏️", "アプリ編集") {
            closePanel()
            val intent = Intent(context, EdgeAppPickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
        panelLayout.addView(editButton)
    }
}
