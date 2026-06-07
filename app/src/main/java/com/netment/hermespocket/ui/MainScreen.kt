package com.netment.hermespocket.ui

import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netment.hermespocket.network.HermesWebSocket
import dev.jeziellago.compose.markdowntext.MarkdownText

// ── Message Item model ──────────────────────────────

enum class MessageStatus { SENDING, SENT, FAILED, RETRYING }

enum class AssistantMode(val label: String, val emoji: String, val placeholder: String, val color: Color) {
    NORMAL("普通", "💬", "输入消息...", Color(0xFF4ADE80)),
    KNOWLEDGE("技能", "📚", "描述要教给 Hermes 的技能...", Color(0xFF60A5FA)),
    MEMORY("记忆", "🧠", "告诉 Hermes 要记住什么...", Color(0xFFC084FC))
}

data class ChatMessage(
    val text: String, val isUser: Boolean,
    val attachments: List<HermesWebSocket.Attachment> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.SENT,  // non-user msgs default SENT
    val retryAttempt: Int = 0
)

enum class ApprovalStatus { PENDING, APPROVED, DENIED }

enum class ClarifyStatus { PENDING, RESOLVED }

sealed class MessageItem {
    data class ChatMsg(val msg: ChatMessage) : MessageItem()
    data class ApprovalItem(
        val approval: HermesWebSocket.ApprovalRequired,
        val status: ApprovalStatus = ApprovalStatus.PENDING,
        val timestamp: Long = System.currentTimeMillis()
    ) : MessageItem()
    data class ClarifyItem(
        val prompt: HermesWebSocket.ClarifyPrompt,
        val status: ClarifyStatus = ClarifyStatus.PENDING,
        val selectedChoice: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    ) : MessageItem()
    data class FileItem(
        val attachment: HermesWebSocket.Attachment,
        val timestamp: Long = System.currentTimeMillis()
    ) : MessageItem()
    data class StepItem(
        val title: String,
        val steps: List<StepInfo>,
        val timestamp: Long = System.currentTimeMillis()
    ) : MessageItem()
    data class SuggestionItem(
        val title: String,
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : MessageItem()
    data class ErrorItem(
        val error: String,
        val retryable: Boolean = false,
        val timestamp: Long = System.currentTimeMillis()
    ) : MessageItem()
    data class ThinkingItem(
        val timestamp: Long = System.currentTimeMillis()
    ) : MessageItem()
}

data class SessionInfo(
    val id: Long,
    val name: String,
    val isActive: Boolean,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val preview: String = "",       // 最后一条消息预览
    val lastMsgTime: Long = 0,      // 最后消息时间戳
)

// ── MainScreen ──────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    messages: List<MessageItem>,
    onSendText: (String) -> Unit,
    isRecording: Boolean,
    onStartRecord: () -> Unit,
    onStopRecord: () -> Unit,
    connectionStatus: String,
    connectionState: HermesWebSocket.ConnectionState = HermesWebSocket.ConnectionState.DISCONNECTED,
    liveText: String,
    onDownloadAttachment: ((String, String) -> Unit)? = null,
    hasPendingApproval: Boolean = false,
    onApprove: ((String) -> Unit)? = null,
    onDeny: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    currentProfile: String = "",
    sessions: List<SessionInfo> = emptyList(),
    onSwitchSession: ((Long) -> Unit)? = null,
    onNewSession: (() -> Unit)? = null,
    onDeleteSession: ((Long) -> Unit)? = null,
    // ── P0: 新回调 ──
    onSearch: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
    onClearMessages: (() -> Unit)? = null,
    onRenameSession: ((Long, String) -> Unit)? = null,
    // ── P1: 文件/图片/拍照 ──
    onUploadFile: (() -> Unit)? = null,
    onPickImage: (() -> Unit)? = null,
    onTakePhoto: (() -> Unit)? = null,
    // ── 置顶/归档 ──
    onPinSession: ((Long, Boolean) -> Unit)? = null,
    onArchiveSession: ((Long) -> Unit)? = null,
    // ── 已归档会话 ──
    archivedSessions: List<SessionInfo> = emptyList(),
    onUnarchiveSession: ((Long) -> Unit)? = null,
    // ── P2: 剪贴板填到输入框 ──
    fillInputText: String? = null,
    // ── Profile 切换 ──
    onSwitchProfile: ((String) -> Unit)? = null,
    // ── 消息重发 ──
    onRetrySend: ((Int) -> Unit)? = null,
    // ── 会话导入 ──
    onImportSession: (() -> Unit)? = null,
    // ── 模式切换 ──
    assistantMode: AssistantMode = AssistantMode.NORMAL,
    onModeChange: ((AssistantMode) -> Unit)? = null,
    // ── Clarify ──
    onClarifyResponse: ((String, String) -> Unit)? = null,
    // ── 能力 chips ──
    skillChips: List<String> = emptyList(),
    selectedSkills: Set<String> = emptySet(),
    onToggleSkill: ((String) -> Unit)? = null,
    // ── 返回会话列表 ──
    onBack: (() -> Unit)? = null,
    // ── 思考状态 ──
    isThinking: Boolean = false,
    // ── 连接横幅 ──
    onReconnect: (() -> Unit)? = null,
    // ── 分页加载更多 ──
    hasMoreOlder: Boolean = false,
    isLoadingMore: Boolean = false,
    onLoadMore: (() -> Unit)? = null,
    // ── 重录 ──
    onResetRecording: (() -> Unit)? = null,
    // ── 校对 ──
    onEditTranscript: ((String) -> Unit)? = null
) {
    val listState = rememberLazyListState()
    var editedText by remember { mutableStateOf("") }
    var showEditDialog by remember { mutableStateOf(false) }

    // reverseLayout: index 0=最新(底部), index N=最旧(顶部), 自然零闪烁
    // 滚动到顶（index N=最旧消息）自动加载更多
    if (hasMoreOlder && !isLoadingMore) {
        val firstVisible = listState.firstVisibleItemIndex
        val scrollOffset = listState.firstVisibleItemScrollOffset
        LaunchedEffect(firstVisible, scrollOffset) {
            if (firstVisible == messages.lastIndex && scrollOffset == 0) {
                onLoadMore?.invoke()
            }
        }
    }
    var inputText by remember { mutableStateOf("") }
    var showSessionMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showModeMenu by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var renameDialogSession by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var renameText by remember { mutableStateOf("") }

    LaunchedEffect(isRecording) {
        if (!isRecording && liveText.isNotBlank()) inputText = liveText
    }
    // P2: 外部剪贴板内容填到输入框
    LaunchedEffect(fillInputText) {
        if (!fillInputText.isNullOrBlank()) inputText = fillInputText
    }

    val activeSession = sessions.find { it.isActive }

    // ── 清空确认对话框 ──
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空消息", color = Color.White) },
            text = { Text("确定要删除当前会话的全部消息吗？此操作不可撤销。", color = Color(0xFF94A3B8)) },
            confirmButton = {
                TextButton(onClick = {
                    onClearMessages?.invoke()
                    showClearDialog = false
                }) { Text("确定", color = Color(0xFFEF4444)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消", color = Color(0xFF93C5FD)) }
            },
            containerColor = Color(0xFF1E293B)
        )
    }

    // ── 重命名对话框 ──
    renameDialogSession?.let { (id, name) ->
        AlertDialog(
            onDismissRequest = { renameDialogSession = null },
            title = { Text("重命名会话", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF2563EB),
                        unfocusedBorderColor = Color(0xFF334155),
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) onRenameSession?.invoke(id, renameText)
                    renameDialogSession = null
                }) { Text("确定", color = Color(0xFF4ADE80)) }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogSession = null }) { Text("取消", color = Color(0xFF93C5FD)) }
            },
            containerColor = Color(0xFF1E293B)
        )
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, "返回", tint = Color(0xFF94A3B8))
                        }
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = activeSession?.name ?: "有数",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (hasPendingApproval) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "⏳等待确认",
                                color = Color(0xFFF59E0B),
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                        } else if (isThinking) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "思考中...",
                                color = Color(0xFF94A3B8),
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                        } else if (isRecording) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "录音中",
                                color = Color(0xFFF87171),
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A2E), titleContentColor = Color.White
                ),
                actions = {
                    // ── 模式下拉 ──
                    Box {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = assistantMode.color.copy(alpha = 0.2f),
                            modifier = Modifier.clickable { showModeMenu = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${assistantMode.emoji} ${assistantMode.label}", fontSize = 11.sp,
                                    color = assistantMode.color)
                                Icon(Icons.Default.ArrowDropDown, null,
                                    tint = assistantMode.color, modifier = Modifier.size(16.dp))
                            }
                        }
                        DropdownMenu(
                            expanded = showModeMenu,
                            onDismissRequest = { showModeMenu = false }
                        ) {
                            AssistantMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text("${mode.emoji} ${mode.label}", color = mode.color, fontSize = 13.sp) },
                                    onClick = {
                                        showModeMenu = false
                                        if (mode != assistantMode) onModeChange?.invoke(mode)
                                    },
                                    leadingIcon = if (mode == assistantMode) {
                                        { Icon(Icons.Default.Check, null, tint = mode.color, modifier = Modifier.size(16.dp)) }
                                    } else null
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    // ── 更多菜单 ──
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Default.MoreVert, "更多", tint = Color(0xFF94A3B8), modifier = Modifier.size(22.dp))
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            // ── 搜索 ──
                            DropdownMenuItem(
                                text = { Text("🔍 搜索") },
                                onClick = {
                                    showMoreMenu = false
                                    onSearch?.invoke()
                                }
                            )
                            HorizontalDivider(color = Color(0xFF334155))
                            DropdownMenuItem(
                                text = { Text("📤 导出会话") },
                                onClick = {
                                    showMoreMenu = false
                                    onExport?.invoke()
                                }
                            )
                            if (onImportSession != null) {
                                DropdownMenuItem(
                                    text = { Text("📤 导入会话") },
                                    onClick = {
                                        showMoreMenu = false
                                        onImportSession?.invoke()
                                    }
                                )
                                HorizontalDivider(color = Color(0xFF334155))
                            }
                            activeSession?.let { sess ->
                                DropdownMenuItem(
                                    text = { Text(if (sessions.find { it.id == sess.id }?.isPinned == true) "📌 取消置顶" else "📌 置顶") },
                                    onClick = {
                                        showMoreMenu = false
                                        onPinSession?.invoke(sess.id, !(sessions.find { it.id == sess.id }?.isPinned ?: false))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("✏️ 重命名") },
                                    onClick = {
                                        showMoreMenu = false
                                        renameText = sess.name
                                        renameDialogSession = Pair(sess.id, sess.name)
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("🗑️ 清空消息", color = Color(0xFFF59E0B)) },
                                onClick = {
                                    showMoreMenu = false
                                    showClearDialog = true
                                }
                            )
                            activeSession?.let { sess ->
                                DropdownMenuItem(
                                    text = { Text("📦 归档", color = Color(0xFF64748B)) },
                                    onClick = {
                                        showMoreMenu = false
                                        onArchiveSession?.invoke(sess.id)
                                    }
                                )
                            }
                        }
                    }

                    // ── 连接状态指示灯 ──
                    val dotAlpha by animateFloatAsState(
                        targetValue = if (connectionState == HermesWebSocket.ConnectionState.CONNECTING) 0.3f else 1f,
                        animationSpec = if (connectionState == HermesWebSocket.ConnectionState.CONNECTING)
                            infiniteRepeatable(tween(600), RepeatMode.Reverse) else tween(200),
                        label = "dotAlpha"
                    )
                    val dotColor = when (connectionState) {
                        HermesWebSocket.ConnectionState.CONNECTED -> Color(0xFF4ADE80)
                        HermesWebSocket.ConnectionState.CONNECTING -> Color(0xFFF59E0B)
                        HermesWebSocket.ConnectionState.DISCONNECTED -> Color(0xFFEF4444)
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(dotColor.copy(alpha = dotAlpha))
                    )
                    Spacer(Modifier.width(6.dp))
                }
            )
        },
        bottomBar = {
            Surface(color = Color(0xFF1A1A2E)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    OutlinedTextField(
                        value = inputText, onValueChange = { inputText = it },
                        placeholder = { Text(if (isRecording) "说话中..." else assistantMode.placeholder, color = Color(0xFF64748B)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp, max = 160.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF2563EB), unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF1E293B), unfocusedContainerColor = Color(0xFF1E293B)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        minLines = 1, maxLines = 5
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (onUploadFile != null) {
                                    FilledIconButton(
                                        onClick = onUploadFile,
                                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF374151)),
                                        modifier = Modifier.size(40.dp)
                                    ) { Icon(Icons.Default.AttachFile, null, tint = Color(0xFF94A3B8), modifier = Modifier.size(20.dp)) }
                                }
                                if (onPickImage != null) {
                                    FilledIconButton(
                                        onClick = onPickImage,
                                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF374151)),
                                        modifier = Modifier.size(40.dp)
                                    ) { Icon(Icons.Default.Image, null, tint = Color(0xFF94A3B8), modifier = Modifier.size(20.dp)) }
                                }
                                if (onTakePhoto != null) {
                                    FilledIconButton(
                                        onClick = onTakePhoto,
                                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF374151)),
                                        modifier = Modifier.size(40.dp)
                                    ) { Icon(Icons.Default.CameraAlt, null, tint = Color(0xFF94A3B8), modifier = Modifier.size(20.dp)) }
                                }
                            }
                        }
                        FilledIconButton(
                            onClick = { if (isRecording) onStopRecord() else onStartRecord() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (isRecording) Color(0xFFEF4444) else Color(0xFF374151)
                            ),
                            shape = CircleShape, modifier = Modifier.size(56.dp)
                        ) {
                            Icon(Icons.Default.Mic, null,
                                tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            FilledIconButton(
                                onClick = {
                                    if (isRecording) {
                                        onStopRecord()
                                        if (inputText.isNotBlank()) {
                                            onSendText(inputText); inputText = ""
                                        } else if (liveText.isNotBlank()) {
                                            onSendText(liveText)
                                        }
                                    } else if (inputText.isNotBlank()) {
                                        onSendText(inputText); inputText = ""
                                    }
                                },
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF2563EB))
                            ) { Icon(Icons.Default.Send, null, tint = Color.White) }
                        }
                    }
                }
            }
        },
        containerColor = Color(0xFF0F0F1A)
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ── 连接横幅（微信风格） ──
            val showBanner = connectionStatus.contains("重连") ||
                    connectionStatus.contains("失败") ||
                    connectionStatus.contains("未连接") ||
                    (connectionState == HermesWebSocket.ConnectionState.DISCONNECTED && connectionStatus.isNotEmpty())
            val isReconnecting = connectionStatus.contains("重连")
            AnimatedVisibility(
                visible = showBanner,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    color = if (isReconnecting) Color(0xFF78350F) else Color(0xFF7F1D1D),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (isReconnecting) "\u26A0\uFE0F" else "\u274C",
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = connectionStatus.take(30),
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onReconnect?.invoke() }) {
                            Text("重试", color = Color(0xFF60A5FA), fontSize = 13.sp)
                        }
                    }
                }
            }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = {
                    itemsIndexed(messages) { idx, item ->
                        when (item) {
                            is MessageItem.ChatMsg -> {
                                MessageBubble(item.msg, onDownloadAttachment, onCopyToInput = { inputText = it }, onRetrySend = { onRetrySend?.invoke(idx) })
                            }
                            is MessageItem.ApprovalItem -> {
                                ApprovalCard(
                                    approval = item.approval, status = item.status,
                                    onApprove = if (item.status == ApprovalStatus.PENDING) onApprove else null,
                                    onDeny = if (item.status == ApprovalStatus.PENDING) onDeny else null
                                )
                            }
                            is MessageItem.ClarifyItem -> {
                                ClarifyCard(
                                    prompt = item.prompt, status = item.status,
                                    selectedChoice = item.selectedChoice,
                                    onChoose = if (item.status == ClarifyStatus.PENDING)
                                        { text -> onClarifyResponse?.invoke(item.prompt.clarifyId, text) } else null
                                )
                            }
                            is MessageItem.FileItem -> FilePreviewCard(
                                name = item.attachment.name, url = item.attachment.url,
                                size = item.attachment.size, mime = item.attachment.mime,
                                onDownload = onDownloadAttachment
                            )
                            is MessageItem.StepItem -> StepCard(item.steps, item.title)
                            is MessageItem.SuggestionItem -> SuggestionCard(
                                item.title, item.content,
                                onAccept = { onSendText?.invoke("采用建议: ${item.title}") }
                            )
                            is MessageItem.ErrorItem -> ErrorRetryCard(
                                error = item.error,
                                onRetry = if (item.retryable) {{ /* retry logic */ }} else null,
                                onDismiss = null
                            )
                            is MessageItem.ThinkingItem -> { /* no-op: now shown in top bar subtitle */ }
                        }
                    }
                    // 视觉顶部（旧消息之上）：加载更多
                    if (isLoadingMore) {
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF94A3B8)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("载入中...", color = Color(0xFF94A3B8), fontSize = 12.sp)
                            }
                        }
                    } else if (hasMoreOlder) {
                        item {
                            Row(Modifier.fillMaxWidth().clickable { onLoadMore?.invoke() }.padding(8.dp),
                                horizontalArrangement = Arrangement.Center) {
                                Text("📜 查看更早的消息", color = Color(0xFF60A5FA), fontSize = 12.sp)
                            }
                        }
                    }
                }
            )

            if (liveText.isNotBlank()) {
                val scrollState = rememberScrollState()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        "\"${if (!isRecording && editedText.isNotBlank()) editedText else liveText}\"",
                        modifier = Modifier.weight(1f)
                            .heightIn(max = 160.dp)
                            .verticalScroll(scrollState)
                            .then(Modifier.clickable {
                                editedText = ""
                                showEditDialog = true
                            }),
                        color = Color(0xFFFACC15), fontSize = 14.sp
                    )
                    TextButton(
                        onClick = { onResetRecording?.invoke() },
                        modifier = Modifier.padding(start = 4.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF87171))
                    ) {
                        Text("🗑 重说", fontSize = 12.sp)
                    }
                }
            }

            // ── 校对弹窗 ──
            if (showEditDialog) {
                AlertDialog(
                    onDismissRequest = { showEditDialog = false },
                    title = { Text("📝 校对转写文字", color = Color.White, fontWeight = FontWeight.Bold) },
                    text = {
                        TextField(
                            value = editedText.ifBlank { liveText },
                            onValueChange = { editedText = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF1E293B),
                                unfocusedContainerColor = Color(0xFF1E293B),
                                focusedIndicatorColor = Color(0xFF4ADE80),
                                cursorColor = Color(0xFF4ADE80)
                            )
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val final = editedText.ifBlank { liveText }
                            onEditTranscript?.invoke(final)
                            inputText = final
                            editedText = ""
                            showEditDialog = false
                        }) {
                            Text("✅ 确认", color = Color(0xFF4ADE80))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { editedText = ""; showEditDialog = false }) {
                            Text("取消", color = Color(0xFF64748B))
                        }
                    },
                    containerColor = Color(0xFF0F172A)
                )
            }

            // ── 能力 chips 横滚条 ──
            if (skillChips.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(skillChips) { name ->
                        val selected = name in selectedSkills
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (selected) Color(0xFF2563EB) else Color(0xFF374151),
                            modifier = Modifier.clickable { onToggleSkill?.invoke(name) }
                        ) {
                            Text(
                                text = name,
                                fontSize = 12.sp,
                                color = if (selected) Color.White else Color(0xFF94A3B8),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            // ── 知识库/记忆模式：查看已有 ──
            if (assistantMode != AssistantMode.NORMAL) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = assistantMode.color.copy(alpha = 0.12f),
                        modifier = Modifier.clickable {
                            val query = when (assistantMode) {
                                AssistantMode.KNOWLEDGE -> "请列出我所有的技能"
                                AssistantMode.MEMORY -> "请列出我所有的记忆"
                                else -> ""
                            }
                            if (query.isNotEmpty()) onSendText(query)
                        }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("查看已有${assistantMode.label}", fontSize = 12.sp, color = assistantMode.color)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.Search, null, tint = assistantMode.color, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
    }       // Column content
}           // Scaffold content lambda
}

