package com.example.goodpixel.service

import android.app.Notification
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.goodpixel.data.notification.NotificationDbHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * NotiStar代替：全アプリの通知を受信して時系列DBに保存する通知リスナーサービス
 */
class GoodNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "GoodNotification"
        var instance: GoodNotificationListenerService? = null
            private set

        // 最大1000件保持するLRUキャッシュ（スレッドセーフ）
        private const val MAX_PENDING_INTENTS = 1000
        private val pendingIntentMap = Collections.synchronizedMap(
            object : LinkedHashMap<String, PendingIntent>(MAX_PENDING_INTENTS, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PendingIntent>?): Boolean {
                    return size > MAX_PENDING_INTENTS
                }
            }
        )

        fun getPendingIntent(key: String): PendingIntent? = pendingIntentMap[key]

        fun registerPendingIntent(key: String, pi: PendingIntent) {
            pendingIntentMap[key] = pi
        }

        /**
         * 通知キー、パッケージ名、投稿時間から最も適切な生きたPendingIntentを検索
         */
        fun findPendingIntent(key: String, pkg: String, postTime: Long): PendingIntent? {
            // 1. キャッシュから検索
            pendingIntentMap[key]?.let { return it }

            // 2. 現在システム上でアクティブな通知からリアルタイム検索
            try {
                val active = instance?.activeNotifications ?: return null
                // キー完全一致
                val matchByKey = active.firstOrNull { it.key == key }
                if (matchByKey?.notification?.contentIntent != null) {
                    val pi = matchByKey.notification.contentIntent
                    pendingIntentMap[key] = pi
                    return pi
                }
                // 同一パッケージかつ投稿時間が近い通知（15秒以内）
                val matchByPkg = active.firstOrNull {
                    it.packageName == pkg && Math.abs(it.postTime - postTime) < 15_000L
                }
                if (matchByPkg?.notification?.contentIntent != null) {
                    val pi = matchByPkg.notification.contentIntent
                    pendingIntentMap[key] = pi
                    return pi
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error looking up active notifications", e)
            }

            return null
        }
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
        // サービス再生成時にもキャッシュを破棄しないよう、clear()は呼ばない
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
        val extras = notification.extras ?: Bundle()

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

        // ディープリンクURI / URLの抽出
        val deepLinkUri = extractDeepLink(pkg, extras, title, text)

        serviceScope.launch {
            try {
                dbHelper.insertNotification(
                    pkg = pkg,
                    appName = appName,
                    title = title,
                    text = text,
                    postTime = postTime,
                    key = key,
                    uri = deepLinkUri
                )
                Log.d(TAG, "Notification saved: [$appName] $title: $text (uri: $deepLinkUri)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save notification", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // NotiStar仕様：OS側で通知が消去されても、NotiStarログは消さずに履歴として保持する！
    }

    /**
     * 通知のextras、本文、タイトルからディープリンクや投稿URLを抽出
     */
    private fun extractDeepLink(
        pkg: String,
        extras: Bundle,
        title: String,
        text: String
    ): String? {
        val isTwitter = pkg == "com.twitter.android" || pkg == "com.twitter.android.lite"

        // 1. よく使われるディープリンク用extrasキーをチェック
        val candidateKeys = listOf(
            "uri", "url", "link", "target_url", "deeplink", "deep_link",
            "android.dataUrl", "notification_url", "dest_url"
        )
        for (k in candidateKeys) {
            val v = extras.getString(k)
            if (!v.isNullOrBlank() && (v.startsWith("http://") || v.startsWith("https://") || v.contains("://"))) {
                return v
            }
        }

        // 2. Twitter固有のextras解析
        if (isTwitter) {
            val tweetId = extras.getString("tweet_id")
                ?: extras.getLong("tweet_id", -1L).takeIf { it > 0 }?.toString()
                ?: extras.getString("status_id")
                ?: extras.getLong("status_id", -1L).takeIf { it > 0 }?.toString()

            if (tweetId != null) {
                return "https://x.com/i/status/$tweetId"
            }

            // extrasの全キーからTwitter/X関連URLを探索
            for (k in extras.keySet()) {
                val str = try { extras.getString(k) } catch (e: Exception) { null }
                if (str != null) {
                    if (str.startsWith("twitter://") ||
                        str.startsWith("https://x.com/") ||
                        str.startsWith("https://twitter.com/") ||
                        str.startsWith("https://t.co/")
                    ) {
                        return str
                    }
                }
            }
        }

        // 3. タイトルまたは本文内のURL抽出 (https://... または http://...)
        val urlRegex = Regex("""https?://[^\s<>"'{}|\\^`]+""")
        val matchInText = urlRegex.find(text)?.value
        if (matchInText != null) return matchInText

        val matchInTitle = urlRegex.find(title)?.value
        if (matchInTitle != null) return matchInTitle

        // 4. Twitterのユーザーメンション (@username)
        if (isTwitter) {
            val mentionRegex = Regex("""@([A-Za-z0-9_]{1,15})""")
            val mention = mentionRegex.find(title)?.groupValues?.get(1)
                ?: mentionRegex.find(text)?.groupValues?.get(1)
            if (mention != null) {
                return "twitter://user?screen_name=$mention"
            }

            // Twitterの通知画面へフォールバック
            return "twitter://notifications"
        }

        return null
    }
}
