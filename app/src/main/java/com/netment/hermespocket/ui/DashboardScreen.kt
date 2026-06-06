package com.netment.hermespocket.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netment.hermespocket.data.MessageRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onBack: () -> Unit,
    stats: MessageRepository.DashboardStats
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("数据仪表盘", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回", tint = Color(0xFF94A3B8))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A2E), titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF0F0F1A)
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("📊 全局数据", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    title = "总消息",
                    value = "${stats.totalMessages}",
                    icon = Icons.Default.Chat,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "会话数",
                    value = "${stats.totalSessions}",
                    icon = Icons.Default.Folder,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "存储",
                    value = stats.readableStorage,
                    icon = Icons.Default.Storage,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    title = "对话气泡",
                    value = "${stats.chatMessageCount}",
                    icon = Icons.Default.QuestionAnswer,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "审批记录",
                    value = "${stats.approvalCount}",
                    icon = Icons.Default.Security,
                    modifier = Modifier.weight(1f)
                )
                Box(Modifier.weight(1f))  // spacer
            }

            Spacer(Modifier.height(24.dp))
            Text("🔐 数据主权", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1E293B),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, null, tint = Color(0xFF4ADE80), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("所有数据存储在手机本地 SQLite 数据库中", color = Color(0xFFE2E8F0), fontSize = 14.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudOff, null, tint = Color(0xFF4ADE80), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("不经过任何第三方服务器", color = Color(0xFFE2E8F0), fontSize = 14.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Share, null, tint = Color(0xFF4ADE80), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("随时导出为 Markdown 或 JSON，完全透明", color = Color(0xFFE2E8F0), fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1E293B),
        modifier = modifier
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = Color(0xFF93C5FD), modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(6.dp))
            Text(value, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(title, color = Color(0xFF64748B), fontSize = 12.sp)
        }
    }
}
