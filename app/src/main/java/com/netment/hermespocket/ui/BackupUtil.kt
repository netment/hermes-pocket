package com.netment.hermespocket.ui

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import com.netment.hermespocket.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 全量备份/恢复：导出所有会话+消息+设置 → .sagebackup JSON
 * 存到 Documents/SageBackup/ 目录，与 Downloads 隔离，卸载后文件不丢失。
 */
object BackupUtil {

    private const val BACKUP_VERSION = 1
    private const val FILE_EXT = ".sagebackup"
    private const val BACKUP_DIR = "SageBackup"

    data class ImportResult(
        val sessionsRestored: Int,
        val messagesRestored: Int,
        val settingsRestored: Boolean
    )

    // ═══════════════════════════════════════════════
    //  导出
    // ═══════════════════════════════════════════════

    suspend fun exportBackup(
        context: Context,
        sessionRepo: SessionRepository,
        msgRepo: MessageRepository
    ): Boolean {
        return try {
            val root = JSONObject().apply {
                put("version", BACKUP_VERSION)
                put("app", "有数")
                put("exported_at", System.currentTimeMillis())
                put("exported_at_fmt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))

                // 设置
                val settingsJson = JSONObject()
                for ((k, v) in AppSettings.exportSettings(context)) {
                    settingsJson.put(k, v)
                }
                put("settings", settingsJson)

                // 所有会话 + 消息
                val sessionsArr = JSONArray()
                for (session in sessionRepo.getAll()) {
                    val sessionObj = JSONObject().apply {
                        put("name", session.name)
                        put("profile", session.profile)
                        put("hermesSessionId", session.hermesSessionId)
                        put("createdAt", session.createdAt)
                        put("isPinned", session.isPinned)
                        put("isArchived", session.isArchived)
                    }

                    val msgsArr = JSONArray()
                    for (msg in msgRepo.loadBySession(session.id)) {
                        when (msg) {
                            is MessageItem.ChatMsg -> {
                                val m = JSONObject().apply {
                                    put("type", "chat_msg")
                                    put("text", msg.msg.text)
                                    put("isUser", msg.msg.isUser)
                                    put("timestamp", msg.msg.timestamp)
                                    put("status", msg.msg.status.name)
                                    val atts = JSONArray()
                                    msg.msg.attachments.forEach { att ->
                                        atts.put(JSONObject().apply {
                                            put("name", att.name)
                                            put("url", att.url)
                                            put("size", att.size)
                                            put("mime", att.mime)
                                        })
                                    }
                                    put("attachmentsJson", atts.toString())
                                }
                                msgsArr.put(m)
                            }
                            is MessageItem.ApprovalItem -> {
                                val m = JSONObject().apply {
                                    put("type", "approval_item")
                                    put("command", msg.approval.command)
                                    put("description", msg.approval.description)
                                    put("status", msg.status.name)
                                    put("timestamp", msg.timestamp)
                                }
                                msgsArr.put(m)
                            }
                            is MessageItem.ClarifyItem -> { /* skip */ }
                            is MessageItem.FileItem,
                            is MessageItem.StepItem, is MessageItem.SuggestionItem,
                            is MessageItem.ErrorItem -> { /* skip */ }
                            is MessageItem.ThinkingItem -> { /* skip */ }
                        }
                    }
                    sessionObj.put("messages", msgsArr)
                    sessionsArr.put(sessionObj)
                }
                put("sessions", sessionsArr)
            }

            // 写入 Documents/SageBackup/ 目录
            val fileName = "SageBackup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}$FILE_EXT"
            val backupDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), BACKUP_DIR)
            backupDir.mkdirs()
            val file = File(backupDir, fileName)
            file.writeText(root.toString(2))

            Toast.makeText(context, "备份已保存: $fileName", Toast.LENGTH_LONG).show()
            true
        } catch (e: Exception) {
            Toast.makeText(context, "备份失败: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    // ═══════════════════════════════════════════════
    //  导入
    // ═══════════════════════════════════════════════

    suspend fun importBackup(
        context: Context,
        fileUri: android.net.Uri,
        sessionRepo: SessionRepository,
        msgRepo: MessageRepository,
        targetProfile: String = "work"
    ): ImportResult? {
        return try {
            val jsonStr = context.contentResolver.openInputStream(fileUri)?.bufferedReader()?.readText()
                ?: throw Exception("无法读取文件")
            val root = JSONObject(jsonStr)

            if (root.optInt("version", 0) > BACKUP_VERSION) {
                throw Exception("备份文件版本过新，请升级 App")
            }

            // 验证基本结构
            if (!root.has("sessions") || !root.has("settings")) {
                throw Exception("无效的备份文件格式")
            }

            // 1. 清空现有数据
            msgRepo.deleteAllMessages()
            sessionRepo.deleteAllSessions()

            // 2. 恢复设置
            val settingsJson = root.getJSONObject("settings")
            val settingsMap = mutableMapOf<String, String>()
            for (key in settingsJson.keys()) {
                settingsMap[key] = settingsJson.getString(key)
            }
            AppSettings.importSettings(context, settingsMap)

            // 3. 恢复会话和消息
            val sessionsArr = root.getJSONArray("sessions")
            var sessionCount = 0
            var msgCount = 0

            for (i in 0 until sessionsArr.length()) {
                val sObj = sessionsArr.getJSONObject(i)
                val session = SessionEntity(
                    name = sObj.optString("name", "已恢复会话"),
                    hermesSessionId = sObj.optString("hermesSessionId", UUID.randomUUID().toString()),
                    profile = sObj.optString("profile", targetProfile),  // 从备份恢复原始 Profile，缺省用当前
                    createdAt = sObj.optLong("createdAt", System.currentTimeMillis()),
                    isPinned = sObj.optBoolean("isPinned", false),
                    isArchived = sObj.optBoolean("isArchived", false),
                    isActive = false  // 不强制设 active，由 ensureActive 处理
                )
                val sessionId = sessionRepo.insertForImport(session)
                sessionCount++

                val msgsArr = sObj.optJSONArray("messages") ?: continue
                for (j in 0 until msgsArr.length()) {
                    val mObj = msgsArr.getJSONObject(j)
                    val type = mObj.optString("type", "chat_msg")
                    val entity = when (type) {
                        "chat_msg" -> MessageEntity(
                            sessionId = sessionId,
                            type = "chat_msg",
                            text = mObj.optString("text", ""),
                            isUser = mObj.optBoolean("isUser", true),
                            attachmentsJson = mObj.optString("attachmentsJson", "[]"),
                            timestamp = mObj.optLong("timestamp", System.currentTimeMillis())
                        )
                        "approval_item" -> MessageEntity(
                            sessionId = sessionId,
                            type = "approval_item",
                            command = mObj.optString("command", ""),
                            description = mObj.optString("description", ""),
                            approvalStatus = mObj.optString("status", "PENDING"),
                            timestamp = mObj.optLong("timestamp", System.currentTimeMillis())
                        )
                        else -> continue
                    }
                    msgRepo.insertEntity(entity)
                    msgCount++
                }
            }

            ImportResult(sessionCount, msgCount, true)
        } catch (e: Exception) {
            Toast.makeText(context, "恢复失败: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }
}
