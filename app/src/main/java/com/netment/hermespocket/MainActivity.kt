package com.netment.hermespocket

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.core.content.ContextCompat
import com.netment.hermespocket.controller.*
import com.netment.hermespocket.data.*
import com.netment.hermespocket.network.HermesWebSocket
import com.netment.hermespocket.network.NetworkUtils
import com.netment.hermespocket.service.PollWorker
import com.netment.hermespocket.ui.*
import com.netment.hermespocket.util.FileUtils
import kotlinx.coroutines.*
import java.io.File
import java.util.*

class MainActivity : ComponentActivity() {
    companion object { private const val TAG = "Sage" }

    private lateinit var sessionRepo: SessionRepository
    private lateinit var msgRepo: MessageRepository
    private var tts: TextToSpeech? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Controllers
    private val nav = NavigationController()
    private lateinit var sessionController: SessionController
    private lateinit var chatController: ChatController
    private lateinit var voiceController: VoiceController
    private lateinit var mediaController: MediaController

    // Clipboard
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var lastPromptedText: String? = null

    // Backup
    private var showImportConfirm by mutableStateOf(false)

    // ── launchers ──
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) initAll() else { chatController.connectionStatus = "需要权限" }
    }
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { mediaController.handleFileUpload(it) }
    }
    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { mediaController.handleImagePick(it) }
    }
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) mediaController.pendingCameraImageUri?.let { mediaController.handleCameraPhoto(it) }
    }
    private val cameraPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { mediaController.pendingTakePhoto = true } else toast("需要相机权限才能拍照")
    }
    private val backupImportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let { handleBackupImport(it) }
    }
    private val sessionImportLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleSessionImport(it) }
    }

    // ── lifecycle ──

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); mediaController.handleShareIntent(intent) }

    override fun onResume() {
        super.onResume()
        if (mediaController.pendingTakePhoto) {
            mediaController.pendingTakePhoto = false; mediaController.launchCamera()
            cameraLauncher.launch(mediaController.pendingCameraImageUri!!)
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount > 0 && chatController.clipboardText == null) {
            val t = clip.getItemAt(0).text?.toString()
            if (!t.isNullOrBlank() && t.length in 5..5000 && t != lastPromptedText && t != mediaController.pendingShareText) {
                lastPromptedText = t; chatController.clipboardText = t
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.getInstance(this)
        sessionRepo = SessionRepository(db.sessionDao(), db.messageDao())
        msgRepo = MessageRepository(db.messageDao())
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.CHINESE }

        // Create controllers
        sessionController = SessionController(sessionRepo, msgRepo, this, scope)
        chatController = ChatController(msgRepo, this, scope, tts, sessionController)
        chatController.ttsEnabled = AppSettings.isTtsEnabled(this)
        voiceController = VoiceController(this, chatController)
        mediaController = MediaController(this, scope, chatController)

        mediaController.handleShareIntent(intent)
        createNotificationChannel()

        // Clipboard listener
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            val clip = cm.primaryClip ?: return@OnPrimaryClipChangedListener
            if (clip.itemCount > 0) {
                val t = clip.getItemAt(0).text?.toString()
                if (!t.isNullOrBlank() && t.length in 5..5000) chatController.clipboardText = t
            }
        }
        cm.addPrimaryClipChangedListener(clipboardListener!!)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
            val profileName = AppSettings.getProfileName(sessionController.activeProfile)

            if (showImportConfirm) {
                AlertDialog(
                    onDismissRequest = { showImportConfirm = false },
                    title = { Text("⚠️ 恢复数据", color = ComposeColor.White) },
                    text = { Text("将覆盖当前所有数据（会话、消息、设置），此操作不可撤销。确定继续？", color = ComposeColor(0xFF94A3B8)) },
                    confirmButton = {
                        TextButton(onClick = { showImportConfirm = false; backupImportLauncher.launch(launchBackupImportIntent()) }) {
                            Text("确定", color = ComposeColor(0xFFEF4444)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showImportConfirm = false }) {
                            Text("取消", color = ComposeColor(0xFF93C5FD)) }
                    },
                    containerColor = ComposeColor(0xFF1E293B)
                )
            }

            when {
                nav.showSessionList -> SessionListScreen(
                    activeSessions = sessionController.sessionList,
                    archivedSessions = sessionController.archivedSessionList,
                    onSessionClick = { id ->
                        nav.navigateToChat(); scope.launch {
                            val s = sessionController.switchSessionAsync(id)
                            if (s != null) chatController.onSessionSwitched(s)
                        }
                    },
                    onNewSession = { nav.navigateToChat(); scope.launch { chatController.onSessionSwitched(sessionController.newSession()) } },
                    onPin = { id, pinned -> sessionController.pinSession(id, pinned) },
                    onArchive = { id -> scope.launch { val s = sessionController.archiveSession(id); if (s != null) chatController.onSessionSwitched(s) } },
                    onUnarchive = { id -> sessionController.unarchiveSession(id) },
                    onRename = { id, name -> sessionController.renameSession(id, name) },
                    onDelete = { id -> scope.launch { val s = sessionController.deleteSession(id); if (s != null) chatController.onSessionSwitched(s) } },
                    onSearch = { nav.navigateToSearch() },
                    onSettings = { nav.navigateToSettings() },
                    connectionState = chatController.connectionState
                )
                nav.showSearch -> SearchScreen(
                    { nav.navigateToSessionList() },
                    { id -> nav.navigateToChat(); scope.launch { val s = sessionController.jumpToSessionFromSearch(id); if (s != null) chatController.onSessionSwitched(s) } },
                    if (nav.sessionSearchMode && sessionController.currentSession != null)
                        { q -> msgRepo.searchBySession(q, sessionController.currentSession!!.id!!) }
                    else { q -> msgRepo.search(q) },
                    sessionScoped = nav.sessionSearchMode
                )
                nav.showDashboard -> DashboardScreen({ nav.navigateToSessionList() }, sessionController.dashboardStats)
                nav.showSettings -> SettingsScreen(
                    { nav.navigateToSessionList(); chatController.reconnectWs() },
                    { nav.navigateToDashboard(); sessionController.refreshDashboard() },
                    onExportBackup = { exportBackup() }, onImportBackup = { showImportConfirm = true },
                    capabilities = chatController.skillChips.map { CapabilityInfo(it.name, "📦", it.description) },
                    selectedSkills = chatController.selectedSkills,
                    onToggleSkill = { n -> chatController.selectedSkills = if (n in chatController.selectedSkills) chatController.selectedSkills - n else chatController.selectedSkills + n },
                    currentProfile = sessionController.activeProfile,
                    onSwitchProfile = { p -> scope.launch { val s = sessionController.switchProfile(p); chatController.onProfileSwitched(s) } },
                    ttsEnabled = chatController.ttsEnabled,
                    onToggleTts = { chatController.ttsEnabled = it; AppSettings.setTtsEnabled(this@MainActivity, it) }
                )
                else -> {
                    LaunchedEffect(Unit) { mediaController.processPendingShare() }
                    chatController.clipboardText?.let { text ->
                        AlertDialog(
                            onDismissRequest = { chatController.clipboardText = null },
                            title = { Text("📋 剪贴板", color = ComposeColor.White) },
                            text = { Text(text.take(120) + if (text.length > 120) "…" else "", color = ComposeColor(0xFF94A3B8)) },
                            confirmButton = { TextButton(onClick = { chatController.fillInputText = text; chatController.clipboardText = null }) { Text("填到输入框", color = ComposeColor(0xFF4ADE80)) } },
                            dismissButton = { TextButton(onClick = { chatController.clipboardText = null }) { Text("忽略", color = ComposeColor(0xFF64748B)) } },
                            containerColor = ComposeColor(0xFF1E293B)
                        )
                    }
                    MainScreen(
                        messages = chatController.messages,
                        onSendText = { chatController.sendMessage(it) },
                        isRecording = voiceController.isRecording,
                        onStartRecord = { voiceController.startRecording() },
                        onStopRecord = { voiceController.stopRecording() },
                        connectionStatus = chatController.connectionStatus,
                        connectionState = chatController.connectionState,
                        liveText = if (voiceController.isRecording) chatController.accumulatedText else chatController.pendingText,
                        onDownloadAttachment = { u, n -> NetworkUtils.downloadFile(this@MainActivity, AppSettings.getHttpUrl(this@MainActivity), u, n) },
                        hasPendingApproval = chatController.pendingApproval != null,
                        onApprove = { c -> HermesWebSocket.get()?.sendApproval(true, c); chatController.resolvePendingApproval(ApprovalStatus.APPROVED) },
                        onDeny = { HermesWebSocket.get()?.sendApproval(false); chatController.resolvePendingApproval(ApprovalStatus.DENIED) },
                        onSettings = { nav.navigateToSettings() },
                        currentProfile = profileName,
                        sessions = sessionController.sessionList,
                        onSwitchSession = { id ->
                            nav.navigateToChat(); scope.launch {
                                val s = sessionController.switchSessionAsync(id)
                                if (s != null) chatController.onSessionSwitched(s)
                            }
                        },
                        onNewSession = { nav.navigateToChat(); scope.launch { chatController.onSessionSwitched(sessionController.newSession()) } },
                        onDeleteSession = { id -> scope.launch { val s = sessionController.deleteSession(id); if (s != null) chatController.onSessionSwitched(s) } },
                        onSearch = { nav.navigateToSearch(sessionScoped = true) },
                        onExport = { chatController.exportCurrentSession() },
                        onClearMessages = { chatController.clearCurrentSessionMessages() },
                        onRenameSession = { id, n -> sessionController.renameSession(id, n) },
                        onUploadFile = { filePickerLauncher.launch("*/*") },
                        onPickImage = { imagePickerLauncher.launch("image/*") },
                        onTakePhoto = {
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                mediaController.launchCamera(); cameraLauncher.launch(mediaController.pendingCameraImageUri!!)
                            } else cameraPermLauncher.launch(Manifest.permission.CAMERA)
                        },
                        onPinSession = { id, pinned -> sessionController.pinSession(id, pinned) },
                        onArchiveSession = { id -> scope.launch { val s = sessionController.archiveSession(id); if (s != null) chatController.onSessionSwitched(s) } },
                        onRetrySend = { chatController.retrySendMessage(it) },
                        onImportSession = { importSession() },
                        assistantMode = chatController.assistantMode,
                        onModeChange = { chatController.assistantMode = it },
                        onClarifyResponse = { id, text ->
                            val idx = chatController.findClarifyItem(id)
                            if (idx >= 0) { val item = chatController.messages[idx] as MessageItem.ClarifyItem; chatController.messages[idx] = item.copy(selectedChoice = text) }
                            HermesWebSocket.get()?.sendClarifyResponse(id, text)
                        },
                        skillChips = chatController.skillChips.map { it.name },
                        selectedSkills = chatController.selectedSkills,
                        onToggleSkill = { n -> chatController.selectedSkills = if (n in chatController.selectedSkills) chatController.selectedSkills - n else chatController.selectedSkills + n },
                        archivedSessions = sessionController.archivedSessionList,
                        onUnarchiveSession = { id -> sessionController.unarchiveSession(id) },
                        fillInputText = chatController.fillInputText,
                        onSwitchProfile = { p -> scope.launch { val s = sessionController.switchProfile(p); chatController.onProfileSwitched(s) } },
                        onBack = { nav.navigateToSessionList() },
                        isThinking = chatController.isThinking,
                        onReconnect = { chatController.reconnectWs() },
                        hasMoreOlder = chatController.hasMoreOlder,
                        isLoadingMore = chatController.isLoadingMore,
                        onLoadMore = { chatController.loadMoreMessages() },
                        onResetRecording = { voiceController.resetRecording() },
                        onEditTranscript = { c -> chatController.pendingText = c; chatController.accumulatedText = c }
                    )
                }
            }
        } }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            initAll() else permLauncher.launch(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    private fun initAll() {
        chatController.connectionStatus = "初始化..."
        scope.launch {
            try {
                val s = sessionController.ensureActive()
                sessionController.refreshSessionList(); sessionController.refreshDashboard()
                chatController.onSessionSwitched(s)
                voiceController.setup()
                chatController.initAll()
            } catch (e: Exception) { Log.e(TAG, "init", e); chatController.connectionStatus = "初始化失败: ${e.message}" }
        }
    }

    override fun onDestroy() {
        PollWorker.cancel(this)
        (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.removePrimaryClipChangedListener(clipboardListener)
        voiceController.release(); tts?.shutdown(); scope.cancel(); super.onDestroy()
    }

    // ── backup / restore ──

    private fun exportBackup() {
        scope.launch { chatController.connectionStatus = "导出中..."; BackupUtil.exportBackup(this@MainActivity, sessionRepo, msgRepo); chatController.connectionStatus = "就绪" }
    }

    private fun launchBackupImportIntent(): Intent {
        val backupDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "SageBackup")
        backupDir.mkdirs()
        val initialUri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents/SageBackup")
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri) }
    }

    private fun handleBackupImport(uri: Uri) {
        scope.launch {
            chatController.connectionStatus = "恢复中..."
            val result = BackupUtil.importBackup(this@MainActivity, uri, sessionRepo, msgRepo)
            if (result != null) {
                toast("已恢复 ${result.sessionsRestored} 个会话，${result.messagesRestored} 条消息")
                sessionController.activeProfile = AppSettings.getActiveProfile(this@MainActivity)
                val s = sessionController.ensureActive()
                sessionController.refreshSessionList(); sessionController.refreshDashboard()
                chatController.onSessionSwitched(s); chatController.reconnectWs()
            }
            chatController.connectionStatus = "就绪"
        }
    }

    private fun importSession() { sessionImportLauncher.launch("*/*") }

    private fun handleSessionImport(uri: Uri) {
        scope.launch {
            chatController.connectionStatus = "导入会话..."
            val imported = ExportUtil.importSessionJson(this@MainActivity, uri)
            if (imported != null) {
                val session = sessionRepo.create(imported.name, sessionController.activeProfile)
                for (msg in imported.messages) msgRepo.insertEntity(msg.copy(sessionId = session.id))
                val s = sessionController.switchSessionAsync(session.id)
                if (s != null) chatController.onSessionSwitched(s)
                toast("已导入「${imported.name}」(${imported.messages.size} 条消息)")
            }
            chatController.connectionStatus = "就绪"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(PollWorker.CHANNEL_ID, "新消息", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "新回复通知" }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }

    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}