// ── MessageBubble ───────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(msg: ChatMessage, onDownloadAttachment: ((String, String) -> Unit)? = null, onCopyToInput: ((String) -> Unit)? = null, onRetrySend: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp, 16.dp, if (msg.isUser) 4.dp else 16.dp, if (msg.isUser) 16.dp else 4.dp),
            color = if (msg.isUser) Color(0xFF2563EB) else Color(0xFF1E293B),
            modifier = Modifier.widthIn(max = 300.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                    onCopyToInput?.invoke(msg.text)
                    Toast.makeText(ctx, "已填入输入框", Toast.LENGTH_SHORT).show()
                })
        ) {
            Column(Modifier.padding(12.dp)) {
                if (msg.text.isNotBlank()) {
                    if (msg.isUser) {
                        Text(msg.text, color = Color.White, fontSize = 15.sp, lineHeight = 22.sp)
                    } else {
                        MarkdownText(
                            modifier = Modifier.fillMaxWidth(),
                            markdown = msg.text,
                            style = TextStyle(color = Color.White, fontSize = 15.sp, lineHeight = 22.sp),
                            linkColor = Color(0xFF93C5FD),
                            syntaxHighlightColor = Color(0xFF374151),
                        )
                    }
                }
                // ── 状态指示器 ──
                if (msg.isUser && msg.status != MessageStatus.SENT) {
                    Spacer(Modifier.height(4.dp))
                    when (msg.status) {
                        MessageStatus.SENDING -> {
                            Text("\uD83D\uDD53 发送中...", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        }
                        MessageStatus.FAILED -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("\u274C 发送失败  ", color = Color(0xFFEF4444), fontSize = 11.sp)
                                Text("点击重发", color = Color(0xFF4ADE80), fontSize = 11.sp,
                                    modifier = Modifier.clickable { onRetrySend?.invoke() })
                            }
                        }
                        MessageStatus.RETRYING -> {
                            Text("\u21BB 重试中 (${msg.retryAttempt}/3)...", color = Color(0xFFF59E0B), fontSize = 11.sp)
                        }
                        else -> {}
                    }
                }
                // P2: 内联图片渲染
                msg.attachments.forEach { att ->
                    Spacer(Modifier.height(6.dp))
                    if (att.mime.startsWith("image/") || att.name.endsWith(".jpg") || att.name.endsWith(".png") || att.name.endsWith(".jpeg") || att.name.endsWith(".webp")) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF374151),
                            modifier = Modifier.widthIn(max = 220.dp)
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current).data(att.url).crossfade(true).build(),
                                contentDescription = att.name,
                                modifier = Modifier.widthIn(max = 220.dp).heightIn(max = 180.dp),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    } else {
                        FilePreviewCard(
                            name = att.name,
                            url = att.url,
                            size = att.size,
                            mime = att.mime,
                            onDownload = onDownloadAttachment
                        )
                    }
            }
        }
    }
}
}
