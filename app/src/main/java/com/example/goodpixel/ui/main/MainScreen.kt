package com.example.goodpixel.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.goodpixel.data.update.AppUpdateManager
import com.example.goodpixel.data.update.ReleaseInfo
import com.example.goodpixel.service.GoodAccessibilityService
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val isAccessibilityEnabled by GoodAccessibilityService.isServiceEnabled.collectAsState()
    var isOverlayPermissionGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isNotificationListenerGranted by remember { mutableStateOf(checkNotificationListenerPermission(context)) }
    var isDebugOverlayVisible by remember { mutableStateOf(GoodAccessibilityService.isDebugOverlayEnabled(context)) }
    var isVibrationOn by remember { mutableStateOf(GoodAccessibilityService.isVibrationEnabled(context)) }
    var isOhoEnabled by remember { mutableStateOf(GoodAccessibilityService.isOhoEnabled(context)) }
    var isHideNavBarEnabled by remember { mutableStateOf(GoodAccessibilityService.isHideNavBarEnabled(context)) }
    val prefs = remember { context.getSharedPreferences("goodpixel_prefs", Context.MODE_PRIVATE) }
    var isTestNotificationEnabled by remember {
        mutableStateOf(prefs.getBoolean("pref_show_test_notification_btn", false))
    }

    // アップデート管理用の状態
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<ReleaseInfo?>(null) }
    var updateStatusMessage by remember { mutableStateOf<String?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(0L) }
    var downloadedApkFile by remember { mutableStateOf<File?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    // 画面復帰時に権限状態を再チェック
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isOverlayPermissionGranted = Settings.canDrawOverlays(context)
                isNotificationListenerGranted = checkNotificationListenerPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (onBack != null) "⚙️ GoodPixel 設定" else "GoodPixel (All-in-One)",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Text("←", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 権限ステータスカード
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("システム権限・動作状況", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))

                    // ユーザー補助
                    StatusRow(
                        title = "① ユーザー補助サービス (OHO+動作)",
                        isEnabled = isAccessibilityEnabled,
                        onOpenSettings = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // オーバーレイ表示
                    StatusRow(
                        title = "② 重ねて表示 (画面端タッチ受付)",
                        isEnabled = isOverlayPermissionGranted,
                        onOpenSettings = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 通知アクセス権限（通知ログ用）
                    StatusRow(
                        title = "③ 通知アクセス (通知ログ収集)",
                        isEnabled = isNotificationListenerGranted,
                        onOpenSettings = {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            context.startActivity(intent)
                        }
                    )
                }
            }

            // 2. 操作・設定カード（バイブレーション ＆ 可視化）
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("操作設定", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))

                    // ワンハンドオペレーション+ (OHO+) ON/OFF
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("ワンハンドオペレーション+ (OHO+)", fontWeight = FontWeight.Medium)
                            Text(
                                "画面端ジェスチャー操作（戻る・スクショ・クイックツール等）",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isOhoEnabled,
                            onCheckedChange = { checked ->
                                isOhoEnabled = checked
                                GoodAccessibilityService.setOhoEnabled(context, checked)
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // 画面下の切り替えバーを非表示 ON/OFF
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("画面下のバー（切り替えピル）を非表示", fontWeight = FontWeight.Medium)
                            Text(
                                "最下部の白い横棒を隠します（下からのスワイプ操作は維持されます）",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isHideNavBarEnabled,
                            onCheckedChange = { checked ->
                                isHideNavBarEnabled = checked
                                GoodAccessibilityService.setHideNavBarEnabled(context, checked)
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // バイブレーション ON/OFF
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("触覚フィードバック（バイブレーション）", fontWeight = FontWeight.Medium)
                            Text(
                                "タッチ時・ジェスチャー認識時の微振動",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isVibrationOn,
                            onCheckedChange = { checked ->
                                isVibrationOn = checked
                                GoodAccessibilityService.setVibrationEnabled(context, checked)
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // 青色ハンドル可視化
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("青色ハンドルを表示（位置確認用）", fontWeight = FontWeight.Medium)
                            Text(
                                "画面両端のタッチ反応エリアを青く表示します",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isDebugOverlayVisible,
                            onCheckedChange = { checked ->
                                isDebugOverlayVisible = checked
                                GoodAccessibilityService.setDebugOverlayEnabled(context, checked)
                            }
                        )
                    }
                }
            }

            // 3. 現在のジェスチャー割り当て一覧
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("ジェスチャー割り当て (左右共通)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    GestureItem("水平スワイプ (→ / ←)", "戻る (Back)")
                    GestureItem("斜め上スワイプ (↗ / ↖)", "全画面スクリーンショット")
                    GestureItem("斜め下スワイプ (↘ / ↙)", "スマート選択 (範囲切り抜き保存)")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    GestureItem("水平長押しスワイプ", "クイックツール")
                    GestureItem("斜め上長押しスワイプ", "（未設定）")
                    GestureItem("斜め下長押しスワイプ", "画面自動回転 ON/OFF")
                }
            }

            // 4. 通知ログ & 新機能ランチャーカード
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("📜 通知ログ機能", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "通知をまとめず完全時系列で保存し、アプリ名や本文からいつでも全文検索できます。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            if (onBack != null) {
                                onBack()
                            } else {
                                val intent = Intent(context, com.example.goodpixel.ui.notilog.NotiLogActivity::class.java)
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📜 通知ログ・履歴を開く")
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                    // デバッグ用テスト通知ボタン表示トグル
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("テスト通知ボタンを表示 (デバッグ用)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "通知ログ画面右上のベルマーク（🔔）を表示します",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isTestNotificationEnabled,
                            onCheckedChange = { checked ->
                                isTestNotificationEnabled = checked
                                prefs.edit().putBoolean("pref_show_test_notification_btn", checked).apply()
                            }
                        )
                    }
                }
            }

            // 5. エッジパネル & クイックツール案内カード
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🚀 エッジパネル & クイックツール", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "・エッジパネル: 画面右端上部の白い半透明タブを内側に引くと、アプリランチャーが展開します（ゲーム中は自動非表示）。\n" +
                        "・クイックツール: 画面端から「水平長押しスワイプ」で、メディア操作・Wi-Fi・Bluetooth・全ミュート・画面録画などのアイコン操作パネルがポップアップします。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { GoodAccessibilityService.instance?.showEdgePanelOverlay() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("エッジ展開")
                        }
                        OutlinedButton(
                            onClick = { GoodAccessibilityService.instance?.showQuickToolsOverlay() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("ツール展開")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val intent = Intent(context, com.example.goodpixel.ui.edge.EdgeAppPickerActivity::class.java)
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("✏️ エッジパネルのアプリを編集")
                    }
                }
            }

            // 6. アクション即時テストボタン
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("アクション即時テスト", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { GoodAccessibilityService.performBackAction() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("戻る")
                        }
                        Button(
                            onClick = { GoodAccessibilityService.takeScreenshotAction() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("スクショ")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { GoodAccessibilityService.toggleAutoRotation(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("画面自動回転の切り替え")
                    }
                }
            }

            // 7. アプリ情報 & アップデートカード
            val currentVersion = remember { AppUpdateManager.getCurrentVersion(context) }
            val currentVersionCode = remember { AppUpdateManager.getCurrentVersionCode(context) }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("📦 アプリ情報 & アップデート", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "GoodPixel v$currentVersion (Build $currentVersionCode)",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (isCheckingUpdate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            OutlinedButton(
                                onClick = {
                                    isCheckingUpdate = true
                                    updateStatusMessage = null
                                    coroutineScope.launch {
                                        val result = AppUpdateManager.checkLatestRelease(context)
                                        isCheckingUpdate = false
                                        result.onSuccess { info ->
                                            updateInfo = info
                                            showUpdateDialog = true
                                        }.onFailure { e ->
                                            updateStatusMessage = "確認に失敗しました: ${e.localizedMessage ?: "通信エラー"}"
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("🔄 更新確認", fontSize = 12.sp)
                            }
                        }
                    }

                    if (updateStatusMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = updateStatusMessage!!,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    if (downloadedApkFile != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "✅ 更新APKの準備完了",
                                fontSize = 12.sp,
                                color = Color(0xFF2E7D32),
                                fontWeight = FontWeight.Bold
                            )
                            Button(
                                onClick = {
                                    if (!AppUpdateManager.canRequestPackageInstalls(context)) {
                                        showPermissionDialog = true
                                    } else {
                                        AppUpdateManager.installApk(context, downloadedApkFile!!)
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("インストール", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // アップデート詳細・確認ダイアログ
    if (showUpdateDialog && updateInfo != null) {
        val info = updateInfo!!
        AlertDialog(
            onDismissRequest = {
                if (!isDownloading) showUpdateDialog = false
            },
            title = {
                Text(
                    text = if (info.isNewer) "🎉 アップデートがあります" else "✅ 最新バージョンです",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "最新版: ${info.tagName} (現在: v${AppUpdateManager.getCurrentVersion(context)})",
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                    if (info.apkSizeBytes > 0) {
                        val sizeMb = String.format("%.1f", info.apkSizeBytes / (1024.0 * 1024.0))
                        Text(
                            text = "ファイルサイズ: ${sizeMb} MB",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (info.body.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("更新内容:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = info.body,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }

                    if (isDownloading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "ダウンロード中... ($downloadProgress%)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        LinearProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        val dlMb = String.format("%.1f", downloadedBytes / (1024.0 * 1024.0))
                        val totMb = String.format("%.1f", totalBytes / (1024.0 * 1024.0))
                        Text(
                            text = "$dlMb MB / $totMb MB",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (downloadedApkFile != null && !isDownloading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "✅ ダウンロード完了！「インストール」を押してください。",
                            fontSize = 13.sp,
                            color = Color(0xFF2E7D32),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            confirmButton = {
                if (downloadedApkFile != null) {
                    Button(
                        onClick = {
                            if (!AppUpdateManager.canRequestPackageInstalls(context)) {
                                showPermissionDialog = true
                            } else {
                                AppUpdateManager.installApk(context, downloadedApkFile!!)
                            }
                        }
                    ) {
                        Text("📦 インストール")
                    }
                } else if (!isDownloading) {
                    Button(
                        onClick = {
                            if (!AppUpdateManager.canRequestPackageInstalls(context)) {
                                showPermissionDialog = true
                                return@Button
                            }
                            isDownloading = true
                            downloadProgress = 0
                            downloadedApkFile = null
                            coroutineScope.launch {
                                val dlResult = AppUpdateManager.downloadApk(context, info) { percent, dl, tot ->
                                    downloadProgress = percent
                                    downloadedBytes = dl
                                    totalBytes = tot
                                }
                                isDownloading = false
                                dlResult.onSuccess { file ->
                                    downloadedApkFile = file
                                    if (AppUpdateManager.canRequestPackageInstalls(context)) {
                                        AppUpdateManager.installApk(context, file)
                                    } else {
                                        showPermissionDialog = true
                                    }
                                }.onFailure { err ->
                                    updateStatusMessage = "ダウンロード失敗: ${err.localizedMessage}"
                                }
                            }
                        }
                    ) {
                        Text(if (info.isNewer) "⬇️ 今すぐ更新" else "再ダウンロード")
                    }
                }
            },
            dismissButton = {
                if (!isDownloading) {
                    TextButton(onClick = { showUpdateDialog = false }) {
                        Text("閉じる")
                    }
                }
            }
        )
    }

    // インストール権限リクエストダイアログ
    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("⚠️ 不明なアプリのインストール許可") },
            text = {
                Text(
                    "GoodPixelをアプリ内から直接アップデートするには、「提供元不明のアプリのインストール」権限を許可する必要があります。\n\n" +
                    "設定画面が開いたら「この提供元のアプリを許可」をONにしてください。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionDialog = false
                        AppUpdateManager.openInstallPermissionSetting(context)
                    }
                ) {
                    Text("設定を開く")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }
}

private fun checkNotificationListenerPermission(context: Context): Boolean {
    val enabledListeners = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    ) ?: return false
    val myComponent = "${context.packageName}/${com.example.goodpixel.service.GoodNotificationListenerService::class.java.name}"
    return enabledListeners.contains(myComponent)
}

@Composable
fun StatusRow(
    title: String,
    isEnabled: Boolean,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                if (isEnabled) "● 有効 (正常稼働中)" else "▲ 未許可 (タップして許可)",
                color = if (isEnabled) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        OutlinedButton(
            onClick = onOpenSettings,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(if (isEnabled) "設定確認" else "許可する", fontSize = 12.sp)
        }
    }
}

@Composable
fun GestureItem(trigger: String, action: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(trigger, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(action, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
