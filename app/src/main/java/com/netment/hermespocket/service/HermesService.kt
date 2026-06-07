package com.netment.hermespocket.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.netment.hermespocket.MainActivity
import com.netment.hermespocket.data.*
import com.netment.hermespocket.network.HermesWebSocket
import com.netment.hermespocket.ui.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 前台 Service：WS 长连接中枢 + 事件总线。
 *
 * 所有 WS 事件通过 companion object 的 StateFlow/SharedFlow 暴露，
 * MainActivity collect 即可，不依赖生命周期时机。
 */
class HermesService : Service() {

    companion object {
        const val TAG = "HermesService"
        const val CHANNEL_ID = "hermes_connection"
        const val NOTIFICATION_ID = 2
        private const val POLL_INTERVAL_MS = 60_000L

        // ── 事件总线（UI 层 collect 消费） ──
        val connectionState = MutableStateFlow(HermesWebSocket.ConnectionState.DISCONNECTED)
        val connectionStatus = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val assistantMessages = MutableSharedFlow<HermesWebSocket.AssistantMessage>(extraBufferCapacity = 64)
        val toolProgress = MutableSharedFlow<HermesWebSocket.ToolProgress>(extraBufferCapacity = 16)
        val approvalRequired = MutableSharedFlow<HermesWebSocket.ApprovalRequired>(extraBufferCapacity = 8)
        val approvalResolved = MutableSharedFlow<HermesWebSocket.ApprovalResolved>(extraBufferCapacity = 8)
        val sessionState = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val clarifyPrompt = MutableSharedFlow<HermesWebSocket.ClarifyPrompt>(extraBufferCapacity = 8)
        val clarifyResolved = MutableSharedFlow<HermesWebSocket.ClarifyResolved>(extraBufferCapacity = 8)
        val stepData = MutableSharedFlow<HermesWebSocket.StepData>(extraBufferCapacity = 8)
        val suggestion = MutableSharedFlow<HermesWebSocket.SuggestionData>(extraBufferCapacity = 8)
        val errorCard = MutableSharedFlow<HermesWebSocket.ErrorCardData>(extraBufferCapacity = 8)
        val serviceError = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val fileReceived = MutableSharedFlow<HermesWebSocket.Attachment>(extraBufferCapacity = 8)

        fun start(context: Context) {
            val intent = Intent(context, HermesService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HermesService::class.java))
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null
    private val deviceId: String by lazy {
        getSharedPreferences(AppSettings.PREFS_NAME, MODE_PRIVATE)
            .getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also {
                getSharedPreferences(AppSettings.PREFS_NAME, MODE_PRIVATE).edit().putString("device_id", it).apply()
            }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand deviceId=${deviceId.take(8)}")
        startForeground(NOTIFICATION_ID, buildNotification("连接中..."))

        val wsUrl = AppSettings.getWsUrl(this@HermesService)
        Log.i(TAG, "connecting to $wsUrl")
        val ws = HermesWebSocket.getOrCreate(wsUrl, deviceId)

        // Bridge WS channels → companion flows
        scope.launch {
            launch { ws.connectionStateChannel.collect { s ->
                connectionState.value = s
                val text = when (s) {
                    HermesWebSocket.ConnectionState.CONNECTED -> { stopPolling(); "已连接" }
                    HermesWebSocket.ConnectionState.CONNECTING -> "连接中..."
                    HermesWebSocket.ConnectionState.DISCONNECTED -> { startPolling(); "未连接" }
                }
                updateNotification(text)
            } }
            launch { for (s in ws.statusChannel) { connectionStatus.tryEmit(s) } }
            launch { for (m in ws.assistantChannel) {
                Log.i("Session", "Service bridge: text=${m.text.take(30)} session_id=${m.sessionId.take(8)}")
                assistantMessages.tryEmit(m)
                if (!ws.isForeground && m.text.isNotBlank()) showMessageNotification(m.text)
            } }
            launch { for (tp in ws.toolChannel) { toolProgress.tryEmit(tp) } }
            launch { for (ap in ws.approvalChannel) { approvalRequired.tryEmit(ap) } }
            launch { for (r in ws.approvalResolvedChannel) { approvalResolved.tryEmit(r) } }
            launch { for (st in ws.sessionStateChannel) { sessionState.tryEmit(st.state) } }
            launch { for (cl in ws.clarifyChannel) { clarifyPrompt.tryEmit(cl) } }
            launch { for (cr in ws.clarifyResolvedChannel) { clarifyResolved.tryEmit(cr) } }
            launch { for (stp in ws.stepChannel) { stepData.tryEmit(stp) } }
            launch { for (sg in ws.suggestionChannel) { suggestion.tryEmit(sg) } }
            launch { for (ec in ws.errorCardChannel) { errorCard.tryEmit(ec) } }
            launch { for (e in ws.errorChannel) { serviceError.tryEmit(e) } }
            launch { for (f in ws.fileChannel) { fileReceived.tryEmit(f) } }
        }

        scope.launch { ws.connect() }

        // WorkManager 兜底
        PollWorker.enqueue(this@HermesService)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopPolling()
        HermesWebSocket.destroy()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        HermesWebSocket.destroy()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    // ── 轮询 ──────────────────────────────────────

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            Log.i(TAG, "startPolling: interval=${POLL_INTERVAL_MS / 1000}s")
            while (isActive) { try { doPoll() } catch (_: Exception) {}; delay(POLL_INTERVAL_MS) }
        }
    }

    private fun stopPolling() { pollJob?.cancel(); pollJob = null }

    private fun doPoll() {
        val prefs = getSharedPreferences(AppSettings.PREFS_NAME, MODE_PRIVATE)
        val httpUrl = AppSettings.getHttpUrl(this, AppSettings.getActiveProfile(this))
        val sessionId = prefs.getString("active_session_id", "") ?: ""
        val lastMsgId = PollWorker.getLastMsgId(this)
        if (sessionId.isBlank()) return
        try {
            val url = URL("$httpUrl/v1/poll?session_id=$sessionId&since_msg_id=$lastMsgId")
            val conn = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 5000 }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            if (json.optBoolean("has_new", false)) {
                val c = json.optInt("count", 0); val p = json.optString("preview", "")
                PollWorker.setLastMsgId(this, json.optInt("last_msg_id", lastMsgId))
                showMessageNotification(c, p); Log.i(TAG, "doPoll: $c new msgs")
            }
        } catch (e: Exception) { Log.w(TAG, "doPoll: ${e.message}") }
    }

    // ── 通知 ──────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "连接状态", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "后台连接状态"; setShowBadge(false)
                })
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("有数").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun showMessageNotification(text: String) = showMessageNotification(1, text)

    private fun showMessageNotification(count: Int, preview: String) {
        val pi = PendingIntent.getActivity(this, 1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = if (count == 1) "有数 · 新回复" else "有数 · $count 条新回复"
        val body = if (preview.length > 80) preview.take(80) + "…" else preview
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(3,
            NotificationCompat.Builder(this, "new_messages")
                .setContentTitle(title).setContentText(body)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pi)
                .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT).build())
    }
}
