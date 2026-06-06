package com.netment.hermespocket.service

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.netment.hermespocket.MainActivity
import com.netment.hermespocket.ui.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * WorkManager 每15分轮询 Hermes，有新消息弹通知。
 */
class PollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "PollWorker"
        const val WORK_NAME = "poll_hermes"
        const val CHANNEL_ID = "new_messages"
        private const val PREFS_NAME = "poll_prefs"
        private const val KEY_LAST_MSG_ID = "last_msg_id"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        fun setLastMsgId(context: Context, msgId: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_LAST_MSG_ID, msgId).apply()
        }

        fun getLastMsgId(context: Context): Int {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_LAST_MSG_ID, 0)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val wsUrl = AppSettings.getWsUrl(applicationContext)
            val httpUrl = wsUrl.replace("ws://", "http://").replace("ws://", "http://")

            // 从 SharedPreferences 读取活跃会话 ID
            val sessionId = getActiveSessionId()
            val lastMsgId = getLastMsgId(applicationContext)

            val url = URL("$httpUrl/v1/poll?session_id=$sessionId&since_msg_id=$lastMsgId")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val response = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(response)

            if (json.optBoolean("has_new", false)) {
                val count = json.optInt("count", 0)
                val preview = json.optString("preview", "新消息")
                val newMsgId = json.optInt("last_msg_id", lastMsgId)

                showNotification(count, preview)
                setLastMsgId(applicationContext, newMsgId)
                Log.i(TAG, "poll: $count new msgs, preview=$preview")
            }

            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "poll failed: ${e.message}")
            Result.retry()
        }
    }

    private fun getActiveSessionId(): String {
        val prefs = applicationContext.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("active_session_id", "default") ?: "default"
    }

    private fun showNotification(count: Int, preview: String) {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (count == 1) "有数 · 新回复" else "有数 · $count 条新消息"
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(preview)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(1, notification)
    }
}
