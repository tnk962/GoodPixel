package com.example.goodpixel.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val body: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val isNewer: Boolean
)

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val GITHUB_REPO = "tnk962/GoodPixel"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    /**
     * 現在インストールされているアプリのバージョン名を取得 (例: "1.0.0")
     */
    fun getCurrentVersion(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    /**
     * 現在インストールされているアプリのバージョンコードを取得
     */
    @Suppress("DEPRECATION")
    fun getCurrentVersionCode(context: Context): Long {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            1L
        }
    }

    /**
     * バージョン比較 (最新バージョンが現在バージョンより新しいか)
     */
    fun isNewerVersion(current: String, latest: String): Boolean {
        val cleanCurrent = current.removePrefix("v").trim()
        val cleanLatest = latest.removePrefix("v").trim()

        val currentParts = cleanCurrent.split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val latestParts = cleanLatest.split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }

        val maxLen = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until maxLen) {
            val c = currentParts.getOrElse(i) { 0 }
            val l = latestParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    /**
     * GitHub Releases APIから最新バージョン情報を取得
     */
    suspend fun checkLatestRelease(context: Context): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        try {
            val currentVer = getCurrentVersion(context)
            val url = URL(LATEST_RELEASE_URL)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "GoodPixel-App/$currentVer")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                connectTimeout = 15000
                readTimeout = 15000
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(Exception("GitHub APIエラー (HTTP $responseCode)"))
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseBody)

            val tagName = json.optString("tag_name", "")
            val name = json.optString("name", "")
            val body = json.optString("body", "")
            val assets = json.optJSONArray("assets") ?: return@withContext Result.failure(Exception("リリースにアセットが見つかりません"))

            var apkUrl: String? = null
            var apkName: String? = null
            var apkSize: Long = 0L

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val fileName = asset.optString("name", "")
                if (fileName.endsWith(".apk", ignoreCase = true)) {
                    apkName = fileName
                    apkUrl = asset.optString("browser_download_url", "")
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }

            if (apkUrl.isNullOrEmpty() || apkName == null) {
                return@withContext Result.failure(Exception("リリース内にAPKファイルが見つかりません"))
            }

            val latestVer = tagName.removePrefix("v")
            val isNewer = isNewerVersion(currentVer, latestVer)

            Result.success(
                ReleaseInfo(
                    tagName = tagName,
                    versionName = latestVer,
                    title = name,
                    body = body,
                    apkDownloadUrl = apkUrl,
                    apkFileName = apkName,
                    apkSizeBytes = apkSize,
                    isNewer = isNewer
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "checkLatestRelease failed", e)
            Result.failure(e)
        }
    }

    /**
     * 提供元不明のアプリのインストール許可があるかチェック
     */
    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * 提供元不明のアプリのインストール許可画面を開く
     */
    fun openInstallPermissionSetting(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * APKをダウンロード
     */
    suspend fun downloadApk(
        context: Context,
        releaseInfo: ReleaseInfo,
        onProgress: (progressPercent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }

            // 既存の古いAPKを掃除
            downloadDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".apk") || file.name.endsWith(".apk.tmp")) {
                    file.delete()
                }
            }

            val targetFile = File(downloadDir, releaseInfo.apkFileName)
            val tempFile = File(downloadDir, "${releaseInfo.apkFileName}.tmp")

            var currentUrl = releaseInfo.apkDownloadUrl
            var connection: HttpURLConnection
            var redirects = 0
            var inputStream: InputStream? = null

            while (redirects < 5) {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "GoodPixel-App/${getCurrentVersion(context)}")
                    connectTimeout = 20000
                    readTimeout = 30000
                }

                val status = connection.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == 307 || status == 308
                ) {
                    val location = connection.getHeaderField("Location")
                    if (!location.isNullOrEmpty()) {
                        currentUrl = location
                        redirects++
                        continue
                    }
                }

                if (status != HttpURLConnection.HTTP_OK) {
                    return@withContext Result.failure(Exception("ダウンロード失敗 (HTTP $status)"))
                }

                val totalLength = if (releaseInfo.apkSizeBytes > 0) {
                    releaseInfo.apkSizeBytes
                } else {
                    connection.contentLength.toLong()
                }

                inputStream = connection.inputStream
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var downloaded = 0L
                    var bytesRead: Int
                    var lastReportedPercent = -1

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead

                        val percent = if (totalLength > 0) {
                            ((downloaded * 100) / totalLength).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }

                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            onProgress(percent, downloaded, totalLength)
                        }
                    }
                    output.flush()
                }
                break
            }

            if (!tempFile.exists() || tempFile.length() == 0L) {
                return@withContext Result.failure(Exception("ダウンロードされたファイルが空です"))
            }

            // 一時ファイルから正式なAPKファイルへリネーム
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            Result.success(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "downloadApk failed", e)
            Result.failure(e)
        }
    }

    /**
     * ダウンロードしたAPKのインストールインテントを発行
     */
    fun installApk(context: Context, apkFile: File): Result<Unit> {
        return try {
            if (!apkFile.exists()) {
                return Result.failure(Exception("APKファイルが存在しません: ${apkFile.absolutePath}"))
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "installApk failed", e)
            Result.failure(e)
        }
    }
}
