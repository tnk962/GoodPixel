package com.example.goodpixel.data.edge

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import org.json.JSONArray

import android.os.Build

data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable? = null
)

/**
 * エッジパネルに表示するアプリ一覧の管理
 */
object EdgeAppManager {
    private const val PREFS_NAME = "goodpixel_prefs"
    private const val KEY_SELECTED_APPS = "pref_edge_panel_selected_apps"

    fun getInstalledLaunchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PackageManager.MATCH_ALL
        } else {
            0
        }

        val appMap = mutableMapOf<String, AppEntry>()

        // 1. ACTION_MAIN + CATEGORY_LAUNCHER
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, flags)
        for (info in resolveInfos) {
            val pkg = info.activityInfo.packageName
            if (pkg == context.packageName) continue
            val label = info.loadLabel(pm).toString()
            val icon = info.loadIcon(pm)
            appMap[pkg] = AppEntry(pkg, label, icon)
        }

        // 2. getInstalledApplications + getLaunchIntentForPackage で漏れなく取得
        try {
            val installedApps = pm.getInstalledApplications(flags)
            for (app in installedApps) {
                val pkg = app.packageName
                if (pkg == context.packageName) continue
                if (!appMap.containsKey(pkg)) {
                    val launchIntent = pm.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        val label = pm.getApplicationLabel(app).toString()
                        val icon = pm.getApplicationIcon(app)
                        appMap[pkg] = AppEntry(pkg, label, icon)
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        return appMap.values.sortedBy { it.label.lowercase() }
    }

    fun getSelectedAppPackages(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_SELECTED_APPS, null)
        if (jsonStr.isNullOrEmpty()) {
            // デフォルト：端末の主要アプリ最大8個
            val defaultApps = getInstalledLaunchableApps(context).take(8).map { it.packageName }
            saveSelectedAppPackages(context, defaultApps)
            return defaultApps
        }
        val list = mutableListOf<String>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray.getString(i))
            }
        } catch (e: Exception) {
            // fallback
        }
        return list
    }

    fun saveSelectedAppPackages(context: Context, packages: List<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        packages.forEach { jsonArray.put(it) }
        prefs.edit().putString(KEY_SELECTED_APPS, jsonArray.toString()).apply()
    }
}
