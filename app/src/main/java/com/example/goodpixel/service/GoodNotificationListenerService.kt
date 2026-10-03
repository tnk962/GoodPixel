package com.example.goodpixel.service

import android.app.Notification
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.goodpixel.data.notification.NotificationDbHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * NotiStar代替：全アプリの通知を受信して時系列DBに保存する通知リスナーサービス
 */
class GoodNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "GoodNotification"
        var instance: GoodNotificationListenerService? = null
            private set

        // メモリ上で通知キーとPendingIntentを紐付け（タップ時に通知先を直接開く）
        private val pendingIntentMap = ConcurrentHashMap<String, PendingIntent>()

        fun getPendingIntent(key: String): PendingIntent? = pendingIntentMap[key]
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dbHelper: NotificationDbHelper

    override fun onCreate() {
        super.onCreate()
        instance = this
        dbHelper = NotificationDbHelper.getInstance(this)
        Log.i(TAG, "GoodNotificationListenerService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        pendingIntentMap.clear()
        Log.i(TAG, "GoodNotificationListenerService destroyed")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        // 自身のアプリ通知やシステムUIの常駐音量バー等のノイズを除外
        if (pkg == packageName) return

        // 常駐通知（音楽再生バー、ナビ案内中、ダウンロード進捗など）は履歴から除外
        if (sbn.isOngoing) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""

        // タイトルも本文も空の無効通知は保存しない
        if (title.isBlank() && text.isBlank()) return

        // アプリ表示名を取得
        val appName = try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }

        val postTime = sbn.postTime
        val key = sbn.key

        // PendingIntentをキャッシュ
        notification.contentIntent?.let {
            pendingIntentMap[key] = it
        }

        serviceScope.launch {
            try {
                dbHelper.insertNotification(
                    pkg = pkg,
                    appName = appName,
                    title = title,
                    text = text,
                    postTime = postTime,
                    key = key
                )
                Log.d(TAG, "Notification saved: [$appName] $title: $text")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save notification", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // NotiStar仕様：OS側で通知が消去されても、NotiStarログは消さずに履歴として保持する！
    }
}
