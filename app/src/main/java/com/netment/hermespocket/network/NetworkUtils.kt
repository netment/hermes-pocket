package com.netment.hermespocket.network

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import com.netment.hermespocket.ui.SkillChip
import com.netment.hermespocket.ui.AppSettings
import org.json.JSONObject

/**
 * Network utilities extracted from MainActivity.
 */
object NetworkUtils {

    private const val TAG = "NetworkUtils"

    fun downloadFile(context: Context, baseUrl: String, relPath: String, name: String) = try {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(
            DownloadManager.Request(Uri.parse("$baseUrl$relPath"))
                .setTitle("下载: $name")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
        )
        Toast.makeText(context, "开始下载: $name", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Log.e(TAG, "dl", e)
        Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }

    fun loadCapabilities(context: Context, activeProfile: String): List<SkillChip> = try {
        val rawBase = AppSettings.getHttpUrl(context)
        val base = rawBase.replace(Regex("^ws://"), "http://").replace(Regex("^wss://"), "https://")
        val url = "$base/v1/capabilities?profile=$activeProfile"
        Log.d(TAG, "loadCapabilities: fetching $url")
        val json = java.net.URL(url).readText()
        Log.d(TAG, "loadCapabilities: raw response (${json.length} chars): ${json.take(500)}")
        val arr = JSONObject(json).getJSONArray("capabilities")
        val chips = (0 until arr.length()).map {
            val obj = arr.getJSONObject(it)
            SkillChip(obj.getString("name"), obj.optString("description", ""))
        }
        Log.d(TAG, "loadCapabilities: loaded ${chips.size} chips: ${chips.map { it.name }}")
        chips
    } catch (e: Exception) {
        Log.e(TAG, "loadCapabilities failed: ${e.javaClass.simpleName} - ${e.message}", e)
        emptyList()
    }
}
