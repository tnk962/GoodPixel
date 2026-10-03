package com.example.goodpixel.data.notification

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

/**
 * NotiStar代替：通知履歴ローカルSQLiteデータベース
 */
class NotificationDbHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "goodpixel_notifications.db"
        private const val DATABASE_VERSION = 2

        private const val TABLE_NOTIFICATIONS = "notifications"
        private const val COL_ID = "id"
        private const val COL_PKG = "package_name"
        private const val COL_APP_NAME = "app_name"
        private const val COL_TITLE = "title"
        private const val COL_TEXT = "text"
        private const val COL_POST_TIME = "post_time"
        private const val COL_KEY = "notification_key"

        @Volatile
        private var INSTANCE: NotificationDbHelper? = null

        fun getInstance(context: Context): NotificationDbHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NotificationDbHelper(context).also { INSTANCE = it }
            }
        }

        private val _dataUpdates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val dataUpdates: SharedFlow<Unit> = _dataUpdates.asSharedFlow()
    }

    override fun onCreate(db: SQLiteDatabase) {
        // notification_keyのUNIQUE制約を撤廃（Twitter等の通知ID再利用による過去ログ上書きを防止）
        val createSql = """
            CREATE TABLE $TABLE_NOTIFICATIONS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_PKG TEXT NOT NULL,
                $COL_APP_NAME TEXT NOT NULL,
                $COL_TITLE TEXT,
                $COL_TEXT TEXT,
                $COL_POST_TIME INTEGER NOT NULL,
                $COL_KEY TEXT
            )
        """.trimIndent()
        db.execSQL(createSql)
        db.execSQL("CREATE INDEX idx_post_time ON $TABLE_NOTIFICATIONS ($COL_POST_TIME DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                // 既存データを保持したままUNIQUE制約を解除するテーブル移行
                db.execSQL("""
                    CREATE TABLE notifications_v2 (
                        $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                        $COL_PKG TEXT NOT NULL,
                        $COL_APP_NAME TEXT NOT NULL,
                        $COL_TITLE TEXT,
                        $COL_TEXT TEXT,
                        $COL_POST_TIME INTEGER NOT NULL,
                        $COL_KEY TEXT
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO notifications_v2 ($COL_ID, $COL_PKG, $COL_APP_NAME, $COL_TITLE, $COL_TEXT, $COL_POST_TIME, $COL_KEY)
                    SELECT $COL_ID, $COL_PKG, $COL_APP_NAME, $COL_TITLE, $COL_TEXT, $COL_POST_TIME, $COL_KEY FROM $TABLE_NOTIFICATIONS
                """.trimIndent())
                db.execSQL("DROP TABLE $TABLE_NOTIFICATIONS")
                db.execSQL("ALTER TABLE notifications_v2 RENAME TO $TABLE_NOTIFICATIONS")
                db.execSQL("CREATE INDEX idx_post_time ON $TABLE_NOTIFICATIONS ($COL_POST_TIME DESC)")
            } catch (e: Exception) {
                // 万一失敗した場合は再作成
                db.execSQL("DROP TABLE IF EXISTS $TABLE_NOTIFICATIONS")
                onCreate(db)
            }
        }
    }

    suspend fun insertNotification(
        pkg: String,
        appName: String,
        title: String,
        text: String,
        postTime: Long,
        key: String
    ): Long = withContext(Dispatchers.IO) {
        val db = writableDatabase

        // 直近の同一パッケージ＆同一キーの通知をチェック
        // タイトルも本文も完全に一致しており、かつ直近60秒以内の再描画・同一通知の再ポストであればスキップ
        val checkSql = """
            SELECT $COL_TITLE, $COL_TEXT, $COL_POST_TIME 
            FROM $TABLE_NOTIFICATIONS 
            WHERE $COL_PKG = ? AND $COL_KEY = ? 
            ORDER BY $COL_POST_TIME DESC LIMIT 1
        """.trimIndent()

        var isDuplicate = false
        try {
            db.rawQuery(checkSql, arrayOf(pkg, key)).use { cursor ->
                if (cursor.moveToFirst()) {
                    val lastTitle = cursor.getString(0) ?: ""
                    val lastText = cursor.getString(1) ?: ""
                    val lastTime = cursor.getLong(2)

                    if (lastTitle == title && lastText == text && (postTime - lastTime < 60_000L)) {
                        isDuplicate = true
                    }
                }
            }
        } catch (e: Exception) {
            // クエリ失敗時は安全のためそのまま挿入を継続
        }

        if (isDuplicate) {
            return@withContext -1L
        }

        val values = ContentValues().apply {
            put(COL_PKG, pkg)
            put(COL_APP_NAME, appName)
            put(COL_TITLE, title)
            put(COL_TEXT, text)
            put(COL_POST_TIME, postTime)
            put(COL_KEY, key)
        }

        // CONFLICT_REPLACE ではなく、通常の insert で確実に新レコードとして蓄積
        val id = db.insert(TABLE_NOTIFICATIONS, null, values)
        _dataUpdates.tryEmit(Unit)
        id
    }

    suspend fun getRecentNotifications(limit: Int = 300): List<NotificationItem> =
        withContext(Dispatchers.IO) {
            val list = mutableListOf<NotificationItem>()
            val query = "SELECT * FROM $TABLE_NOTIFICATIONS ORDER BY $COL_POST_TIME DESC LIMIT $limit"
            readableDatabase.rawQuery(query, null).use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(COL_ID)
                val pkgIdx = cursor.getColumnIndexOrThrow(COL_PKG)
                val appIdx = cursor.getColumnIndexOrThrow(COL_APP_NAME)
                val titleIdx = cursor.getColumnIndexOrThrow(COL_TITLE)
                val textIdx = cursor.getColumnIndexOrThrow(COL_TEXT)
                val timeIdx = cursor.getColumnIndexOrThrow(COL_POST_TIME)
                val keyIdx = cursor.getColumnIndexOrThrow(COL_KEY)

                while (cursor.moveToNext()) {
                    list.add(
                        NotificationItem(
                            id = cursor.getLong(idIdx),
                            packageName = cursor.getString(pkgIdx),
                            appName = cursor.getString(appIdx),
                            title = cursor.getString(titleIdx) ?: "",
                            text = cursor.getString(textIdx) ?: "",
                            postTime = cursor.getLong(timeIdx),
                            notificationKey = cursor.getString(keyIdx)
                        )
                    )
                }
            }
            list
        }

    suspend fun searchNotifications(keyword: String, limit: Int = 200): List<NotificationItem> =
        withContext(Dispatchers.IO) {
            if (keyword.isBlank()) return@withContext getRecentNotifications(limit)

            val list = mutableListOf<NotificationItem>()
            val wildCard = "%$keyword%"
            val query = """
                SELECT * FROM $TABLE_NOTIFICATIONS
                WHERE $COL_TITLE LIKE ? OR $COL_TEXT LIKE ? OR $COL_APP_NAME LIKE ?
                ORDER BY $COL_POST_TIME DESC LIMIT $limit
            """.trimIndent()

            readableDatabase.rawQuery(query, arrayOf(wildCard, wildCard, wildCard)).use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(COL_ID)
                val pkgIdx = cursor.getColumnIndexOrThrow(COL_PKG)
                val appIdx = cursor.getColumnIndexOrThrow(COL_APP_NAME)
                val titleIdx = cursor.getColumnIndexOrThrow(COL_TITLE)
                val textIdx = cursor.getColumnIndexOrThrow(COL_TEXT)
                val timeIdx = cursor.getColumnIndexOrThrow(COL_POST_TIME)
                val keyIdx = cursor.getColumnIndexOrThrow(COL_KEY)

                while (cursor.moveToNext()) {
                    list.add(
                        NotificationItem(
                            id = cursor.getLong(idIdx),
                            packageName = cursor.getString(pkgIdx),
                            appName = cursor.getString(appIdx),
                            title = cursor.getString(titleIdx) ?: "",
                            text = cursor.getString(textIdx) ?: "",
                            postTime = cursor.getLong(timeIdx),
                            notificationKey = cursor.getString(keyIdx)
                        )
                    )
                }
            }
            list
        }

    suspend fun deleteById(id: Long) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_NOTIFICATIONS, "$COL_ID = ?", arrayOf(id.toString()))
        _dataUpdates.tryEmit(Unit)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_NOTIFICATIONS, null, null)
        _dataUpdates.tryEmit(Unit)
    }
}
