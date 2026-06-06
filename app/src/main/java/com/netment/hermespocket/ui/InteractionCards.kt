package com.netment.hermespocket.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netment.hermespocket.network.HermesWebSocket

// ── FilePreviewCard ─────────────────────────────────
// ═══════════════════════════════════════════════

@Composable
fun FilePreviewCard(
    name: String, url: String, size: Long, mime: String,
    onDownload: ((String, String) -> Unit)? = null
) {
    val icon = when {
        mime.startsWith("image/") -> Icons.Default.Image
        mime == "application/pdf" -> Icons.Default.PictureAsPdf
        mime.startsWith("audio/") -> Icons.Default.MusicNote
        mime.startsWith("video/") -> Icons.Default.Videocam
        else -> Icons.Default.InsertDriveFile
    }
    val sizeText = when {
        size >= 1_000_000 -> "%.1f MB".format(size / 1_000_000.0)
        size >= 1_000 -> "%.1f KB".format(size / 1_000.0)
        else -> "${size} B"
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1E293B),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .border(1.5.dp, Color(0xFF60A5FA), RoundedCornerShape(14.dp))
    ) {
        Column(Modifier.padding(14.dp)) {
            // Title bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("\uD83D\uDCC1 收到文件", color = Color(0xFF60A5FA), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            // File info row
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F172A))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, null, tint = Color(0xFF93C5FD), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (name.length > 30) name.take(30) + "\u2026" else name,
                        color = Color(0xFFE2E8F0), fontSize = 13.sp)
                    Text("$sizeText \u00b7 ${mime.split("/").last()}", color = Color(0xFF64748B), fontSize = 11.sp)
                }
            }
            // Download button
            if (onDownload != null) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF2563EB).copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth().clickable { onDownload(url, name) }
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Download, "\u4e0b\u8f7d", tint = Color(0xFF4ADE80), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("\u70b9\u51fb\u4e0b\u8f7d", color = Color(0xFF4ADE80), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════
//  ErrorRetryCard
// ═══════════════════════════════════════════════

@Composable
fun ErrorRetryCard(error: String, onRetry: (() -> Unit)? = null, onDismiss: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1E293B),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(12.dp))
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFEF4444), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(error.take(80), color = Color(0xFFFCA5A5), fontSize = 13.sp,
                modifier = Modifier.weight(1f))
            if (onRetry != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onRetry) { Text("重试", color = Color(0xFF4ADE80), fontSize = 12.sp) }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, "关闭", tint = Color(0xFF64748B), modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════
//  StepCard — multi-step progress indicator
// ═══════════════════════════════════════════════

data class StepInfo(val label: String, val status: StepStatus = StepStatus.WAITING)
enum class StepStatus { WAITING, RUNNING, DONE, ERROR }

@Composable
fun StepCard(steps: List<StepInfo>, title: String = "任务进度") {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1E293B),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .border(1.dp, Color(0xFF60A5FA), RoundedCornerShape(14.dp))
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("\uD83D\uDCCB $title", color = Color(0xFF60A5FA), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            steps.forEachIndexed { i, step ->
                val icon = when (step.status) {
                    StepStatus.DONE -> "\u2705"
                    StepStatus.RUNNING -> "\u23F3"
                    StepStatus.ERROR -> "\u274C"
                    StepStatus.WAITING -> "\u25CB"
                }
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(icon, fontSize = 12.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("${i + 1}. ${step.label}",
                        color = when (step.status) {
                            StepStatus.RUNNING -> Color(0xFF60A5FA)
                            StepStatus.ERROR -> Color(0xFFEF4444)
                            StepStatus.DONE -> Color(0xFF94A3B8)
                            else -> Color(0xFF64748B)
                        },
                        fontSize = 13.sp)
                    if (step.status == StepStatus.RUNNING) {
                        Spacer(Modifier.width(6.dp))
                        CircularProgressIndicator(modifier = Modifier.size(10.dp), color = Color(0xFF60A5FA), strokeWidth = 1.5.dp)
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════
//  SuggestionCard — collapsible hint
// ═══════════════════════════════════════════════

@Composable
fun SuggestionCard(title: String, content: String, onAccept: (() -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1E293B).copy(alpha = 0.8f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("\uD83D\uDCA1", fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(title, color = Color(0xFF93C5FD), fontSize = 13.sp, modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null, tint = Color(0xFF64748B), modifier = Modifier.size(16.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 38.dp, end = 12.dp, bottom = 10.dp)) {
                    Text(content, color = Color(0xFF94A3B8), fontSize = 12.sp)
                    if (onAccept != null) {
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = onAccept) {
                            Text("采用建议", color = Color(0xFF4ADE80), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
