package com.netment.hermespocket.network

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Hermes WebSocket 客户端。
 *
 * 协议（JSON 帧）：
 *   发送: {"type":"user_message","text":"...","session_id":"..."}
 *         {"type":"approval_response","approved":true,"choice":"once|session|always"}
 *   接收: {"type":"assistant_message","text":"...","attachments":[...]}
 *         {"type":"tool_progress","tool":"...","status":"...","label":"..."}
 *         {"type":"approval_required","approval_id":"...","command":"...","description":"..."}
 *         {"type":"approval_resolved","approved":true}
 *         {"type":"session_state","state":"thinking|awaiting_approval|ready"}
 *         {"type":"status","text":"..."}
 *         {"type":"error","text":"..."}
 */
class HermesWebSocket internal constructor(
    val baseUrl: String,
    val deviceId: String
) {
    // HTTP base URL derived from ws:// → http://
    val httpBaseUrl: String = baseUrl.replaceFirst("^ws", "http")

    companion object {
        @Volatile private var _instance: HermesWebSocket? = null

        fun getOrCreate(baseUrl: String, deviceId: String): HermesWebSocket {
            return _instance ?: synchronized(this) {
                _instance ?: HermesWebSocket(baseUrl, deviceId).also { _instance = it }
            }
        }

        fun get(): HermesWebSocket? = _instance

        fun destroy() {
            synchronized(this) {
                _instance?.disconnect()
                _instance = null
            }
        }
    }

    /** 前台标记：Activity onResume=true, onPause=false */
    @Volatile var isForeground = false
    
    // 输出通道
    val assistantChannel = Channel<AssistantMessage>(Channel.BUFFERED)
    val statusChannel = Channel<String>(Channel.CONFLATED)
    val toolChannel = Channel<ToolProgress>(Channel.BUFFERED)
    val approvalChannel = Channel<ApprovalRequired>(Channel.BUFFERED)
    val sessionStateChannel = Channel<SessionState>(Channel.CONFLATED)
    val approvalResolvedChannel = Channel<ApprovalResolved>(Channel.BUFFERED)
    val errorChannel = Channel<String>(Channel.CONFLATED)
    val clarifyChannel = Channel<ClarifyPrompt>(Channel.BUFFERED)
    val clarifyResolvedChannel = Channel<ClarifyResolved>(Channel.BUFFERED)
    val stepChannel = Channel<StepData>(Channel.BUFFERED)
    val suggestionChannel = Channel<SuggestionData>(Channel.BUFFERED)
    val errorCardChannel = Channel<ErrorCardData>(Channel.BUFFERED)
    val fileChannel = Channel<Attachment>(Channel.BUFFERED)

    enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED }
    val connectionStateChannel = MutableStateFlow(ConnectionState.DISCONNECTED)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var connected = false
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10

    data class AssistantMessage(
        val text: String,
        val attachments: List<Attachment> = emptyList(),
        val sessionId: String = ""
    )

    data class Attachment(
        val name: String, val url: String, val size: Long, val mime: String
    )

    data class ToolProgress(
        val tool: String, val status: String, val label: String = ""
    )

    data class ApprovalRequired(
        val approvalId: String,
        val command: String,
        val description: String,
        val patternKeys: List<String> = emptyList()
    )

    data class ApprovalResolved(
        val approved: Boolean, val choice: String = ""
    )

    data class SessionState(
        val state: String  // thinking | awaiting_approval | ready
    )

    data class ClarifyPrompt(
        val question: String,
        val choices: List<String>,
        val clarifyId: String
    )

    data class ClarifyResolved(
        val clarifyId: String,
        val response: String
    )

    data class StepData(
        val title: String,
        val steps: List<StepInfo>
    )
    data class StepInfo(
        val label: String,
        val status: String  // waiting | running | done | error
    )
    data class SuggestionData(
        val title: String,
        val content: String
    )
    data class ErrorCardData(
        val error: String,
        val retryable: Boolean = false
    )

    fun connect() {
        if (connected || ws != null) return  // already connecting/connected
        reconnectAttempts = 0  // reset for fresh connection
        Log.d("HermesWS", "connect: ${baseUrl}/v1/ws?device_id=$deviceId")
        scope.launch {
            try {
                connectionStateChannel.value = ConnectionState.CONNECTING
                statusChannel.send("连接中...")
                val request = Request.Builder()
                    .url("$baseUrl/v1/ws?device_id=$deviceId")
                    .build()

                ws = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.i("HermesWS", "onOpen connected")
                        connected = true
                        reconnectAttempts = 0
                        scope.launch {
                            connectionStateChannel.value = ConnectionState.CONNECTED
                            statusChannel.send("已连接")
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            val json = JSONObject(text)
                            val type = json.optString("type", "")
                            when (type) {
                                "status" -> scope.launch {
                                    val rawText = json.optString("text", "")
                                    statusChannel.send(rawText.replace("Hermes", "").trim())
                                }
                                "assistant_message" -> {
                                    val msgText = json.optString("text", "")
                                    val atts = mutableListOf<Attachment>()
                                    val attArr = json.optJSONArray("attachments")
                                    if (attArr != null) {
                                        for (i in 0 until attArr.length()) {
                                            val a = attArr.getJSONObject(i)
                                            atts.add(Attachment(
                                                name = a.optString("name", "file"),
                                                url = a.optString("url", ""),
                                                size = a.optLong("size", 0),
                                                mime = a.optString("mime", "")
                                            ))
                                        }
                                    }
                                    scope.launch {
                                        val sid = json.optString("session_id", "")
                                        Log.i("Session", "recv assistant: text=${msgText.take(30)} session_id=${sid.take(8)}")
                                        assistantChannel.send(AssistantMessage(msgText, atts, sid))
                                        statusChannel.send("就绪")
                                    }
                                }
                                "tool_progress" -> scope.launch {
                                    toolChannel.send(ToolProgress(
                                        tool = json.optString("tool", ""),
                                        status = json.optString("status", ""),
                                        label = json.optString("label", "")
                                    ))
                                }
                                "approval_required" -> {
                                    val keys = mutableListOf<String>()
                                    val arr = json.optJSONArray("pattern_keys")
                                    if (arr != null) {
                                        for (i in 0 until arr.length()) keys.add(arr.getString(i))
                                    }
                                    scope.launch {
                                        approvalChannel.send(ApprovalRequired(
                                            approvalId = json.optString("approval_id", ""),
                                            command = json.optString("command", ""),
                                            description = json.optString("description", ""),
                                            patternKeys = keys
                                        ))
                                    }
                                }
                                "approval_resolved" -> scope.launch {
                                    approvalResolvedChannel.send(ApprovalResolved(
                                        approved = json.optBoolean("approved", false),
                                        choice = json.optString("choice", "")
                                    ))
                                }
                                "session_state" -> scope.launch {
                                    sessionStateChannel.send(SessionState(
                                        state = json.optString("state", "ready")
                                    ))
                                }
                                "file" -> {
                                    val att = Attachment(
                                        name = json.optString("name", "file"),
                                        url = json.optString("url", ""),
                                        size = json.optLong("size", 0),
                                        mime = json.optString("mime", "")
                                    )
                                    scope.launch { fileChannel.send(att) }
                                }
                                "error" -> scope.launch {
                                    errorChannel.send(json.optString("text", "未知错误"))
                                }
                                "clarify" -> {
                                    val choices = mutableListOf<String>()
                                    val arr = json.optJSONArray("choices")
                                    if (arr != null) {
                                        for (i in 0 until arr.length()) choices.add(arr.getString(i))
                                    }
                                    scope.launch {
                                        clarifyChannel.send(ClarifyPrompt(
                                            question = json.optString("question", ""),
                                            choices = choices,
                                            clarifyId = json.optString("clarify_id", "")
                                        ))
                                    }
                                }
                                "clarify_resolved" -> scope.launch {
                                    clarifyResolvedChannel.send(ClarifyResolved(
                                        clarifyId = json.optString("clarify_id", ""),
                                        response = json.optString("response", "")
                                    ))
                                }
                                "step" -> {
                                    val title = json.optString("title", "任务进度")
                                    val steps = mutableListOf<StepInfo>()
                                    val arr = json.optJSONArray("steps")
                                    if (arr != null) {
                                        for (i in 0 until arr.length()) {
                                            val s = arr.getJSONObject(i)
                                            steps.add(StepInfo(
                                                label = s.optString("label", ""),
                                                status = s.optString("status", "waiting")
                                            ))
                                        }
                                    }
                                    scope.launch { stepChannel.send(StepData(title, steps)) }
                                }
                                "suggestion" -> scope.launch {
                                    suggestionChannel.send(SuggestionData(
                                        title = json.optString("title", ""),
                                        content = json.optString("content", "")
                                    ))
                                }
                                "error_card" -> scope.launch {
                                    errorCardChannel.send(ErrorCardData(
                                        error = json.optString("error", "未知错误"),
                                        retryable = json.optBoolean("retryable", false)
                                    ))
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.e("HermesWS", "onFailure: ${t.message}", t)
                        connected = false
                        ws = null
                        scope.launch {
                            connectionStateChannel.value = ConnectionState.DISCONNECTED
                            statusChannel.send("连接失败: ${t.message}")
                        }
                        scheduleReconnect()
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.w("HermesWS", "onClosed code=$code reason=$reason")
                        connected = false
                        ws = null
                        scope.launch { connectionStateChannel.value = ConnectionState.DISCONNECTED }
                        scheduleReconnect()
                    }
                })
            } catch (e: Exception) {
                scope.launch { statusChannel.send("连接异常: ${e.message}") }
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            scope.launch {
                statusChannel.send("连接失败")
                connectionStateChannel.value = ConnectionState.DISCONNECTED
            }
            return
        }
        reconnectAttempts++
        Log.i("HermesWS", "scheduleReconnect attempt=$reconnectAttempts/$maxReconnectAttempts delay=${(reconnectAttempts * 2000L).coerceAtMost(30000L)}ms")
        scope.launch {
            delay((reconnectAttempts * 2000L).coerceAtMost(30000L))
            statusChannel.send("重连中...($reconnectAttempts/$maxReconnectAttempts)")
            connect()
        }
    }

    fun sendMessage(text: String, selectedSkills: Set<String> = emptySet(), targetSessionId: String = ""): Boolean {
        if (text.isBlank()) return false
        if (!connected) { Log.w("Session", "sendMessage: not connected"); return false }
        val sid = targetSessionId.ifEmpty { deviceId }  // fallback to deviceId for legacy
        Log.i("Session", "sendMessage: text=${text.take(30)} session_id=${sid.take(8)} skills=${selectedSkills.size}")
        val json = JSONObject().apply {
            put("type", "user_message")
            put("text", text)
            put("session_id", sid)
            if (selectedSkills.isNotEmpty()) {
                put("selectedSkills", JSONArray(selectedSkills.toList()))
            }
        }
        val ok = ws?.send(json.toString()) ?: false
        if (ok) {
            scope.launch { statusChannel.send("思考中...") }
        } else {
            scope.launch { errorChannel.send("发送失败") }
        }
        return ok
    }

    /** 发送系统消息（工具注册等） */
    fun sendSystemMessage(text: String) {
        val json = JSONObject().apply {
            put("type", "user_message")
            put("text", "[系统] $text")
            put("session_id", deviceId)
        }
        ws?.send(json.toString())
    }

    fun sendApproval(approved: Boolean, choice: String = "once") {
        val json = JSONObject().apply {
            put("type", "approval_response")
            put("approved", approved)
            put("choice", choice)
        }
        ws?.send(json.toString())
    }

    fun sendClarifyResponse(clarifyId: String, text: String) {
        val json = JSONObject().apply {
            put("type", "clarify_response")
            put("clarify_id", clarifyId)
            put("text", text)
        }
        ws?.send(json.toString())
    }

    /** 上传文件到 Hermes 服务器，返回文件信息 JSON（含 file_id, name, url 等） */
    fun uploadFile(fileName: String, fileBytes: ByteArray, mimeType: String = "image/jpeg", httpUrl: String = httpBaseUrl, targetSessionId: String = "", uploadDeviceId: String = ""): String? {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(300, TimeUnit.SECONDS)
                .build()
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName,
                    RequestBody.create(mimeType.toMediaType(), fileBytes))
                .build()
            val urlBuilder = StringBuilder("$httpUrl/v1/upload")
            val params = mutableListOf<String>()
            val sid = targetSessionId.ifEmpty { deviceId }
            params.add("session_id=${java.net.URLEncoder.encode(sid, "UTF-8")}")
            val did = uploadDeviceId.ifEmpty { deviceId }
            params.add("device_id=${java.net.URLEncoder.encode(did, "UTF-8")}")
            if (params.isNotEmpty()) urlBuilder.append("?").append(params.joinToString("&"))
            val request = Request.Builder()
                .url(urlBuilder.toString())
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()
            } else {
                android.util.Log.e("Sage", "uploadFile HTTP ${response.code}: ${response.message}")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("Sage", "uploadFile error: ${e.message}", e)
            null
        }
    }

    fun disconnect() {
        connected = false
        reconnectAttempts = maxReconnectAttempts
        scope.launch { connectionStateChannel.value = ConnectionState.DISCONNECTED }
        ws?.close(1000, "用户断开")
        scope.cancel()
    }
}
