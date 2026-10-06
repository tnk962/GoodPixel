package com.example.goodpixel.ui.notilog

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.NotificationCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.goodpixel.data.notification.NotificationDbHelper
import com.example.goodpixel.data.notification.NotificationItem
import com.example.goodpixel.service.GoodNotificationListenerService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotiLogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                NotiLogScreen(
                    onBack = { finish() },
                    showBackButton = true,
                    onOpenSettings = {
                        val intent = Intent(this, com.example.goodpixel.MainActivity::class.java).apply {
                            putExtra("open_tab", "settings")
                        }
                        startActivity(intent)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotiLogScreen(
    onBack: () -> Unit,
    showBackButton: Boolean = true,
    onOpenSettings: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val dbHelper = remember { NotificationDbHelper.getInstance(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var searchQuery by remember { mutableStateOf("") }
    var notifications by remember { mutableStateOf<List<NotificationItem>>(emptyList()) }
    var isListenerEnabled by remember { mutableStateOf(checkNotificationListenerPermission(context)) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showRestrictedHelpDialog by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences("goodpixel_prefs", Context.MODE_PRIVATE) }
    var showTestNotificationBtn by remember {
        mutableStateOf(prefs.getBoolean("pref_show_test_notification_btn", false))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isListenerEnabled = checkNotificationListenerPermission(context)
                showTestNotificationBtn = prefs.getBoolean("pref_show_test_notification_btn", false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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

    fun deleteItemWithUndo(item: NotificationItem) {
        scope.launch {
            dbHelper.deleteById(item.id)
            val result = snackbarHostState.showSnackbar(
                message = "「${item.appName}」の通知を削除しました",
                actionLabel = "元に戻す",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                dbHelper.insertNotification(
                    pkg = item.packageName,
                    appName = item.appName,
                    title = item.title,
                    text = item.text,
                    postTime = item.postTime,
                    key = item.notificationKey,
                    uri = item.uri
                )
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("通知ログ", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onBack) {
                            Text("←", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                actions = {
                    if (showTestNotificationBtn) {
                        IconButton(onClick = { sendTestNotification(context) }) {
                            Text("🔔", fontSize = 18.sp)
                        }
                    }
                    if (notifications.isNotEmpty()) {
                        IconButton(onClick = { showDeleteConfirmDialog = true }) {
                            Text("🗑️", fontSize = 18.sp)
                        }
                    }
                    if (onOpenSettings != null) {
                        IconButton(onClick = onOpenSettings) {
                            Text("⚙️", fontSize = 18.sp)
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
                            "通知ログ機能で過去の通知を収集・保存するには、通知アクセス権限を許可してください。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    context.startActivity(intent)
                                }
                            ) {
                                Text("権限を設定する")
                            }
                            TextButton(
                                onClick = { showRestrictedHelpDialog = true }
                            ) {
                                Text("スイッチが押せない場合", fontSize = 12.sp)
                            }
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

            // 操作ヒント
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "💡 左右スワイプで削除 / 長押しでロック（消去保護）",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
                val lockedTotal = remember(notifications) { notifications.count { it.isLocked } }
                if (lockedTotal > 0) {
                    Text(
                        text = "🔒 ${lockedTotal}件保護中",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

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
                        SwipeableNotificationCard(
                            item = item,
                            onDeleteWithUndo = { deletedItem ->
                                deleteItemWithUndo(deletedItem)
                            },
                            onLockToggle = { lockItem ->
                                scope.launch {
                                    val newLocked = dbHelper.toggleLock(lockItem.id, lockItem.isLocked)
                                    val msg = if (newLocked) {
                                        "🔒 通知をロックしました（削除から保護）"
                                    } else {
                                        "🔓 通知のロックを解除しました"
                                    }
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    snackbarHostState.showSnackbar(msg)
                                }
                            },
                            onClick = { clickedItem ->
                                launchNotificationTarget(context, clickedItem)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        val lockedCount = remember(notifications) { notifications.count { it.isLocked } }

        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("履歴の全消去") },
            text = {
                if (lockedCount > 0) {
                    Text("ロックされていない通知履歴をすべて削除しますか？\n\n※ 🔒 ロック中の通知（${lockedCount}件）は保護され、残ります。")
                } else {
                    Text("保存されているすべての通知履歴を削除しますか？\n（消したくない通知は長押しでロックして保護できます）")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            dbHelper.clearUnlocked()
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

    if (showRestrictedHelpDialog) {
        AlertDialog(
            onDismissRequest = { showRestrictedHelpDialog = false },
            title = {
                Text("設定がグレーアウトしている場合", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            },
            text = {
                Column {
                    Text(
                        "Android 13以降では、セキュリティ保護のためPlayストア外からインストールしたアプリの権限スイッチが一時的に保護（グレーアウト）される場合があります。\n\n" +
                        "【解除手順】\n" +
                        "1. 下の「アプリ情報画面を開く」をタップ\n" +
                        "2. 画面右上の「︙」（3点メニュー）をタップ\n" +
                        "3.「制限付き設定を許可」を選択し、生体認証またはPINで認証\n\n" +
                        "完了後、「通知アクセス」の設定画面でGoodPixelのスイッチをONにできるようになります。",
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestrictedHelpDialog = false
                        try {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent(Settings.ACTION_SETTINGS)
                            context.startActivity(intent)
                        }
                    }
                ) {
                    Text("アプリ情報画面を開く")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestrictedHelpDialog = false }) {
                    Text("閉じる")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeableNotificationCard(
    item: NotificationItem,
    onDeleteWithUndo: (NotificationItem) -> Unit,
    onLockToggle: (NotificationItem) -> Unit,
    onClick: (NotificationItem) -> Unit
) {
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    // 画面幅の 75% までしっかり指でスワイプしないと消去判定にならない（エッジ端判定）
    // 途中で指を戻したり、途中で指を離した場合は 100% 元の位置へ復帰する
    val dismissThresholdPx = screenWidthPx * 0.75f

    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var isDragging by remember { mutableStateOf(false) }
    var hasHapticTriggered by remember { mutableStateOf(false) }

    val canSwipe = !item.isLocked
    val currentOffset = offsetX.value
    val isPastThreshold = abs(currentOffset) >= dismissThresholdPx

    // しきい値（エッジ端）に達した瞬間に「コッ」と手に微振動を伝達
    LaunchedEffect(isPastThreshold) {
        if (isPastThreshold && isDragging && !hasHapticTriggered) {
            try {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            } catch (_: Exception) {}
            hasHapticTriggered = true
        } else if (!isPastThreshold) {
            hasHapticTriggered = false
        }
    }

    val draggableState = rememberDraggableState { delta ->
        if (canSwipe) {
            scope.launch {
                offsetX.snapTo(offsetX.value + delta)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
    ) {
        // 背景（スワイプ時のゴミ箱と赤色表示）
        if (abs(currentOffset) > 10f) {
            val progress = (abs(currentOffset) / dismissThresholdPx).coerceIn(0f, 1f)
            val bgColor = if (isPastThreshold) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = (0.25f + progress * 0.45f).coerceIn(0.25f, 0.7f))
            }
            val alignment = if (currentOffset > 0) Alignment.CenterStart else Alignment.CenterEnd

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(bgColor, RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment
            ) {
                Text(
                    text = "🗑️",
                    fontSize = if (isPastThreshold) 24.sp else 18.sp,
                    modifier = Modifier.alpha(if (isPastThreshold) 1.0f else 0.6f)
                )
            }
        }

        // 前面カード本体（ドラッグ可能・タップや長押しも完全透過）
        Box(
            modifier = Modifier
                .offset { IntOffset(currentOffset.roundToInt(), 0) }
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Horizontal,
                    enabled = canSwipe,
                    onDragStarted = {
                        isDragging = true
                        hasHapticTriggered = false
                    },
                    onDragStopped = {
                        isDragging = false
                        scope.launch {
                            // 指の位置が画面端（75%以上）に達している場合のみ消去を実行！
                            // 途中で指を戻した、または端まで行かずに離した場合は必ず元に戻る！
                            if (abs(offsetX.value) >= dismissThresholdPx) {
                                val targetX = if (offsetX.value > 0) screenWidthPx * 1.25f else -screenWidthPx * 1.25f
                                offsetX.animateTo(
                                    targetValue = targetX,
                                    animationSpec = tween(durationMillis = 200)
                                )
                                onDeleteWithUndo(item)
                            } else {
                                // 元の位置へスナップバック（復帰）
                                offsetX.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessLow
                                    )
                                )
                            }
                        }
                    }
                )
        ) {
            NotificationCard(
                item = item,
                onClick = { onClick(item) },
                onLongClick = { onLockToggle(item) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotificationCard(
    item: NotificationItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val view = LocalView.current
    val timeStr = remember(item.postTime) {
        val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
        sdf.format(Date(item.postTime))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    try {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    } catch (e: Exception) {
                        // ignore
                    }
                    onClick()
                },
                onLongClick = {
                    try {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    } catch (e: Exception) {
                        // ignore
                    }
                    onLongClick()
                }
            ),
        shape = RoundedCornerShape(12.dp),
        border = if (item.isLocked) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else null,
        // 背景を完全な不透明（ソリッド色）にして、背後のアイコンが透けて文字が読めなくなるのを完全に防ぐ
        colors = CardDefaults.cardColors(
            containerColor = if (item.isLocked) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = item.appName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "↗",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                    if (item.isLocked) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "🔒 ロック中",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
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

/**
 * 通知アイテムのタップ時に、該当アプリの特定画面（ツイート、チャット、通知タブ等）へスマートに遷移
 */
private fun launchNotificationTarget(context: Context, item: NotificationItem) {
    Log.d("NotiLog", "launchNotificationTarget: pkg=${item.packageName}, key=${item.notificationKey}, uri=${item.uri}")

    // 1. PendingIntentを検索（メモリキャッシュまたはactiveNotificationsから取得）
    val pi = GoodNotificationListenerService.findPendingIntent(
        key = item.notificationKey,
        pkg = item.packageName,
        postTime = item.postTime
    )

    if (pi != null) {
        try {
            // Android 14+ (API 34+) のバックグラウンドアクティビティ起動制限 (BAL) 回避
            val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions.makeBasic().apply {
                    pendingIntentBackgroundActivityStartMode =
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }.toBundle()
            } else {
                null
            }
            pi.send(context, 0, null, null, null, null, options)
            Log.d("NotiLog", "Successfully sent PendingIntent for ${item.packageName}")
            return
        } catch (e: Exception) {
            Log.w("NotiLog", "PendingIntent send failed, falling back to smart intent", e)
        }
    }

    // 2. ディープリンクURIがあればそれを起動（DBに保存されていたURI）
    if (!item.uri.isNullOrBlank()) {
        if (tryOpenUri(context, item.uri, item.packageName)) {
            Log.d("NotiLog", "Successfully opened item.uri: ${item.uri}")
            return
        }
    }

    // 3. 本文またはタイトルから抽出したURLを起動
    val urlInText = extractUrl(item.text) ?: extractUrl(item.title)
    if (urlInText != null) {
        if (tryOpenUri(context, urlInText, item.packageName)) {
            Log.d("NotiLog", "Successfully opened extracted URL: $urlInText")
            return
        }
    }

    // 4. アプリ固有のスマートディープリンク（Twitter, YouTube, Instagram等）
    if (launchAppSpecificDeepLink(context, item)) {
        Log.d("NotiLog", "Successfully opened app-specific deep link for ${item.packageName}")
        return
    }

    // 5. 最終フォールバック：通常のアプリアイコン起動
    launchApp(context, item.packageName)
}

private fun tryOpenUri(context: Context, uriString: String, packageName: String?): Boolean {
    try {
        val uri = Uri.parse(uriString)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!packageName.isNullOrBlank()) {
                setPackage(packageName)
            }
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            return true
        } else if (!packageName.isNullOrBlank()) {
            // パッケージ制限を外して再試行（ブラウザ等）
            intent.setPackage(null)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return true
            }
        }
    } catch (e: Exception) {
        Log.w("NotiLog", "tryOpenUri failed for $uriString", e)
    }
    return false
}

private fun launchAppSpecificDeepLink(context: Context, item: NotificationItem): Boolean {
    val pkg = item.packageName
    val isTwitter = pkg == "com.twitter.android" || pkg == "com.twitter.android.lite"

    if (isTwitter) {
        // Twitterの場合：
        // 1. タイトルまたは本文にユーザー名 (@username) があれば、そのユーザーのページを開く
        val mentionRegex = Regex("""@([A-Za-z0-9_]{1,15})""")
        val mention = mentionRegex.find(item.title)?.groupValues?.get(1)
            ?: mentionRegex.find(item.text)?.groupValues?.get(1)
        if (mention != null) {
            if (tryOpenUri(context, "twitter://user?screen_name=$mention", pkg)) {
                return true
            }
        }

        // 2. Twitterの通知タブを開く（該当のツイート・リプライ・いいね通知が最上位に表示される）
        if (tryOpenUri(context, "twitter://notifications", pkg)) {
            return true
        }
    }

    // YouTube
    if (pkg == "com.google.android.youtube") {
        if (tryOpenUri(context, "vnd.youtube://", pkg)) {
            return true
        }
    }

    // Instagram
    if (pkg == "com.instagram.android") {
        if (tryOpenUri(context, "instagram://notifications", pkg) ||
            tryOpenUri(context, "instagram://", pkg)) {
            return true
        }
    }

    // Threads
    if (pkg == "com.instagram.barcelona") {
        if (tryOpenUri(context, "barcelona://", pkg)) {
            return true
        }
    }

    return false
}

private fun extractUrl(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val urlRegex = Regex("""https?://[^\s<>"'{}|\\^`]+""")
    return urlRegex.find(text)?.value
}

private fun launchApp(context: Context, packageName: String) {
    try {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        }
    } catch (e: Exception) {
        Log.e("NotiLog", "Failed to launch app $packageName", e)
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

/**
 * 開発・動作確認用：Twitter/Xの投稿通知を模したテスト通知を送信
 */
private fun sendTestNotification(context: Context) {
    try {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "goodpixel_test_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "テスト通知", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val testUrl = "https://x.com/GoodPixel/status/123456789"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(testUrl)).apply {
            setPackage("com.twitter.android")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            context,
            (System.currentTimeMillis() % 10000).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val postTime = System.currentTimeMillis()
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(postTime))
        val testTitle = "X (Twitter)"
        val testText = "@PixelUserさんがあなたのポストをいいねしました: GoodPixel最高！ ($timeStr)"
        val testPkg = "com.twitter.android"
        val testAppName = "X (Twitter)"
        val notifKey = "test_key_$postTime"

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(testTitle)
            .setContentText(testText)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        notificationManager.notify((postTime % 100000).toInt(), notification)

        // PendingIntent をキャッシュに登録（タップ時の起動用）
        GoodNotificationListenerService.registerPendingIntent(notifKey, pi)

        // GoodPixel自身の通知はNotificationListenerServiceで除外されるため、
        // テスト通知はDBに直接登録してリアルタイムに通知ログ一覧へ反映させる
        CoroutineScope(Dispatchers.IO).launch {
            val db = NotificationDbHelper.getInstance(context)
            db.insertNotification(
                pkg = testPkg,
                appName = testAppName,
                title = testTitle,
                text = testText,
                postTime = postTime,
                key = notifKey,
                uri = testUrl
            )
        }
    } catch (e: Exception) {
        Log.e("NotiLog", "Failed to send test notification", e)
    }
}
