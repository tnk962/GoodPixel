package com.example.goodpixel.data.notification

import android.app.PendingIntent

/**
 * NotiStar代替：完全時系列通知アイテム
 */
data class NotificationItem(
    val id: Long = 0,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val notificationKey: String,
    val pendingIntent: PendingIntent? = null
)
