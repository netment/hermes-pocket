package com.netment.hermespocket.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netment.hermespocket.network.HermesWebSocket
import java.text.SimpleDateFormat
import java.util.*

// ═══════════════════════════════════════════════
//  SessionListScreen — 微信风格会话列表
// ═══════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    activeSessions: List<SessionInfo>,
    archivedSessions: List<SessionInfo>,
    onSessionClick: (Long) -> Unit,
    onNewSession: () -> Unit,
    onPin: (Long, Boolean) -> Unit,
    onArchive: (Long) -> Unit,
    onUnarchive: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onSearch: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    connectionState: HermesWebSocket.ConnectionState = HermesWebSocket.ConnectionState.DISCONNECTED
) {
    var showArchived by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("有数", fontWeight = FontWeight.Bold, color = Color.White) },
                actions = {
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
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { onSearch?.invoke() }) {
                        Icon(Icons.Default.Search, "搜索", tint = Color(0xFF94A3B8))
                    }
                    IconButton(onClick = { onSettings?.invoke() }) {
                        Icon(Icons.Default.Settings, "设置", tint = Color(0xFF94A3B8))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F0F1A))
            )
        },
        containerColor = Color(0xFF0F0F1A),
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewSession,
                containerColor = Color(0xFF2563EB),
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, "新建")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            // ── 活跃会话 ──
            if (activeSessions.isNotEmpty()) {
                item {
                    SectionHeader("活跃会话")
                }
                items(activeSessions, key = { it.id }) { session ->
                    SessionItem(
                        session = session,
                        onClick = { onSessionClick(session.id) },
                        onPin = { onPin(session.id, !session.isPinned) },
                        onArchive = { onArchive(session.id) },
                        onRename = { newName -> onRename(session.id, newName) },
                        onDelete = { onDelete(session.id) }
                    )
                }
            }

            // ── 归档会话 ──
            if (archivedSessions.isNotEmpty()) {
                item {
                    ArchivedHeader(
                        count = archivedSessions.size,
                        expanded = showArchived,
                        onToggle = { showArchived = !showArchived }
                    )
                }
                if (showArchived) {
                    items(archivedSessions, key = { it.id }) { session ->
                        SessionItem(
                            session = session,
                            onClick = { onSessionClick(session.id) },
                            onPin = null,
                            onArchive = null,
                            onUnarchive = { onUnarchive(session.id) },
                            onRename = null,
                            onDelete = { onDelete(session.id) }
                        )
                    }
                }
            }

            // 空状态
            if (activeSessions.isEmpty() && archivedSessions.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(60.dp), contentAlignment = Alignment.Center) {
                        Text("暂无会话", color = Color(0xFF64748B), fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════
//  Section header
// ═══════════════════════════════════════════════

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        color = Color(0xFF64748B),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium
    )
}

// ═══════════════════════════════════════════════
//  Archived header (collapsible)
// ═══════════════════════════════════════════════

@Composable
private fun ArchivedHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("📦 归档会话", color = Color(0xFF64748B), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        Text("($count)", color = Color(0xFF475569), fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            null, tint = Color(0xFF475569), modifier = Modifier.size(16.dp)
        )
    }
}

// ═══════════════════════════════════════════════
//  Session item (WeChat-style)
// ═══════════════════════════════════════════════

@Composable
private fun SessionItem(
    session: SessionInfo,
    onClick: () -> Unit,
    onPin: (() -> Unit)?,
    onArchive: (() -> Unit)?,
    onUnarchive: (() -> Unit)? = null,
    onRename: ((String) -> Unit)?,
    onDelete: (() -> Unit)?
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(session.name) }

    Surface(
        color = if (session.isActive) Color(0xFF1A1A2E) else Color(0xFF0F0F1A),
        modifier = Modifier.fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar placeholder
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF2563EB)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    session.name.take(1),
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (session.isPinned) {
                        Text("📌 ", fontSize = 11.sp)
                    }
                    Text(
                        session.name,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = if (session.isActive) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatRelativeTime(session.lastMsgTime),
                        color = Color(0xFF64748B), fontSize = 11.sp
                    )
                    Spacer(Modifier.width(4.dp))
                    // More menu button
                    Box(modifier = Modifier.clickable { showMenu = true }) {
                        Icon(
                            Icons.Default.MoreVert, "更多",
                            tint = Color(0xFF475569), modifier = Modifier.size(16.dp)
                        )
                    }
                }
                if (session.preview.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!session.isActive) {
                            Surface(
                                shape = RoundedCornerShape(3.dp),
                                color = Color(0xFF334155)
                            ) {
                                Text("归档", color = Color(0xFF94A3B8), fontSize = 9.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            session.preview.take(40) + if (session.preview.length > 40) "…" else "",
                            color = Color(0xFF64748B), fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    // ── 长按菜单 ──
    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
        if (onPin != null) {
            DropdownMenuItem(
                text = { Text(if (session.isPinned) "📌 取消置顶" else "📌 置顶") },
                onClick = { showMenu = false; onPin() }
            )
        }
        if (onUnarchive != null) {
            DropdownMenuItem(
                text = { Text("📤 取消归档") },
                onClick = { showMenu = false; onUnarchive() }
            )
        }
        if (onArchive != null) {
            DropdownMenuItem(
                text = { Text("📦 归档") },
                onClick = { showMenu = false; onArchive() }
            )
        }
        if (onRename != null) {
            DropdownMenuItem(
                text = { Text("✏️ 重命名") },
                onClick = { showMenu = false; showRenameDialog = true }
            )
        }
        DropdownMenuItem(
            text = { Text("🗑️ 删除", color = Color(0xFFEF4444)) },
            onClick = { showMenu = false; onDelete?.invoke() }
        )
    }

    // ── 重命名弹窗 ──
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
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
                        unfocusedBorderColor = Color(0xFF334155)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    if (renameText.isNotBlank()) onRename?.invoke(renameText)
                }) { Text("确定", color = Color(0xFF4ADE80)) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("取消", color = Color(0xFF64748B))
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }
}

// ═══════════════════════════════════════════════
//  相对时间格式化
// ═══════════════════════════════════════════════

fun formatRelativeTime(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000}分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000}小时前"
        diff < 604_800_000 -> "${diff / 86_400_000}天前"
        else -> {
            val sdf = SimpleDateFormat("MM/dd", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}
