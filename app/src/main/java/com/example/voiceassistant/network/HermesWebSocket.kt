package com.example.voiceassistant.network

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
class HermesWebSocket(
    private val baseUrl: String = "ws://localhost:8643",
    val sessionId: String = java.util.UUID.randomUUID().toString()
) {
    // HTTP base URL derived from ws:// → http://
    val httpBaseUrl: String = baseUrl.replaceFirst("^ws", "http")
    
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

    enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED }
    val connectionStateChannel = Channel<ConnectionState>(Channel.CONFLATED)

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
        val attachments: List<Attachment> = emptyList()
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
        scope.launch {
            try {
                connectionStateChannel.trySend(ConnectionState.CONNECTING)
                statusChannel.send("连接中...")
                val request = Request.Builder()
                    .url("$baseUrl/v1/ws?session_id=$sessionId")
                    .build()

                ws = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        connected = true
                        reconnectAttempts = 0
                        scope.launch {
                            connectionStateChannel.send(ConnectionState.CONNECTED)
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
                                        assistantChannel.send(AssistantMessage(msgText, atts))
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
                                    scope.launch {
                                        assistantChannel.send(AssistantMessage("", listOf(att)))
                                    }
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
                        connected = false
                        scope.launch {
                            connectionStateChannel.send(ConnectionState.DISCONNECTED)
                            statusChannel.send("连接失败: ${t.message}")
                        }
                        scheduleReconnect()
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        connected = false
                        scope.launch { connectionStateChannel.send(ConnectionState.DISCONNECTED) }
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
                connectionStateChannel.send(ConnectionState.DISCONNECTED)
            }
            return
        }
        reconnectAttempts++
        scope.launch {
            delay((reconnectAttempts * 2000L).coerceAtMost(30000L))
            statusChannel.send("重连中...($reconnectAttempts/$maxReconnectAttempts)")
            connect()
        }
    }

    fun sendMessage(text: String, selectedSkills: Set<String> = emptySet()): Boolean {
        if (text.isBlank()) return false
        if (!connected) return false
        val json = JSONObject().apply {
            put("type", "user_message")
            put("text", text)
            put("session_id", sessionId)
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
            put("session_id", sessionId)
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
    fun uploadFile(fileName: String, fileBytes: ByteArray, mimeType: String = "image/jpeg", httpUrl: String = httpBaseUrl): String? {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName,
                    RequestBody.create(mimeType.toMediaType(), fileBytes))
                .build()
            val request = Request.Builder()
                .url("$httpUrl/v1/upload?session_id=$sessionId")
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
        scope.launch { connectionStateChannel.send(ConnectionState.DISCONNECTED) }
        ws?.close(1000, "用户断开")
        scope.cancel()
    }
}
