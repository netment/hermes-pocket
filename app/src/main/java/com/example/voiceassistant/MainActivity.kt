package com.example.voiceassistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.voiceassistant.service.PollWorker
import com.example.voiceassistant.service.PhoneTools
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import com.example.voiceassistant.data.*
import com.example.voiceassistant.network.HermesWebSocket
import com.example.voiceassistant.service.VoiceRecognitionEngine
import com.example.voiceassistant.ui.*
import com.example.voiceassistant.ui.MessageStatus
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color as ComposeColor
import com.example.voiceassistant.util.FileUtils
import com.example.voiceassistant.network.NetworkUtils
import kotlinx.coroutines.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.URL
import java.net.HttpURLConnection
import android.media.MediaPlayer
import java.util.*

class MainActivity : ComponentActivity() {
    companion object { private const val TAG = "Sage" }

    private lateinit var engine: VoiceRecognitionEngine
    private lateinit var sessionRepo: SessionRepository
    private lateinit var msgRepo: MessageRepository
    private var wsClient: HermesWebSocket? = null
    private var tts: TextToSpeech? = null
    private val messages = mutableStateListOf<MessageItem>()
    private var isRecording by mutableStateOf(false)
    private var connectionStatus by mutableStateOf("初始化...")
    private var connectionState by mutableStateOf(HermesWebSocket.ConnectionState.DISCONNECTED)
    private var liveText by mutableStateOf("")
    private var pendingText by mutableStateOf("")
    private var accumulatedText by mutableStateOf("")
    private var showSettings by mutableStateOf(false)
    private var pendingApproval by mutableStateOf<HermesWebSocket.ApprovalRequired?>(null)
    private var showSearch by mutableStateOf(false)
    private var sessionSearchMode by mutableStateOf(false)  // true=搜当前会话, false=全局
    private var showDashboard by mutableStateOf(false)
    private var showSessionList by mutableStateOf(true)  // 启动时显示会话列表
    private var dashboardStats by mutableStateOf(MessageRepository.DashboardStats(0, 0, 0, 0))
    private var pendingShareText by mutableStateOf<String?>(null)
    private var pendingShareImageUri by mutableStateOf<Uri?>(null)
    private var isThinking by mutableStateOf(false)
    private var pendingCameraImageUri by mutableStateOf<Uri?>(null)
    // ── P2: 剪贴板感知 ──
    private var clipboardText by mutableStateOf<String?>(null)
    private var fillInputText by mutableStateOf<String?>(null)
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var lastPromptedText: String? = null
    private var copyingInternally = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wsJob: Job? = null
    private var currentSession by mutableStateOf<SessionEntity?>(null)
    private val sessionList = mutableStateListOf<SessionInfo>()
    private val archivedSessionList = mutableStateListOf<SessionInfo>()
    // ── Profile ──
    private var activeProfile by mutableStateOf("work")

    // ── 助手模式 ──
    private var assistantMode by mutableStateOf(AssistantMode.NORMAL)

    // ── 技能 chips ──
    private var skillChips by mutableStateOf<List<SkillChip>>(emptyList())
    private var selectedSkills by mutableStateOf<Set<String>>(emptySet())
    private var ttsEnabled by mutableStateOf(false)

    // ── 分页 ──
    private var hasMoreOlder by mutableStateOf(false)
    private var isLoadingMore by mutableStateOf(false)
    private var totalMsgCount by mutableIntStateOf(0)
    private val PAGE_SIZE = 50

