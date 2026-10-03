package com.example.goodpixel.ui.notilog

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.goodpixel.data.notification.NotificationDbHelper
import com.example.goodpixel.data.notification.NotificationItem
import com.example.goodpixel.service.GoodNotificationListenerService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotiLogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                NotiLogScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotiLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dbHelper = remember { NotificationDbHelper.getInstance(context) }
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var notifications by remember { mutableStateOf<List<NotificationItem>>(emptyList()) }
    var isListenerEnabled by remember { mutableStateOf(checkNotificationListenerPermission(context)) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    fun loadData() {
        scope.launch {
            notifications = if (searchQuery.isBlank()) {
                dbHelper.getRecentNotifications(300)
            } else {
                dbHelper.searchNotifications(searchQuery, 200)
            }
        }
    }

    LaunchedEffect(Unit) {
        loadData()
        // DB更新を監視して自動リロード
        NotificationDbHelper.dataUpdates.collect {
            loadData()
        }
    }

    LaunchedEffect(searchQuery) {
        loadData()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NotiStar 通知ログ") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    if (notifications.isNotEmpty()) {
                        IconButton(onClick = { showDeleteConfirmDialog = true }) {
                            Text("🗑️", fontSize = 18.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 権限未許可時の警告バナー
            if (!isListenerEnabled) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "⚠️ 通知アクセス権限が必要です",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "NotiStar機能で過去の通知を収集・保存するには、通知アクセス権限を許可してください。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                context.startActivity(intent)
                            }
                        ) {
                            Text("権限を設定する")
                        }
                    }
                }
            }

            // 検索バー
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("アプリ名、タイトル、本文で検索...") },
                leadingIcon = { Text("🔍", fontSize = 16.sp, modifier = Modifier.padding(start = 12.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Text("✕", fontSize = 16.sp, color = Color.Gray)
                        }
                    }
                },
                shape = RoundedCornerShape(24.dp),
                singleLine = true
            )

            // 通知一覧
            if (notifications.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (searchQuery.isEmpty()) "通知履歴はまだありません\n（新しい通知を受信すると自動保存されます）"
                        else "「$searchQuery」に一致する通知は見つかりませんでした",
                        color = Color.Gray,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(notifications, key = { it.id }) { item ->
                        NotificationCard(
                            item = item,
                            onClick = {
                                // PendingIntentがあれば発火、なければアプリを起動
                                val pi = GoodNotificationListenerService.getPendingIntent(item.notificationKey)
                                if (pi != null) {
                                    try {
                                        pi.send()
                                    } catch (e: Exception) {
                                        launchApp(context, item.packageName)
                                    }
                                } else {
                                    launchApp(context, item.packageName)
                                }
                            },
                            onLongClick = {
                                scope.launch {
                                    dbHelper.deleteById(item.id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("履歴の全消去") },
            text = { Text("保存されているすべての通知履歴を削除しますか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            dbHelper.clearAll()
                            showDeleteConfirmDialog = false
                        }
                    }
                ) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotificationCard(
    item: NotificationItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val timeStr = remember(item.postTime) {
        val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
        sdf.format(Date(item.postTime))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.appName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = timeStr,
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }

            if (item.title.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (item.text.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.text,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun launchApp(context: Context, packageName: String) {
    try {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        }
    } catch (e: Exception) {
        // ignore
    }
}

private fun checkNotificationListenerPermission(context: Context): Boolean {
    val enabledListeners = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    ) ?: return false
    val myComponent = "${context.packageName}/${GoodNotificationListenerService::class.java.name}"
    return enabledListeners.contains(myComponent)
}
