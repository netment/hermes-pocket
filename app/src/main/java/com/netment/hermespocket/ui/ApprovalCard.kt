package com.netment.hermespocket.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.foundation.layout.PaddingValues
import com.netment.hermespocket.network.HermesWebSocket
import kotlinx.coroutines.delay

// ═══════════════════════════════════════════════
//  Approve Action — shared between cards
// ═══════════════════════════════════════════════

enum class RiskLevel { LOW, MEDIUM, HIGH }

fun detectRisk(command: String, description: String): RiskLevel {
    val text = "$command $description".lowercase()
    return when {
        text.contains("rm ") || text.contains("delete") || text.contains("format") ||
        text.contains("drop table") || text.contains("truncate") -> RiskLevel.HIGH
        text.contains("write") || text.contains("edit") || text.contains("install") ||
        text.contains("uninstall") || text.contains("shutdown") || text.contains("reboot") -> RiskLevel.MEDIUM
        else -> RiskLevel.LOW
    }
}

@Composable
fun RiskBadge(level: RiskLevel) {
    val (color, text) = when (level) {
        RiskLevel.LOW -> Color(0xFF4ADE80) to "低"
        RiskLevel.MEDIUM -> Color(0xFFF59E0B) to "中"
        RiskLevel.HIGH -> Color(0xFFEF4444) to "高"
    }
    Surface(shape = RoundedCornerShape(4.dp), color = color.copy(alpha = 0.2f)) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

// ═══════════════════════════════════════════════
//  ApprovalCard (enhanced)
// ═══════════════════════════════════════════════

@Composable
fun ApprovalCard(
    approval: HermesWebSocket.ApprovalRequired,
    status: ApprovalStatus = ApprovalStatus.PENDING,
    onApprove: ((String) -> Unit)? = null,
    onDeny: (() -> Unit)? = null
) {
    val risk = remember { detectRisk(approval.command, approval.description) }
    var remainingSec by remember { mutableIntStateOf(30) }
    var showConfirmDialog by remember { mutableStateOf(false) }

    // Auto-deny after 30 seconds
    if (status == ApprovalStatus.PENDING && onDeny != null) {
        LaunchedEffect(Unit) {
            while (remainingSec > 0) { delay(1000); remainingSec-- }
            onDeny()
        }
    }

    val borderColor = when {
        status == ApprovalStatus.PENDING -> when (risk) {
            RiskLevel.HIGH -> Color(0xFFEF4444)
            RiskLevel.MEDIUM -> Color(0xFFF59E0B)
            RiskLevel.LOW -> Color(0xFF60A5FA)
        }
        status == ApprovalStatus.APPROVED -> Color(0xFF4ADE80)
        else -> Color(0xFF334155)
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1E293B),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .border(1.5.dp, borderColor, RoundedCornerShape(14.dp))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (status) {
                        ApprovalStatus.PENDING -> Icons.Default.Security
                        ApprovalStatus.APPROVED -> Icons.Default.CheckCircle
                        ApprovalStatus.DENIED -> Icons.Default.Cancel
                    },
                    contentDescription = null,
                    tint = borderColor, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    when (status) {
                        ApprovalStatus.PENDING -> "危险命令检测"
                        ApprovalStatus.APPROVED -> "已批准"
                        ApprovalStatus.DENIED -> "已拒绝"
                    },
                    color = borderColor, fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(6.dp))
                RiskBadge(risk)
                if (status == ApprovalStatus.PENDING) {
                    Spacer(Modifier.weight(1f))
                    Text("⏱ ${remainingSec}s", color = Color(0xFF64748B), fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0F172A),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    approval.command,
                    color = Color(0xFFF1F5F9),
                    fontSize = 12.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.padding(8.dp)
                )
            }
            if (approval.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(approval.description, color = Color(0xFF94A3B8), fontSize = 12.sp)
            }

            if (status == ApprovalStatus.PENDING) {
                Spacer(Modifier.height(10.dp))
                val btnPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { onDeny?.invoke() },
                        modifier = Modifier.weight(1f),
                        contentPadding = btnPadding,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                        border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp)
                    ) { Text("❌ 拒绝", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    OutlinedButton(
                        onClick = { onApprove?.invoke("once") },
                        modifier = Modifier.weight(1f),
                        contentPadding = btnPadding,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF59E0B))
                    ) { Text("✅ 允许一次", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Button(
                        onClick = {
                            if (risk >= RiskLevel.MEDIUM) showConfirmDialog = true
                            else onApprove?.invoke("session")
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = btnPadding,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                    ) { Text("🔓 会话允许", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("⚠️ 确认操作", color = Color.White) },
            text = { Text("这是一个${when(risk){RiskLevel.HIGH->"高风险" else->"中风险"}}操作，确定整个会话都允许？", color = Color(0xFF94A3B8)) },
            confirmButton = {
                TextButton(onClick = { showConfirmDialog = false; onApprove?.invoke("session") }) {
                    Text("确定", color = Color(0xFFF59E0B))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) { Text("取消", color = Color(0xFF93C5FD)) }
            },
            containerColor = Color(0xFF1E293B)
        )
    }
}