    private var lastTrainingSampleId: String? = null  // uploaded sample ID for PATCH update after editing
    private var pendingAudioPath: String? = null  // WAV path waiting to be uploaded on send

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) initAll() else connectionStatus = "需要权限"
    }
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileUpload(it) }
    }
    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleImagePick(it) }
    }
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) pendingCameraImageUri?.let { handleCameraPhoto(it) }
    }

    // ── P1: 相机权限请求 ──
    private val cameraPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { pendingTakePhoto = true } else toast("需要相机权限才能拍照")
    }
    private var pendingTakePhoto = false

    // ── 备份导入文件选择器 ──
    private val backupImportLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleBackupImport(it) }
    }

    // ── 会话导入文件选择器 ──
    private val sessionImportLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleSessionImport(it) }
    }

    // ── Share intent ──────────────────────────────

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleShareIntent(intent) }

    // P2: 从后台恢复时检查剪贴板
    override fun onResume() {
        super.onResume()
        // 切回前台时静默重连
        if (wsClient == null || connectionStatus.contains("失败") || connectionStatus.contains("重连")) {
            reconnectWs()
        }
        // 权限通过后延迟启动相机
        if (pendingTakePhoto) {
            pendingTakePhoto = false
            launchCamera()
        }
        // 剪贴板检查
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount > 0 && clipboardText == null) {
            val text = clip.getItemAt(0).text?.toString()
            if (!text.isNullOrBlank() && text.length in 5..5000 && text != lastPromptedText && text != pendingShareText) {
                lastPromptedText = text
                clipboardText = text
            }
        }
    }
    private fun handleShareIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        when {
            (intent.type ?: "").startsWith("text/") -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                pendingShareText = if (subject != null) "$subject\n\n$text" else text
            }
            (intent.type ?: "").startsWith("image/") -> {
                pendingShareImageUri = intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        }
    }

    private fun processPendingShare() {
        pendingShareText?.let { text ->
            pendingShareText = null
            val display = "[分享] " + if (text.length > 200) text.take(200) + "…" else text
            val item = MessageItem.ChatMsg(ChatMessage(display, true))
            messages.add(item); saveMessage(item); wsClient?.sendMessage(text)
        }
        pendingShareImageUri?.let { uri ->
            pendingShareImageUri = null
            val file = FileUtils.copyUriToCache(this, uri, "shared_image.jpg")
            if (file != null) {
                val item = MessageItem.ChatMsg(ChatMessage("[分享了一张图片]", true,
                    listOf(HermesWebSocket.Attachment(file.name, file.toURI().toString(), file.length(), "image/jpeg"))))
                messages.add(item); saveMessage(item); wsClient?.sendMessage("[用户分享了一张图片]")
            }
        }
    }

    // ── File upload ───────────────────────────────

    private fun handleFileUpload(uri: Uri) {
        val name = FileUtils.getFileName(this, uri) ?: "file"
        val file = FileUtils.copyUriToCache(this, uri, name) ?: return
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        val size = file.length()
        val label = if (mime.startsWith("image/")) "🖼️" else "📎"
        val msg = if (mime.startsWith("image/")) "[上传了图片: $name]" else "[上传了文件: $name (${FileUtils.formatSize(size)})]"
        val item = MessageItem.ChatMsg(ChatMessage(msg, true,
            listOf(HermesWebSocket.Attachment(name, file.absolutePath, size, mime))))
        messages.add(item); saveMessage(item); wsClient?.sendMessage(msg)
    }

    private fun handleImagePick(uri: Uri) {
        val file = FileUtils.copyUriToCache(this, uri, "picked.jpg") ?: return
        val mime = contentResolver.getType(uri) ?: "image/jpeg"

        // Show sending UI immediately
        val item = MessageItem.ChatMsg(ChatMessage("[上传了一张图片]", true,
            listOf(HermesWebSocket.Attachment(file.name, file.toURI().toString(), file.length(), mime))))
        messages.add(item); saveMessage(item)

        // Compress + upload via HTTP in background
        scope.launch(Dispatchers.IO) {
            try {
                // Step 1: Compress image (resize max 1024px, JPEG quality 70%)
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                val maxDim = 1024
                val scale = if (opts.outWidth > opts.outHeight) opts.outWidth / maxDim else opts.outHeight / maxDim
                val sampleSize = if (scale < 1) 1 else scale.coerceAtMost(8)
                val loadOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                var bitmap = BitmapFactory.decodeFile(file.absolutePath, loadOpts)
                if (bitmap != null && (bitmap.width > maxDim || bitmap.height > maxDim)) {
                    val ratio = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
                    val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
                    if (scaled != bitmap) bitmap.recycle()
                    bitmap = scaled
                }
                val baos = java.io.ByteArrayOutputStream()
                bitmap?.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                bitmap?.recycle()
                val compressed = baos.toByteArray()
                Log.d(TAG, "Image compressed: ${file.length()} → ${compressed.size} bytes")

                // Step 2: Upload via HTTP multipart
                val httpUrl = AppSettings.getHttpUrl(this@MainActivity)
                val result = wsClient?.uploadFile(file.name, compressed, "image/jpeg", httpUrl)
                if (result != null) {
                    Log.d(TAG, "Upload success: $result")
                } else {
                    withContext(Dispatchers.Main) { toast("图片上传失败") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Image upload error", e)
                withContext(Dispatchers.Main) { toast("图片上传失败: ${e.message}") }
            }
        }
    }

    // ── Camera ────────────────────────────────────

    private fun launchCamera() {
        val dir = File(cacheDir, "photos"); dir.mkdirs()
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        pendingCameraImageUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        cameraLauncher.launch(pendingCameraImageUri!!)
    }

    private fun handleCameraPhoto(uri: Uri) {
        pendingCameraImageUri = null
        val file = File(uri.path ?: return)
        val item = MessageItem.ChatMsg(ChatMessage("[拍了一张照片]", true,
            listOf(HermesWebSocket.Attachment(file.name, file.toURI().toString(), file.length(), "image/jpeg"))))
        messages.add(item); saveMessage(item)

        // Compress + upload via HTTP
        scope.launch(Dispatchers.IO) {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                val maxDim = 1024
                val scale = if (opts.outWidth > opts.outHeight) opts.outWidth / maxDim else opts.outHeight / maxDim
                val sampleSize = if (scale < 1) 1 else scale.coerceAtMost(8)
                val loadOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                var bitmap = BitmapFactory.decodeFile(file.absolutePath, loadOpts)
                if (bitmap != null && (bitmap.width > maxDim || bitmap.height > maxDim)) {
                    val ratio = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
                    val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
                    if (scaled != bitmap) bitmap.recycle()
                    bitmap = scaled
                }
                val baos = java.io.ByteArrayOutputStream()
                bitmap?.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                bitmap?.recycle()
                val httpUrl = AppSettings.getHttpUrl(this@MainActivity)
                wsClient?.uploadFile(file.name, baos.toByteArray(), "image/jpeg", httpUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Camera upload error", e)
            }
        }
    }

    // ── Approval ──────────────────────────────────

    private fun resolvePendingApproval(status: ApprovalStatus) {
        val idx = messages.indexOfLast { it is MessageItem.ApprovalItem && it.status == ApprovalStatus.PENDING }
        if (idx >= 0) {
            val item = messages[idx] as MessageItem.ApprovalItem
            messages[idx] = item.copy(status = status)
            currentSession?.let { s -> scope.launch { msgRepo.updateApprovalStatus(s.id, item.approval, status) } }
        }
        pendingApproval = null
    }

    private fun expirePendingApprovals() {
        for (i in messages.indices) {
            val item = messages[i]
            if (item is MessageItem.ApprovalItem && item.status == ApprovalStatus.PENDING)
                messages[i] = item.copy(status = ApprovalStatus.DENIED)
        }
        pendingApproval = null
        currentSession?.let { s -> scope.launch { msgRepo.expireAllPending(s.id) } }
    }

    private fun saveMessage(item: MessageItem) { currentSession?.let { s -> scope.launch { msgRepo.insert(s.id, item) } } }

    // ── Session ───────────────────────────────────

    private fun refreshSessionList() { scope.launch { val (active, archived) = sessionRepo.getSessionsWithPreview(activeProfile); sessionList.clear(); sessionList.addAll(active); archivedSessionList.clear(); archivedSessionList.addAll(archived) } }
    private fun refreshDashboard() { scope.launch { dashboardStats = msgRepo.getDashboardStats(sessionRepo.count()) } }

    private fun switchSession(id: Long) { scope.launch { wsClient?.disconnect(); wsJob?.cancel(); sessionRepo.switchTo(id); val s = sessionRepo.getByProfile(activeProfile).find { it.id == id } ?: return@launch; currentSession = s; loadInitialPage(id); assistantMode = AssistantMode.NORMAL; connectionStatus = "切换中..."; connectWs(s.hermesSessionId); refreshSessionList() } }
    private fun newSession() { scope.launch { wsClient?.disconnect(); wsJob?.cancel(); val count = sessionRepo.getByProfile(activeProfile).size; val s = sessionRepo.create("${AppSettings.getProfileName(activeProfile)} 会话 ${count + 1}", activeProfile); currentSession = s; messages.clear(); connectionStatus = "新建..."; connectWs(s.hermesSessionId); refreshSessionList(); refreshDashboard() } }
    private fun deleteSession(id: Long) { scope.launch { val all = sessionRepo.getByProfile(activeProfile); if (all.size <= 1) return@launch; msgRepo.deleteBySession(id); sessionRepo.delete(id); if (currentSession?.id == id) { val t = all.first { it.id != id }; sessionRepo.switchTo(t.id); currentSession = t; loadInitialPage(t.id); wsClient?.disconnect(); wsJob?.cancel(); connectWs(t.hermesSessionId) }; refreshSessionList(); refreshDashboard() } }
    private fun renameSession(id: Long, name: String) { scope.launch { sessionRepo.rename(id, name); refreshSessionList() } }
    private fun pinSession(id: Long, pinned: Boolean) { scope.launch { sessionRepo.pin(id, pinned); refreshSessionList() } }
    private fun archiveSession(id: Long) { scope.launch { sessionRepo.archive(id, true); refreshSessionList(); if (currentSession?.id == id) { val a = sessionRepo.getByProfile(activeProfile); if (a.isNotEmpty()) switchSession(a.first().id) } } }
    private fun unarchiveSession(id: Long) { scope.launch { sessionRepo.archive(id, false); refreshSessionList(); toast("已取消归档") } }

    // ── P0 actions ────────────────────────────────

    private fun exportCurrentSession() { scope.launch { val n = currentSession?.name ?: "会话"; val ms = messages.toList(); if (ms.isEmpty()) { toast("没有消息可导出"); return@launch }; ExportUtil.exportJson(this@MainActivity, n, ms) } }
    private fun clearCurrentSessionMessages() { scope.launch { currentSession?.let { msgRepo.clearBySession(it.id); messages.clear(); toast("消息已清空") } } }
    private fun jumpToSessionFromSearch(id: Long) { showSearch = false; scope.launch { if (currentSession?.id != id) switchSession(id) } }

    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    // ── lifecycle ─────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ttsEnabled = AppSettings.isTtsEnabled(this)
        val db = AppDatabase.getInstance(this)
        sessionRepo = SessionRepository(db.sessionDao(), db.messageDao())
        msgRepo = MessageRepository(db.messageDao())
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.CHINESE }
        engine = VoiceRecognitionEngine(this)
        handleShareIntent(intent)

        // 通知渠道
        createNotificationChannel()

        // P2: 剪贴板监听
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            val clip = cm.primaryClip ?: return@OnPrimaryClipChangedListener
            if (clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()
                Log.d(TAG, "clipboard: ${text?.take(50)}...")
                if (!text.isNullOrBlank() && text.length in 5..5000) {
                    clipboardText = text
                }
            }
        }
        cm.addPrimaryClipChangedListener(clipboardListener!!)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
            val profileName = AppSettings.getProfileName(activeProfile)

            // ── 导入确认对话框 ──
            if (showImportConfirm) {
                AlertDialog(
                    onDismissRequest = { showImportConfirm = false },
                    title = { Text("⚠️ 恢复数据", color = ComposeColor.White) },
                    text = { Text("将覆盖当前所有数据（会话、消息、设置），此操作不可撤销。确定继续？", color = ComposeColor(0xFF94A3B8)) },
                    confirmButton = {
                        TextButton(onClick = {
                            showImportConfirm = false
                            backupImportLauncher.launch("*/*")
                        }) { Text("确定", color = ComposeColor(0xFFEF4444)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showImportConfirm = false }) { Text("取消", color = ComposeColor(0xFF93C5FD)) }
                    },
                    containerColor = ComposeColor(0xFF1E293B)
                )
            }

            when {
                showSessionList -> SessionListScreen(
                    activeSessions = sessionList,
                    archivedSessions = archivedSessionList,
                    onSessionClick = { id -> showSessionList = false; if (currentSession?.id != id) switchSession(id) },
                    onNewSession = { showSessionList = false; newSession() },
                    onPin = { id, pinned -> pinSession(id, pinned) },
                    onArchive = { id -> archiveSession(id) },
                    onUnarchive = { id -> unarchiveSession(id) },
                    onRename = { id, name -> renameSession(id, name) },
                    onDelete = { id -> deleteSession(id) },
                    onSearch = { showSessionList = false; sessionSearchMode = false; showSearch = true },
                    onSettings = { showSessionList = false; showSettings = true },
                    connectionState = connectionState
                )
                showSearch -> SearchScreen(
                    { showSearch = false; showSessionList = true },
                    { jumpToSessionFromSearch(it) },
                    if (sessionSearchMode && currentSession != null) {
                        { q -> msgRepo.searchBySession(q, currentSession!!.id!!) }
                    } else {
                        { q -> msgRepo.search(q) }
                    },
                    sessionScoped = sessionSearchMode
                )
                showDashboard -> DashboardScreen({ showDashboard = false; showSessionList = true }, dashboardStats)
                showSettings -> SettingsScreen(
                    { showSettings = false; showSessionList = true; reconnectWs() },
                    { showSettings = false; refreshDashboard(); showDashboard = true },
                    onExportBackup = { exportBackup() },
                    onImportBackup = { importBackup() },
                    capabilities = skillChips.map { CapabilityInfo(it.name, "📦", it.description) },
                    selectedSkills = selectedSkills,
                    onToggleSkill = { name -> selectedSkills = if (name in selectedSkills) selectedSkills - name else selectedSkills + name },
                    currentProfile = activeProfile,
                    onSwitchProfile = { switchProfile(it) },
                    ttsEnabled = ttsEnabled,
                    onToggleTts = { ttsEnabled = it; AppSettings.setTtsEnabled(this@MainActivity, it) }
                )
                else -> {
                    LaunchedEffect(Unit) { processPendingShare() }
                    // P2: 剪贴板感知对话框
                    clipboardText?.let { text ->
                        AlertDialog(
                            onDismissRequest = { clipboardText = null },
                            title = { Text("📋 剪贴板", color = ComposeColor.White) },
                            text = { Text(text.take(120) + if (text.length > 120) "…" else "", color = ComposeColor(0xFF94A3B8)) },
                            confirmButton = { TextButton(onClick = { fillInputText = text; clipboardText = null }) { Text("填到输入框", color = ComposeColor(0xFF4ADE80)) } },
                            dismissButton = { TextButton(onClick = { clipboardText = null }) { Text("忽略", color = ComposeColor(0xFF64748B)) } },
                            containerColor = ComposeColor(0xFF1E293B)
                        )
                    }
                    MainScreen(messages, { sendMessage(it) }, isRecording, { startRecording() }, { stopRecording() },
                        connectionStatus, connectionState, if (isRecording) accumulatedText else pendingText,
                        { u, n -> NetworkUtils.downloadFile(this@MainActivity, AppSettings.getHttpUrl(this@MainActivity), u, n) },
                        pendingApproval != null,
                        { c -> wsClient?.sendApproval(true, c); resolvePendingApproval(ApprovalStatus.APPROVED) },
                        { wsClient?.sendApproval(false); resolvePendingApproval(ApprovalStatus.DENIED) },
                        { showSettings = true }, profileName, sessionList,
                        { switchSession(it) }, { newSession() }, { deleteSession(it) },
                        { sessionSearchMode = true; showSearch = true }, onExport = { exportCurrentSession() },
                        { clearCurrentSessionMessages() }, { id, n -> renameSession(id, n) },
                        { filePickerLauncher.launch("*/*") },
                        { imagePickerLauncher.launch("image/*") },
                        {
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                                launchCamera()
                            else cameraPermLauncher.launch(Manifest.permission.CAMERA)
                        },
                        onPinSession = { id, pinned -> pinSession(id, pinned) },
                        onArchiveSession = { id -> archiveSession(id) },
                        onRetrySend = { retrySendMessage(it) },
                        onImportSession = { importSession() },
                        assistantMode = assistantMode,
                        onModeChange = { assistantMode = it },
                        onClarifyResponse = { id, text ->
                            val idx = messages.indexOfLast { it is MessageItem.ClarifyItem && it.prompt.clarifyId == id }
                            if (idx >= 0) {
                                val item = messages[idx] as MessageItem.ClarifyItem
                                messages[idx] = item.copy(selectedChoice = text)
                            }
                            wsClient?.sendClarifyResponse(id, text)
                        },
                        skillChips = skillChips.map { it.name },
                        selectedSkills = selectedSkills,
                        onToggleSkill = { name -> selectedSkills = if (name in selectedSkills) selectedSkills - name else selectedSkills + name },
                        archivedSessions = archivedSessionList,
                        onUnarchiveSession = { id -> unarchiveSession(id) },
                        onBack = { showSessionList = true },
                        isThinking = isThinking,
                        onReconnect = { reconnectWs() },
                        hasMoreOlder = hasMoreOlder,
                        isLoadingMore = isLoadingMore,
                        onLoadMore = { loadMoreMessages() },
                        onResetRecording = ::resetRecording,
                        onEditTranscript = { corrected ->
                            pendingText = corrected
                            accumulatedText = corrected
                        }
                    )
                }
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            initAll() else permLauncher.launch(Manifest.permission.RECORD_AUDIO)

        // 通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    }

    private fun initAll() { connectionStatus = "初始化..."; scope.launch { try { activeProfile = AppSettings.getActiveProfile(this@MainActivity); currentSession = sessionRepo.ensureActive(activeProfile); refreshSessionList(); refreshDashboard(); loadInitialPage(currentSession!!.id); engine.onStatus = { connectionStatus = it }; engine.onAsrResult = { liveText = it; accumulatedText = if (accumulatedText.isEmpty()) it else "$accumulatedText $it" }; engine.initialize(); connectionStatus = "连接中..."; connectWs(currentSession!!.hermesSessionId) } catch (e: Exception) { Log.e(TAG, "init", e); connectionStatus = "初始化失败: ${e.message}" } } }

    private fun connectWs(sid: String = "") {
        wsJob?.cancel(); wsClient?.disconnect()
        val url = AppSettings.getWsUrl(this); val id = sid.ifEmpty { UUID.randomUUID().toString() }
        // 保存活跃会话 ID 供轮询用
        getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().putString("active_session_id", id).apply()
        val c = HermesWebSocket(url, id); wsClient = c; wsJob = scope.launch { launch { for (m in c.assistantChannel) {
    // 检测 tool_call
    Log.d("Sage", "recv: " + m.text.take(80)); val toolCall = PhoneTools.extractToolCall(m.text)
    if (toolCall != null) {
        val cleanText = m.text.replace(Regex("```tool_call[^`]*```"), "").trim()
        // 显示清理后的回复
        if (cleanText.isNotBlank()) {
            val item = MessageItem.ChatMsg(ChatMessage(cleanText, false))
            messages.add(item); saveMessage(item)
        }
        // 执行工具并注入结果
        val result = PhoneTools.execute(this@MainActivity, toolCall)
        val resultItem = MessageItem.ChatMsg(ChatMessage("[工具: ${toolCall.name}] $result", true))
        messages.add(resultItem); saveMessage(resultItem)
        c.sendMessage("[工具结果: ${toolCall.name}] $result")
    } else {
        val a = m.attachments.map { HermesWebSocket.Attachment(it.name, it.url, it.size, it.mime) }
        val item = MessageItem.ChatMsg(ChatMessage(m.text, false, a))
        isThinking = false
        messages.add(item); saveMessage(item)
        PollWorker.setLastMsgId(this@MainActivity, PollWorker.getLastMsgId(this@MainActivity) + 1)
        // TTS auto-speak disabled — Peft to cloud CosyVoice TTS via Hermes
        if (ttsEnabled && m.text.isNotBlank()) playTts(m.text)
    }
} }; launch { for (s in c.statusChannel) { connectionStatus = s } }; launch { for (state in c.connectionStateChannel) { connectionState = state } }; launch { for (tp in c.toolChannel) { connectionStatus = if (tp.label.isNotBlank()) tp.label else "执行: ${tp.tool}"; messages.add(MessageItem.ToolItem(tp)) } }; launch { for (ap in c.approvalChannel) { pendingApproval = ap; connectionStatus = "等待确认"; messages.add(MessageItem.ApprovalItem(ap)); saveMessage(messages.last()) } }; launch { for (r in c.approvalResolvedChannel) { resolvePendingApproval(if (r.approved) ApprovalStatus.APPROVED else ApprovalStatus.DENIED); connectionStatus = if (r.approved) "已批准" else "已拒绝" } }; launch { for (st in c.sessionStateChannel) { when (st.state) { "thinking" -> { isThinking = true; connectionStatus = "思考中..." }; "awaiting_clarify" -> connectionStatus = "等待回复"; "ready" -> { isThinking = false; connectionStatus = "就绪" } } } }; launch { for (cl in c.clarifyChannel) { connectionStatus = "等待回复"; messages.add(MessageItem.ClarifyItem(cl)); saveMessage(messages.last()) } }; launch { for (cr in c.clarifyResolvedChannel) { val idx = messages.indexOfLast { it is MessageItem.ClarifyItem && it.prompt.clarifyId == cr.clarifyId }; if (idx >= 0) { val item = messages[idx] as MessageItem.ClarifyItem; messages[idx] = item.copy(status = ClarifyStatus.RESOLVED) }; connectionStatus = "就绪" } }; launch { for (stp in c.stepChannel) { connectionStatus = "步骤: ${stp.title}"; val stepInfos = stp.steps.map { s -> StepInfo(s.label, when(s.status) { "done" -> StepStatus.DONE; "running" -> StepStatus.RUNNING; "error" -> StepStatus.ERROR; else -> StepStatus.WAITING }) }; messages.add(MessageItem.StepItem(stp.title, stepInfos)) } }; launch { for (sg in c.suggestionChannel) { messages.add(MessageItem.SuggestionItem(sg.title, sg.content)) } }; launch { for (ec in c.errorCardChannel) { connectionStatus = "错误: ${ec.error.take(15)}"; messages.add(MessageItem.ErrorItem(ec.error, ec.retryable)) } }; launch { for (e in c.errorChannel) { connectionStatus = "错误: ${e.take(15)}"; messages.add(MessageItem.ChatMsg(ChatMessage("⚠️ 错误: $e", false))) } }; c.connect(); connectionStatus = "就绪"
        PollWorker.enqueue(this@MainActivity); loadSkills() } }
    private fun reconnectWs() { scope.launch { connectionStatus = "重连中..."; connectWs(currentSession?.hermesSessionId ?: UUID.randomUUID().toString()) } }

    private fun playTts(text: String) {
        val httpUrl = AppSettings.getHttpUrl(this@MainActivity)
        scope.launch(Dispatchers.IO) {
            try {
                val url = URL("$httpUrl/v1/tts")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 30000
                conn.readTimeout = 60000
                val body = """{"text":${jsonString(text)}}"""
                conn.outputStream.use { it.write(body.toByteArray()) }
                if (conn.responseCode != 200) return@launch
                val audioFile = File(cacheDir, "tts_${System.currentTimeMillis()}.wav")
                conn.inputStream.use { input -> audioFile.outputStream().use { input.copyTo(it) } }
                withContext(Dispatchers.Main) {
                    val mp = MediaPlayer()
                    mp.setDataSource(audioFile.absolutePath)
                    mp.prepare()
                    mp.start()
                    mp.setOnCompletionListener { it.release(); audioFile.delete() }
                }
            } catch (_: Exception) { }
        }
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return sb.append("\"").toString()
    }

    private fun loadSkills() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                skillChips = NetworkUtils.loadCapabilities(this@MainActivity, activeProfile)
                delay(300_000) // poll every 5min
            }
        }
    }

    private fun loadInitialPage(sessionId: Long) {
        scope.launch(Dispatchers.IO) {
            totalMsgCount = msgRepo.countBySession(sessionId)
            val offset = maxOf(0, totalMsgCount - PAGE_SIZE)
            val page = msgRepo.loadBySessionPaged(sessionId, PAGE_SIZE, offset)
            hasMoreOlder = offset > 0
            isLoadingMore = false
            withContext(Dispatchers.Main) {
                messages.clear()
                messages.addAll(page)
            }
        }
    }

    fun loadMoreMessages() {
        if (isLoadingMore || !hasMoreOlder) return
        val sid = currentSession?.id ?: return
        isLoadingMore = true
        scope.launch(Dispatchers.IO) {
            val currentCount = totalMsgCount - messages.size // how many we haven't loaded yet
            val offset = maxOf(0, currentCount - PAGE_SIZE)
            val page = msgRepo.loadBySessionPaged(sid, PAGE_SIZE, offset)
            hasMoreOlder = offset > 0
            isLoadingMore = false
            withContext(Dispatchers.Main) {
                messages.addAll(0, page)
            }
        }
    }

    private fun switchProfile(newProfile: String) {
        if (newProfile == activeProfile) return
        scope.launch {
            activeProfile = newProfile
            AppSettings.setActiveProfile(this@MainActivity, newProfile)
            wsClient?.disconnect(); wsJob?.cancel()
            messages.clear()
            connectionStatus = "切换 Profile..."
            assistantMode = AssistantMode.NORMAL
            // 加载新 profile 的会话
            currentSession = sessionRepo.ensureActive(newProfile)
            refreshSessionList(); refreshDashboard()
            loadInitialPage(currentSession!!.id)
            connectWs(currentSession!!.hermesSessionId)
            loadSkills()
        }
    }
    private fun startRecording() { liveText = ""; accumulatedText = ""; pendingText = ""; lastTrainingSampleId = null; pendingAudioPath = null; engine.startListening(); isRecording = true; connectionStatus = "录音中" }
    private fun stopRecording() {
        val wavPath = engine.stopListening()
        isRecording = false
        pendingText = accumulatedText
        connectionStatus = "就绪"
        pendingAudioPath = wavPath  // defer upload to send time
    }
    private fun resetRecording() {
        engine.stopListening()
        liveText = ""; accumulatedText = ""; pendingText = ""; lastTrainingSampleId = null; pendingAudioPath = null
        engine.startListening()
        // isRecording stays true, connectionStatus stays "录音中"
    }
    private suspend fun uploadTrainingSample(wavPath: String, text: String) {
        try {
            val rawUrl = AppSettings.getHttpUrl(this@MainActivity)
            // Normalize: strip ws:// or wss:// if user accidentally saved WS URL in HTTP field
            val baseUrl = rawUrl.replace(Regex("^ws://"), "http://").replace(Regex("^wss://"), "https://")
            val url = java.net.URL("$baseUrl/v1/training")
            val boundary = "Boundary-${System.currentTimeMillis()}"
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val wavFile = java.io.File(wavPath)
            val body = java.io.ByteArrayOutputStream()
            val lineEnd = "\r\n"
            val twoHyphens = "--"

            // audio part
            body.write("$twoHyphens$boundary$lineEnd".toByteArray())
            body.write("Content-Disposition: form-data; name=\"audio\"; filename=\"sample.wav\"$lineEnd".toByteArray())
            body.write("Content-Type: audio/wav$lineEnd$lineEnd".toByteArray())
            body.write(wavFile.readBytes())
            body.write(lineEnd.toByteArray())

            // text part
            body.write("$twoHyphens$boundary$lineEnd".toByteArray())
            body.write("Content-Disposition: form-data; name=\"text\"$lineEnd$lineEnd".toByteArray())
            body.write(text.toByteArray(Charsets.UTF_8))
            body.write(lineEnd.toByteArray())

            // session_id part
            body.write("$twoHyphens$boundary$lineEnd".toByteArray())
            body.write("Content-Disposition: form-data; name=\"session_id\"$lineEnd$lineEnd".toByteArray())
            body.write((currentSession?.hermesSessionId ?: "").toByteArray(Charsets.UTF_8))
            body.write(lineEnd.toByteArray())

            body.write("$twoHyphens$boundary$twoHyphens$lineEnd".toByteArray())

            conn.outputStream.use { it.write(body.toByteArray()) }
            val response = conn.inputStream.bufferedReader().readText()
            val json = org.json.JSONObject(response)
            if (json.optBoolean("ok", false)) {
                lastTrainingSampleId = json.optString("id", null)
                Log.i(TAG, "Training sample uploaded: $lastTrainingSampleId")
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Upload training sample failed", e)
        }
    }

    private fun updateTrainingText(correctedText: String) {
        val sampleId = lastTrainingSampleId ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val baseUrl = AppSettings.getHttpUrl(this@MainActivity)
                val url = java.net.URL("$baseUrl/v1/training")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.doOutput = true
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val json = org.json.JSONObject().apply {
                    put("id", sampleId)
                    put("text", correctedText)
                }
                conn.outputStream.use { it.write(json.toString().toByteArray()) }
                conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                Log.i(TAG, "Training text updated: $sampleId")
            } catch (e: Exception) {
                Log.e(TAG, "Update training text failed", e)
            }
        }
    }

    private fun sendMessage(text: String) {
        if (text.isBlank()) return
        expirePendingApprovals()

        // Upload training sample on send (user has verified text is correct)
        val audioPath = pendingAudioPath
        if (audioPath != null) {
            pendingAudioPath = null
            scope.launch(Dispatchers.IO) {
                uploadTrainingSample(audioPath, text)
            }
        }

        val modeInstruction = when (assistantMode) {
            AssistantMode.KNOWLEDGE -> "[技能教学模式] 用户正在教你新技能。请用 skill_manage(action='create') 将以下内容提炼并保存为技能。先和用户确认名称后创建。\n用户说："
            AssistantMode.MEMORY -> "[记忆模式] 用户需要你记住以下信息。请用 memory 工具保存，每条要简洁精准。\n用户说："
            AssistantMode.NORMAL -> ""
        }
        val fullText = buildString {
            if (modeInstruction.isNotEmpty()) append("$modeInstruction$text") else append(text)
            append("\n\n${PhoneTools.buildSystemPrompt()}")
        }

        val currentSkills = selectedSkills.toSet()  // snapshot for retry

        val displayText = if (assistantMode != AssistantMode.NORMAL) "[${assistantMode.label}] $text" else text
        val item = MessageItem.ChatMsg(ChatMessage(displayText, true, status = MessageStatus.SENDING))
        messages.add(item)
        pendingText = ""
        accumulatedText = ""

        scope.launch {
            currentSession?.let { msgRepo.insert(it.id, item.copy(msg = item.msg.copy(status = MessageStatus.SENT))) }

            val ok = wsClient?.sendMessage(fullText, currentSkills) ?: false
            if (ok) {
                val idx = messages.indexOfLast { it is MessageItem.ChatMsg && it.msg.isUser && it.msg.status == MessageStatus.SENDING }
                if (idx >= 0) { val msg = messages[idx] as MessageItem.ChatMsg; messages[idx] = msg.copy(msg = msg.msg.copy(status = MessageStatus.SENT)) }
                return@launch
            }

            // ── 退避重试: 1s, 3s, 8s ──
            val delays = listOf(1000L, 3000L, 8000L)
            for ((i, delayMs) in delays.withIndex()) {
                val attempt = i + 1
                val idx = messages.indexOfLast { it is MessageItem.ChatMsg && it.msg.isUser && it.msg.status in listOf(MessageStatus.SENDING, MessageStatus.RETRYING) }
                if (idx >= 0) {
                    val msg = messages[idx] as MessageItem.ChatMsg
                    messages[idx] = msg.copy(msg = msg.msg.copy(status = MessageStatus.RETRYING, retryAttempt = attempt))
                }
                kotlinx.coroutines.delay(delayMs)
                if (wsClient?.sendMessage(fullText, currentSkills) == true) {
                    val idx2 = messages.indexOfLast { it is MessageItem.ChatMsg && it.msg.isUser && it.msg.status == MessageStatus.RETRYING }
                    if (idx2 >= 0) { val msg = messages[idx2] as MessageItem.ChatMsg; messages[idx2] = msg.copy(msg = msg.msg.copy(status = MessageStatus.SENT)) }
                    return@launch
                }
            }

            // 3 次都失败
            val idx = messages.indexOfLast { it is MessageItem.ChatMsg && it.msg.isUser && it.msg.status == MessageStatus.RETRYING }
            if (idx >= 0) { val msg = messages[idx] as MessageItem.ChatMsg; messages[idx] = msg.copy(msg = msg.msg.copy(status = MessageStatus.FAILED)) }
        }
    }

    private fun retrySendMessage(idx: Int) {
        if (idx < 0 || idx >= messages.size) return
        val item = messages[idx]
        if (item !is MessageItem.ChatMsg || !item.msg.isUser || item.msg.status != MessageStatus.FAILED) return
        messages.removeAt(idx)
        sendMessage(item.msg.text)
    }

    // ── 备份/恢复 ──

    private var showImportConfirm by mutableStateOf(false)
    private var pendingImportUri by mutableStateOf<android.net.Uri?>(null)

    private fun exportBackup() {
        scope.launch {
            connectionStatus = "导出中..."
            BackupUtil.exportBackup(this@MainActivity, sessionRepo, msgRepo)
            connectionStatus = "就绪"
        }
    }

    private fun importBackup() {
        // 弹出确认框
        showImportConfirm = true
    }

    private fun handleBackupImport(uri: android.net.Uri) {
        scope.launch {
            connectionStatus = "恢复中..."
            wsClient?.disconnect()
            wsJob?.cancel()
            messages.clear()

            val result = BackupUtil.importBackup(this@MainActivity, uri, sessionRepo, msgRepo)
            if (result != null) {
                toast("已恢复 ${result.sessionsRestored} 个会话，${result.messagesRestored} 条消息")
                // 重新加载
                activeProfile = AppSettings.getActiveProfile(this@MainActivity)
                currentSession = sessionRepo.ensureActive(activeProfile)
                loadInitialPage(currentSession!!.id)
                refreshSessionList()
                refreshDashboard()
                connectWs(currentSession!!.hermesSessionId)
            }
            connectionStatus = "就绪"
        }
    }

    private fun importSession() {
        sessionImportLauncher.launch("*/*")
    }

    private fun handleSessionImport(uri: android.net.Uri) {
        scope.launch {
            connectionStatus = "导入会话..."
            val imported = ExportUtil.importSessionJson(this@MainActivity, uri)
            if (imported != null) {
                // 创建新会话
                val session = sessionRepo.create(imported.name, activeProfile)
                for (msg in imported.messages) {
                    msgRepo.insertEntity(msg.copy(sessionId = session.id))
                }
                // 切换到新会话
                switchSession(session.id)
                toast("已导入「${imported.name}」(${imported.messages.size} 条消息)")
            }
            connectionStatus = "就绪"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PollWorker.CHANNEL_ID, "新消息", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "新回复通知" }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        PollWorker.cancel(this)
        (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.removePrimaryClipChangedListener(clipboardListener)
        engine.release(); wsClient?.disconnect(); tts?.shutdown(); scope.cancel(); super.onDestroy()
    }
}
