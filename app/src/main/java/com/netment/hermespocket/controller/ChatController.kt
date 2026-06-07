package com.netment.hermespocket.controller

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.netment.hermespocket.data.MessageRepository
import com.netment.hermespocket.data.SessionEntity
import com.netment.hermespocket.network.HermesWebSocket
import com.netment.hermespocket.network.NetworkUtils
import com.netment.hermespocket.service.HermesService
import com.netment.hermespocket.service.PhoneTools
import com.netment.hermespocket.service.PollWorker
import com.netment.hermespocket.service.VoiceRecognitionEngine
import com.netment.hermespocket.ui.*
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ChatController(
    private val msgRepo: MessageRepository,
    private val context: Context,
    private val scope: CoroutineScope,
    private val tts: android.speech.tts.TextToSpeech?,
    val sessionController: SessionController,
) {
    companion object {
        private const val TAG = "ChatCtrl"
        private const val PAGE_SIZE = 50
    }

    // ── state ──
    val messages = mutableStateListOf<MessageItem>()
    var connectionStatus by mutableStateOf("初始化...")
    var connectionState by mutableStateOf(HermesWebSocket.ConnectionState.DISCONNECTED)
    var isThinking by mutableStateOf(false)
    var pendingApproval by mutableStateOf<HermesWebSocket.ApprovalRequired?>(null)
    var pendingText by mutableStateOf("")
    var accumulatedText by mutableStateOf("")
    var assistantMode by mutableStateOf(AssistantMode.NORMAL)
    var selectedSkills by mutableStateOf<Set<String>>(emptySet())
    var ttsEnabled by mutableStateOf(false)
    var skillChips by mutableStateOf<List<SkillChip>>(emptyList())
    var clipboardText by mutableStateOf<String?>(null)
    var fillInputText by mutableStateOf<String?>(null)
    var hasMoreOlder by mutableStateOf(false)
    var isLoadingMore by mutableStateOf(false)
    var totalMsgCount by mutableIntStateOf(0)
    var pendingAudioPath: String? = null
    var lastTrainingSampleId: String? = null
    private var wsFlowJob: Job? = null

    fun setupEngineCallbacks(engine: VoiceRecognitionEngine) {
        engine.onStatus = { connectionStatus = it }
        engine.onAsrResult = { result ->
            accumulatedText = if (accumulatedText.isEmpty()) result else "$accumulatedText $result"
        }
    }

    fun onSessionSwitched(session: SessionEntity) {
        messages.clear(); assistantMode = AssistantMode.NORMAL
        loadInitialPage(session.id)
    }

    fun onProfileSwitched(session: SessionEntity) {
        messages.clear(); connectionStatus = "切换 Profile..."; assistantMode = AssistantMode.NORMAL
        loadInitialPage(session.id); connectWs(); loadSkills()
    }

    fun initAll() {
        connectionStatus = "初始化..."
        scope.launch { connectionStatus = "连接中..."; connectWs(); PollWorker.enqueue(context); loadSkills(); connectionStatus = "就绪" }
    }

    fun connectWs() {
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("active_session_id", sessionController.wsSessionId).apply()
        if (wsFlowJob?.isActive == true) return

        wsFlowJob = scope.launch {
            launch { HermesService.connectionState.collect { connectionState = it } }
            launch { HermesService.connectionStatus.collect { connectionStatus = it } }
            launch { HermesService.assistantMessages.collect { m -> handleAssistantMsg(m) } }
            launch { HermesService.toolProgress.collect { tp -> connectionStatus = tp.label.ifBlank { "执行: ${tp.tool}" } } }
            launch { HermesService.approvalRequired.collect { ap -> pendingApproval = ap; connectionStatus = "等待确认"; messages.add(0, MessageItem.ApprovalItem(ap)); saveMessage(messages[0]) } }
            launch { HermesService.approvalResolved.collect { r -> resolvePendingApproval(if (r.approved) ApprovalStatus.APPROVED else ApprovalStatus.DENIED); connectionStatus = if (r.approved) "已批准" else "已拒绝" } }
            launch { HermesService.sessionState.collect { state ->
                when (state) { "thinking" -> { isThinking = true; connectionStatus = "思考中..." }; "awaiting_clarify" -> connectionStatus = "等待回复"; "ready" -> { isThinking = false; connectionStatus = "就绪" } }
            } }
            launch { HermesService.clarifyPrompt.collect { cl -> connectionStatus = "等待回复"; messages.add(0, MessageItem.ClarifyItem(cl)); saveMessage(messages[0]) } }
            launch { HermesService.clarifyResolved.collect { cr ->
                val idx = messages.indexOfLast { it is MessageItem.ClarifyItem && it.prompt.clarifyId == cr.clarifyId }
                if (idx >= 0) messages[idx] = (messages[idx] as MessageItem.ClarifyItem).copy(status = ClarifyStatus.RESOLVED)
                connectionStatus = "就绪"
            } }
            launch { HermesService.stepData.collect { stp ->
                val infos = stp.steps.map { s -> StepInfo(s.label, when (s.status) { "done" -> StepStatus.DONE; "running" -> StepStatus.RUNNING; "error" -> StepStatus.ERROR; else -> StepStatus.WAITING }) }
                messages.add(0, MessageItem.StepItem(stp.title, infos))
            } }
            launch { HermesService.suggestion.collect { sg -> messages.add(0, MessageItem.SuggestionItem(sg.title, sg.content)) } }
            launch { HermesService.errorCard.collect { ec -> connectionStatus = "错误: ${ec.error.take(15)}"; messages.add(0, MessageItem.ErrorItem(ec.error, ec.retryable)) } }
            launch { HermesService.serviceError.collect { e -> connectionStatus = "错误: ${e.take(15)}"; messages.add(0, MessageItem.ChatMsg(ChatMessage("⚠️ 错误: $e", false))) } }
            launch { HermesService.fileReceived.collect { f ->
                val label = if (f.mime.startsWith("image/")) "🖼️ 图片已上传" else "📎 ${f.name} 已上传"
                withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, label, android.widget.Toast.LENGTH_SHORT).show() }
            } }
        }
        HermesService.start(context)
    }

    private suspend fun handleAssistantMsg(m: HermesWebSocket.AssistantMessage) {
        if (m.sessionId != sessionController.wsSessionId) return
        Log.i("Session", "recv: text=${m.text.take(30)} msg_sid=${m.sessionId.take(8)} cur_sid=${sessionController.wsSessionId.take(8)}")
        val toolCall = PhoneTools.extractToolCall(m.text)
        if (toolCall != null) {
            val cleanText = m.text.replace(Regex("```tool_call[^`]*```"), "").trim()
            if (cleanText.isNotBlank()) { val item = MessageItem.ChatMsg(ChatMessage(cleanText, false)); messages.add(0, item); saveMessage(item) }
            val result = PhoneTools.execute(context, toolCall)
            val ri = MessageItem.ChatMsg(ChatMessage("[工具: ${toolCall.name}] $result", true)); messages.add(0, ri); saveMessage(ri)
            HermesWebSocket.get()?.sendMessage("[工具结果: ${toolCall.name}] $result", emptySet(), sessionController.wsSessionId)
        } else {
            val atts = m.attachments.map { HermesWebSocket.Attachment(it.name, it.url, it.size, it.mime) }
            val item = MessageItem.ChatMsg(ChatMessage(m.text, false, atts))
            isThinking = false; messages.add(0, item); saveMessage(item)
            PollWorker.setLastMsgId(context, PollWorker.getLastMsgId(context) + 1)
            if (ttsEnabled && m.text.isNotBlank()) playTts(m.text)
        }
    }

    fun reconnectWs() { connectionStatus = "重连中..."; scope.launch { connectWs() } }

    // ── pagination ──

    fun loadInitialPage(sessionId: Long) {
        scope.launch(Dispatchers.IO) {
            totalMsgCount = msgRepo.countBySession(sessionId)
            val offset = maxOf(0, totalMsgCount - PAGE_SIZE)
            val page = msgRepo.loadBySessionPaged(sessionId, PAGE_SIZE, offset)
            hasMoreOlder = offset > 0; isLoadingMore = false
            withContext(Dispatchers.Main) { messages.clear(); messages.addAll(page.reversed()) }
        }
    }

    fun loadMoreMessages() {
        if (isLoadingMore || !hasMoreOlder) return
        val sid = sessionController.currentSession?.id ?: return
        isLoadingMore = true
        scope.launch(Dispatchers.IO) {
            val currentCount = totalMsgCount - messages.size
            val offset = maxOf(0, currentCount - PAGE_SIZE)
            val page = msgRepo.loadBySessionPaged(sid, PAGE_SIZE, offset)
            hasMoreOlder = offset > 0; isLoadingMore = false
            withContext(Dispatchers.Main) { messages.addAll(page.reversed()) }
        }
    }

    // ── send ──

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        expirePendingApprovals()
        val audioPath = pendingAudioPath
        if (audioPath != null) { pendingAudioPath = null; scope.launch(Dispatchers.IO) { uploadTrainingSample(audioPath, text) } }
        val modeInstruction = when (assistantMode) {
            AssistantMode.KNOWLEDGE -> "[技能教学模式] 用户正在教你新技能。请用 skill_manage(action='create') 将以下内容提炼并保存为技能。先和用户确认名称后创建。\n用户说："
            AssistantMode.MEMORY -> "[记忆模式] 用户需要你记住以下信息。请用 memory 工具保存，每条要简洁精准。\n用户说："
            AssistantMode.NORMAL -> ""
        }
        val fullText = buildString { if (modeInstruction.isNotEmpty()) append("$modeInstruction$text") else append(text); append("\n\n${PhoneTools.buildSystemPrompt()}") }
        val currentSkills = selectedSkills.toSet()
        val displayText = if (assistantMode != AssistantMode.NORMAL) "[${assistantMode.label}] $text" else text
        val item = MessageItem.ChatMsg(ChatMessage(displayText, true, status = MessageStatus.SENDING))
        messages.add(0, item); pendingText = ""; accumulatedText = ""
        val sid = sessionController.wsSessionId
        scope.launch {
            sessionController.currentSession?.let { msgRepo.insert(it.id, item.copy(msg = item.msg.copy(status = MessageStatus.SENT))) }
            if (doSend(fullText, currentSkills, sid)) return@launch
            for ((i, delayMs) in listOf(1000L, 3000L, 8000L).withIndex()) {
                updateSentStatus(MessageStatus.RETRYING, i + 1); delay(delayMs)
                if (doSend(fullText, currentSkills, sid)) return@launch
            }
            updateSentStatus(MessageStatus.FAILED)
        }
    }

    private fun doSend(text: String, skills: Set<String>, sid: String): Boolean {
        val ok = HermesWebSocket.get()?.sendMessage(text, skills, sid) ?: false
        if (ok) updateSentStatus(MessageStatus.SENT); return ok
    }

    private fun updateSentStatus(status: MessageStatus, retryAttempt: Int = 0) {
        val idx = messages.indexOfLast { it is MessageItem.ChatMsg && it.msg.isUser && it.msg.status in listOf(MessageStatus.SENDING, MessageStatus.RETRYING) }
        if (idx >= 0) { val m = messages[idx] as MessageItem.ChatMsg; messages[idx] = m.copy(msg = m.msg.copy(status = status, retryAttempt = retryAttempt)) }
    }

    fun retrySendMessage(idx: Int) {
        if (idx !in messages.indices) return
        val item = messages[idx]
        if (item !is MessageItem.ChatMsg || !item.msg.isUser || item.msg.status != MessageStatus.FAILED) return
        messages.removeAt(idx); sendMessage(item.msg.text)
    }

    fun addOutgoingMessage(chatMsg: ChatMessage) {
        val item = MessageItem.ChatMsg(chatMsg.copy(status = MessageStatus.SENDING))
        messages.add(0, item); saveMessage(item)
        val ok = HermesWebSocket.get()?.sendMessage(chatMsg.text, emptySet(), sessionController.wsSessionId) ?: false
        updateSentStatus(if (ok) MessageStatus.SENT else MessageStatus.FAILED)
    }

    // ── approval ──

    fun resolvePendingApproval(status: ApprovalStatus) {
        val idx = messages.indexOfLast { it is MessageItem.ApprovalItem && it.status == ApprovalStatus.PENDING }
        if (idx >= 0) { val item = messages[idx] as MessageItem.ApprovalItem; messages[idx] = item.copy(status = status); sessionController.currentSession?.let { s -> scope.launch { msgRepo.updateApprovalStatus(s.id, item.approval, status) } } }
        pendingApproval = null
    }

    fun expirePendingApprovals() {
        for (i in messages.indices) { val item = messages[i]; if (item is MessageItem.ApprovalItem && item.status == ApprovalStatus.PENDING) messages[i] = item.copy(status = ApprovalStatus.DENIED) }
        pendingApproval = null; sessionController.currentSession?.let { s -> scope.launch { msgRepo.expireAllPending(s.id) } }
    }

    fun saveMessage(item: MessageItem) { sessionController.currentSession?.let { s -> scope.launch { msgRepo.insert(s.id, item) } } }

    fun findClarifyItem(clarifyId: String): Int = messages.indexOfLast { it is MessageItem.ClarifyItem && it.prompt.clarifyId == clarifyId }

    // ── skills ──

    private fun loadSkills() { scope.launch(Dispatchers.IO) { while (isActive) { skillChips = NetworkUtils.loadCapabilities(context, sessionController.activeProfile); delay(300_000) } } }

    // ── export / clear ──

    fun exportCurrentSession() {
        val name = sessionController.currentSession?.name ?: "会话"; val ms = messages.toList()
        if (ms.isEmpty()) { toast("没有消息可导出"); return }
        scope.launch { ExportUtil.exportJson(context, name, ms) }
    }

    fun clearCurrentSessionMessages() {
        sessionController.currentSession?.let { s -> scope.launch { msgRepo.clearBySession(s.id); messages.clear() } }; toast("消息已清空")
    }

    private fun toast(msg: String) { android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show() }

    // ── TTS ──

    private fun playTts(text: String) {
        val httpUrl = AppSettings.getHttpUrl(context).replace(Regex("^ws"), "http")
        scope.launch(Dispatchers.IO) {
            try {
                val conn = (URL("$httpUrl/v1/tts").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 30000; readTimeout = 60000 }
                conn.outputStream.use { it.write("""{"text":${jsonString(text)}}""".toByteArray()) }
                if (conn.responseCode != 200) return@launch
                val f = File(context.cacheDir, "tts_${System.currentTimeMillis()}.wav")
                conn.inputStream.use { inp -> f.outputStream().use { out -> inp.copyTo(out) } }
                withContext(Dispatchers.Main) { val mp = MediaPlayer(); mp.setDataSource(f.absolutePath); mp.prepare(); mp.start(); mp.setOnCompletionListener { it.release(); f.delete() } }
            } catch (_: Exception) {}
        }
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\""); for (ch in s) when (ch) { '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t"); else -> sb.append(ch) }; return sb.append("\"").toString()
    }

    // ── training ──

    private suspend fun uploadTrainingSample(wavPath: String, text: String) {
        try {
            val baseUrl = AppSettings.getHttpUrl(context).replace(Regex("^ws"), "http")
            val boundary = "Boundary-${System.currentTimeMillis()}"
            val conn = (URL("$baseUrl/v1/training").openConnection() as HttpURLConnection).apply { doOutput = true; requestMethod = "POST"; setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary"); connectTimeout = 15000; readTimeout = 15000 }
            val body = java.io.ByteArrayOutputStream(); val le = "\r\n"; val th = "--"
            body.write("$th$boundary$le".toByteArray()); body.write("Content-Disposition: form-data; name=\"audio\"; filename=\"sample.wav\"$le".toByteArray()); body.write("Content-Type: audio/wav$le$le".toByteArray())
            body.write(File(wavPath).readBytes()); body.write(le.toByteArray())
            body.write("$th$boundary$le".toByteArray()); body.write("Content-Disposition: form-data; name=\"text\"$le$le".toByteArray()); body.write(text.toByteArray(Charsets.UTF_8)); body.write(le.toByteArray())
            body.write("$th$boundary$le".toByteArray()); body.write("Content-Disposition: form-data; name=\"session_id\"$le$le".toByteArray()); body.write((sessionController.currentSession?.hermesSessionId ?: "").toByteArray(Charsets.UTF_8)); body.write(le.toByteArray())
            body.write("$th$boundary$th$le".toByteArray())
            conn.outputStream.use { it.write(body.toByteArray()) }
            val json = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
            if (json.optBoolean("ok", false)) { lastTrainingSampleId = json.optString("id", null); Log.i(TAG, "Training sample uploaded: $lastTrainingSampleId") }
            conn.disconnect()
        } catch (e: Exception) { Log.e(TAG, "Upload training sample failed", e) }
    }

    fun updateTrainingText(correctedText: String) {
        val sampleId = lastTrainingSampleId ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val baseUrl = AppSettings.getHttpUrl(context).replace(Regex("^ws"), "http")
                val conn = (URL("$baseUrl/v1/training").openConnection() as HttpURLConnection).apply { doOutput = true; requestMethod = "POST"; setRequestProperty("X-HTTP-Method-Override", "PATCH"); setRequestProperty("Content-Type", "application/json"); connectTimeout = 10000; readTimeout = 10000 }
                conn.outputStream.use { it.write(org.json.JSONObject().apply { put("id", sampleId); put("text", correctedText) }.toString().toByteArray()) }
                conn.inputStream.bufferedReader().readText(); conn.disconnect(); Log.i(TAG, "Training text updated: $sampleId")
            } catch (e: Exception) { Log.e(TAG, "Update training text failed", e) }
        }
    }
}
