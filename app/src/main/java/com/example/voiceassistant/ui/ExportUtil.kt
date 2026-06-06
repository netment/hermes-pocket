package com.example.voiceassistant.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.voiceassistant.data.MessageRepository
import com.example.voiceassistant.network.HermesWebSocket
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * P0: 会话导出工具。
 *
 * 支持 Markdown 和 JSON 两种格式。
 * 导出到 app 外部缓存目录，通过 FileProvider + Share Intent 发送。
 */
object ExportUtil {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /**
     * 导出会话为 Markdown 并分享。
     */
    suspend fun exportMarkdown(
        context: Context,
        sessionName: String,
        messages: List<MessageItem>
    ) {
        val sb = StringBuilder()
        sb.appendLine("# $sessionName")
        sb.appendLine()
        sb.appendLine("导出时间: ${dateFmt.format(Date())}")
        sb.appendLine("消息数量: ${messages.size}")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        for (msg in messages) {
            when (msg) {
                is MessageItem.ChatMsg -> {
                    val role = if (msg.msg.isUser) "🧑 你" else "🤖 Sage"
                    sb.appendLine("### $role — ${dateFmt.format(Date(msg.msg.timestamp))}")
                    sb.appendLine()
                    if (msg.msg.text.isNotBlank()) {
                        sb.appendLine(msg.msg.text)
                        sb.appendLine()
                    }
                    if (msg.msg.attachments.isNotEmpty()) {
                        sb.appendLine("📎 附件:")
                        msg.msg.attachments.forEach { att ->
                            sb.appendLine("- ${att.name} (${formatSize(att.size)})")
                        }
                        sb.appendLine()
                    }
                    sb.appendLine("---")
                    sb.appendLine()
                }
                is MessageItem.ApprovalItem -> {
                    val status = when (msg.status) {
                        ApprovalStatus.PENDING -> "⏳ 待确认"
                        ApprovalStatus.APPROVED -> "✅ 已批准"
                        ApprovalStatus.DENIED -> "❌ 已拒绝"
                    }
                    sb.appendLine("### 🔐 审批 $status — ${dateFmt.format(Date(msg.timestamp))}")
                    sb.appendLine()
                    sb.appendLine("```")
                    sb.appendLine(msg.approval.command)
                    sb.appendLine("```")
                    if (msg.approval.description.isNotBlank()) {
                        sb.appendLine(msg.approval.description)
                    }
                    sb.appendLine()
                    sb.appendLine("---")
                    sb.appendLine()
                }
                is MessageItem.ClarifyItem -> { /* skip */ }
                is MessageItem.ToolItem, is MessageItem.FileItem,
                is MessageItem.StepItem, is MessageItem.SuggestionItem,
                is MessageItem.ErrorItem -> { /* skip */ }
                is MessageItem.ThinkingItem -> { /* skip */ }
            }
        }

        shareFile(context, sanitizeFileName(sessionName) + ".md", sb.toString(), "text/markdown")
    }

    /**
     * 导出会话为 JSON 并分享。
     */
    suspend fun exportJson(
        context: Context,
        sessionName: String,
        messages: List<MessageItem>
    ) {
        val jsonArr = org.json.JSONArray()
        for (msg in messages) {
            val obj = org.json.JSONObject()
            when (msg) {
                is MessageItem.ChatMsg -> {
                    obj.put("type", "chat_msg")
                    obj.put("role", if (msg.msg.isUser) "user" else "assistant")
                    obj.put("text", msg.msg.text)
                    obj.put("timestamp", msg.msg.timestamp)
                    val atts = org.json.JSONArray()
                    msg.msg.attachments.forEach { att ->
                        atts.put(org.json.JSONObject().apply {
                            put("name", att.name)
                            put("url", att.url)
                            put("size", att.size)
                            put("mime", att.mime)
                        })
                    }
                    obj.put("attachments", atts)
                }
                is MessageItem.ApprovalItem -> {
                    obj.put("type", "approval_item")
                    obj.put("command", msg.approval.command)
                    obj.put("description", msg.approval.description)
                    obj.put("status", msg.status.name)
                    obj.put("timestamp", msg.timestamp)
                }
                is MessageItem.ClarifyItem -> { /* skip */ }
                is MessageItem.ToolItem -> { /* skip */ }
                is MessageItem.FileItem -> { /* skip */ }
                is MessageItem.StepItem -> { /* skip */ }
                is MessageItem.SuggestionItem -> { /* skip */ }
                is MessageItem.ErrorItem -> { /* skip */ }
                is MessageItem.ThinkingItem -> { /* skip */ }
            }
            jsonArr.put(obj)
        }

        // 封装为完整 JSON 结构
        val root = org.json.JSONObject().apply {
            put("session_name", sessionName)
            put("exported_at", System.currentTimeMillis())
            put("message_count", messages.size)
            put("messages", jsonArr)
        }

        shareFile(context, sanitizeFileName(sessionName) + ".json", root.toString(2), "application/json")
    }

    private fun shareFile(context: Context, fileName: String, content: String, mimeType: String) {
        try {
            val dir = File(context.cacheDir, "exports")
            dir.mkdirs()
            val file = File(dir, fileName)
            file.writeText(content)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(Intent.createChooser(shareIntent, "导出 $fileName"))
        } catch (e: Exception) {
            Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
        else -> "${bytes} B"
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(50)
    }

    // ═══════════════════════════════════════════════
    //  导入（解析 ExportUtil JSON 格式）
    // ═══════════════════════════════════════════════

    data class ImportedSession(
        val name: String,
        val messages: List<com.example.voiceassistant.data.MessageEntity>
    )

    /**
     * 解析 ExportUtil.exportJson() 导出的 JSON 文件。
     * 返回会话名称和消息列表（不含 sessionId，由调用方插入时填入）。
     */
    fun importSessionJson(
        context: android.content.Context,
        uri: android.net.Uri
    ): ImportedSession? {
        return try {
            val jsonStr = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                ?: throw Exception("无法读取文件")
            val root = org.json.JSONObject(jsonStr)

            val name = root.optString("session_name", "已导入会话")
            val msgsArr = root.optJSONArray("messages") ?: return ImportedSession(name, emptyList())

            val entities = mutableListOf<com.example.voiceassistant.data.MessageEntity>()
            for (i in 0 until msgsArr.length()) {
                val obj = msgsArr.getJSONObject(i)
                val type = obj.optString("type", "chat_msg")
                val entity = when (type) {
                    "chat_msg" -> com.example.voiceassistant.data.MessageEntity(
                        sessionId = 0, // 占位，调用方填充
                        type = "chat_msg",
                        text = obj.optString("text", ""),
                        isUser = obj.optString("role", "user") == "user",
                        attachmentsJson = obj.optJSONArray("attachments")?.toString() ?: "[]",
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                    "approval_item" -> com.example.voiceassistant.data.MessageEntity(
                        sessionId = 0,
                        type = "approval_item",
                        command = obj.optString("command", ""),
                        description = obj.optString("description", ""),
                        approvalStatus = obj.optString("status", "PENDING"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                    else -> continue
                }
                entities.add(entity)
            }

            ImportedSession(name, entities)
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "导入失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            null
        }
    }
}
