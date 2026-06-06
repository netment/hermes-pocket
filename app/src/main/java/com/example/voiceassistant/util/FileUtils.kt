package com.example.voiceassistant.util

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * URI 和文件工具方法，从 MainActivity 抽出以保持简洁。
 */
object FileUtils {

    private const val TAG = "FileUtils"

    fun copyUriToCache(context: Context, uri: Uri, name: String): File? = try {
        val dir = File(context.cacheDir, "uploads"); dir.mkdirs()
        val out = File(dir, "${System.currentTimeMillis()}_$name")
        context.contentResolver.openInputStream(uri)?.use { inp ->
            out.outputStream().use { o -> inp.copyTo(o) }
        }
        out.takeIf { it.length() > 0 }
    } catch (e: Exception) { Log.e(TAG, "copyUri", e); null }

    fun getFileName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i) else null
            } else null
        }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
        else -> "${bytes}B"
    }
}
