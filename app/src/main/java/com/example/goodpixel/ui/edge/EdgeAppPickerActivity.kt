package com.example.goodpixel.ui.edge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.goodpixel.data.edge.AppEntry
import com.example.goodpixel.data.edge.EdgeAppManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EdgeAppPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                EdgeAppPickerScreen(
                    onFinish = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EdgeAppPickerScreen(onFinish: () -> Unit) {
    val context = LocalContext.current

    var allApps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var selectedPackages by remember { mutableStateOf<List<String>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    // アプリ一覧と選択中アプリの読み込み
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val installed = EdgeAppManager.getInstalledLaunchableApps(context)
            val selected = EdgeAppManager.getSelectedAppPackages(context)
            allApps = installed
            selectedPackages = selected
            isLoading = false
        }
    }

    val filteredApps = remember(allApps, searchQuery) {
        if (searchQuery.isBlank()) allApps
        else allApps.filter { it.label.contains(searchQuery, ignoreCase = true) }
    }

    val maxApps = 12

    fun toggleApp(pkg: String) {
        if (selectedPackages.contains(pkg)) {
            selectedPackages = selectedPackages.filter { it != pkg }
        } else {
            if (selectedPackages.size >= maxApps) {
                Toast.makeText(context, "登録できるアプリは最大${maxApps}個までです", Toast.LENGTH_SHORT).show()
                return
            }
            selectedPackages = selectedPackages + pkg
        }
    }

    fun saveAndExit() {
        EdgeAppManager.saveSelectedAppPackages(context, selectedPackages)
        Toast.makeText(context, "エッジパネルのアプリを更新しました", Toast.LENGTH_SHORT).show()
        onFinish()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("エッジパネルのアプリ編集") },
                navigationIcon = {
                    IconButton(onClick = onFinish) {
                        Text("←", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    Button(
                        onClick = { saveAndExit() },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("完了")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // 選択済みアプリのプレビューエリア
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "選択中のアプリ (${selectedPackages.size} / $maxApps)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            if (selectedPackages.isNotEmpty()) {
                                TextButton(onClick = { selectedPackages = emptyList() }) {
                                    Text("全解除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }

                        if (selectedPackages.isEmpty()) {
                            Text(
                                "下の一覧からエッジパネルに配置したいアプリをタップしてください",
                                fontSize = 12.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        } else {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                items(selectedPackages) { pkg ->
                                    val entry = allApps.firstOrNull { it.packageName == pkg }
                                    if (entry != null) {
                                        SelectedAppChip(
                                            entry = entry,
                                            onRemove = { toggleApp(pkg) }
                                        )
                                    }
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
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    placeholder = { Text("アプリを検索...") },
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

                // アプリ一覧
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val isSelected = selectedPackages.contains(app.packageName)
                        AppListItem(
                            entry = app,
                            isSelected = isSelected,
                            onClick = { toggleApp(app.packageName) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SelectedAppChip(entry: AppEntry, onRemove: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.clickable { onRemove() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            entry.icon?.let { drawable ->
                val bitmap = remember(drawable) { drawableToBitmap(drawable) }
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                entry.label,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("✕", fontSize = 11.sp, color = Color.Gray)
        }
    }
}

@Composable
fun AppListItem(
    entry: AppEntry,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            entry.icon?.let { drawable ->
                val bitmap = remember(drawable) { drawableToBitmap(drawable) }
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.label, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text(
                    entry.packageName,
                    fontSize = 11.sp,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() }
            )
        }
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap
    }
    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 48
    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 48
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}
