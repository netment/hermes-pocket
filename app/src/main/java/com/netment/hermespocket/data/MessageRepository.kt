package com.netment.hermespocket.data

import com.netment.hermespocket.network.HermesWebSocket
import com.netment.hermespocket.ui.ApprovalStatus
import com.netment.hermespocket.ui.ChatMessage
import com.netment.hermespocket.ui.MessageItem
import com.netment.hermespocket.ui.MessageStatus
import org.json.JSONArray

/**
 * Room Entity ↔ UI Model 转换层。
 */
class MessageRepository(private val dao: MessageDao) {

    // ── Query ───────────────────────────────────────

    suspend fun loadBySession(sessionId: Long): List<MessageItem> {
        return dao.getBySession(sessionId).map { it.toMessageItem() }
    }

    suspend fun loadBySessionPaged(sessionId: Long, limit: Int, offset: Int): List<MessageItem> {
        return dao.getBySessionPaged(sessionId, limit, offset).map { it.toMessageItem() }
    }

    suspend fun countBySession(sessionId: Long): Int = dao.countBySession(sessionId)

    // ── P0: 全文搜索 ────────────────────────────────

    data class SearchResultItem(
        val sessionId: Long,
        val messageItem: MessageItem,
        val sessionName: String,
        val matchText: String
    )

    suspend fun search(query: String, limit: Int = 50): List<SearchResultItem> {
        if (query.isBlank()) return emptyList()
        return dao.search(query.trim(), limit).mapNotNull { result -> toSearchItem(result) }
    }

    suspend fun searchBySession(query: String, sessionId: Long, limit: Int = 50): List<SearchResultItem> {
        if (query.isBlank()) return emptyList()
        return dao.searchBySession(query.trim(), sessionId, limit).mapNotNull { result -> toSearchItem(result) }
    }

    private fun toSearchItem(result: SearchResult): SearchResultItem? {
        val item = when (result.type) {
            "chat_msg" -> MessageItem.ChatMsg(
                ChatMessage(
                    text = result.text ?: "",
                    isUser = result.isUser ?: false,
                    attachments = parseAttachments(result.attachmentsJson),
                    timestamp = result.timestamp
                )
            )
            "approval_item" -> MessageItem.ApprovalItem(
                approval = HermesWebSocket.ApprovalRequired(
                    approvalId = "",
                    command = result.command ?: "",
                    description = result.description ?: ""
                ),
                status = try { ApprovalStatus.valueOf(result.approvalStatus ?: "PENDING") }
                    catch (_: Exception) { ApprovalStatus.PENDING },
                timestamp = result.timestamp
            )
            else -> return null
        }
        return SearchResultItem(
            sessionId = result.sessionId,
            messageItem = item,
            sessionName = result.sessionName,
            matchText = result.text ?: result.command ?: result.description ?: ""
        )
    }

    // ── Mutations ────────────────────────────────────

    suspend fun insert(sessionId: Long, item: MessageItem): Long {
        return dao.insert(item.toEntity(sessionId))
    }

    suspend fun updateMessageStatus(messageId: Long, status: MessageStatus) {
        dao.updateMessageStatus(messageId, status.name)
    }

    suspend fun updateApprovalStatus(sessionId: Long, approval: HermesWebSocket.ApprovalRequired, status: ApprovalStatus) {
        dao.updateApprovalStatus(sessionId, approval.command, approval.description, status.name)
    }

    suspend fun expireAllPending(sessionId: Long) {
        dao.expireAllPending(sessionId)
    }

    /** 清理当前会话中卡在 SENDING 的消息（加载前调用） */
    suspend fun expireSending(sessionId: Long) {
        dao.expireSending(sessionId)
    }

    suspend fun deleteBySession(sessionId: Long) {
        dao.deleteBySession(sessionId)
    }

    // ── P0: 数据仪表盘 ──────────────────────────────

    data class DashboardStats(
        val chatMessageCount: Int,
        val approvalCount: Int,
        val totalSessions: Int,
        val estimatedStorageBytes: Long
    ) {
        val totalMessages: Int get() = chatMessageCount + approvalCount
        val readableStorage: String get() = when {
            estimatedStorageBytes >= 1_000_000 -> "%.1f MB".format(estimatedStorageBytes / 1_000_000.0)
            estimatedStorageBytes >= 1_000 -> "%.1f KB".format(estimatedStorageBytes / 1_000.0)
            else -> "${estimatedStorageBytes} B"
        }
    }

    suspend fun getDashboardStats(totalSessions: Int): DashboardStats {
        return DashboardStats(
            chatMessageCount = dao.chatMessageCount(),
            approvalCount = dao.approvalCount(),
            totalSessions = totalSessions,
            estimatedStorageBytes = dao.estimatedStorageBytes()
        )
    }

    // ── P0: 批量管理 ────────────────────────────────

    suspend fun clearBySession(sessionId: Long) {
        dao.clearBySession(sessionId)
    }

    // ── 备份 ──

    suspend fun deleteAllMessages() {
        dao.deleteAll()
    }

    /** 直接插入 Entity（恢复用） */
    suspend fun insertEntity(entity: MessageEntity): Long {
        return dao.insert(entity)
    }

    // ── Entity ↔ MessageItem ────────────────────────

    private fun MessageEntity.toMessageItem(): MessageItem = when (type) {
        "chat_msg" -> MessageItem.ChatMsg(
            ChatMessage(
                text = text ?: "",
                isUser = isUser ?: false,
                attachments = parseAttachments(attachmentsJson),
                timestamp = timestamp,
                status = when (messageStatus) {
                    "SENDING", "FAILED" -> MessageStatus.SENT  // 旧版残留 → 正常
                    else -> try { messageStatus?.let { MessageStatus.valueOf(it) } ?: MessageStatus.SENT }
                        catch (_: Exception) { MessageStatus.SENT }
                }
            )
        )
        "approval_item" -> MessageItem.ApprovalItem(
            approval = HermesWebSocket.ApprovalRequired(
                approvalId = "",
                command = command ?: "",
                description = description ?: ""
            ),
            status = try { ApprovalStatus.valueOf(approvalStatus ?: "PENDING") }
                catch (_: Exception) { ApprovalStatus.PENDING },
            timestamp = timestamp
        )
        else -> throw IllegalArgumentException("Unknown message type: $type")
    }

    private fun MessageItem.toEntity(sessionId: Long): MessageEntity = when (this) {
        is MessageItem.ChatMsg -> MessageEntity(
            sessionId = sessionId,
            type = "chat_msg",
            text = msg.text,
            isUser = msg.isUser,
            attachmentsJson = msg.attachments.toJson(),
            timestamp = msg.timestamp,
            messageStatus = msg.status.name
        )
        is MessageItem.ApprovalItem -> MessageEntity(
            sessionId = sessionId,
            type = "approval_item",
            command = approval.command,
            description = approval.description,
            approvalStatus = status.name,
            timestamp = timestamp
        )
        is MessageItem.ClarifyItem -> MessageEntity(
            sessionId = sessionId,
            type = "chat_msg",
            text = "❓ ${prompt.question}",
            isUser = false,
            timestamp = timestamp
        )
        is MessageItem.FileItem -> MessageEntity(
            sessionId = sessionId, type = "chat_msg", text = "", isUser = false, timestamp = timestamp
        )
        is MessageItem.StepItem -> MessageEntity(
            sessionId = sessionId, type = "chat_msg", text = "", isUser = false, timestamp = timestamp
        )
        is MessageItem.SuggestionItem -> MessageEntity(
            sessionId = sessionId, type = "chat_msg", text = "", isUser = false, timestamp = timestamp
        )
        is MessageItem.ErrorItem -> MessageEntity(
            sessionId = sessionId, type = "chat_msg", text = "⚠️ $error", isUser = false, timestamp = timestamp
        )
        is MessageItem.ThinkingItem -> MessageEntity(
            sessionId = sessionId, type = "chat_msg", text = "", isUser = false, timestamp = System.currentTimeMillis()
        )
    }

    // ── JSON helpers ─────────────────────────────────

    companion object {
        fun List<HermesWebSocket.Attachment>.toJson(): String {
            if (isEmpty()) return "[]"
            val arr = JSONArray()
            forEach { att ->
                arr.put(org.json.JSONObject().apply {
                    put("name", att.name)
                    put("url", att.url)
                    put("size", att.size)
                    put("mime", att.mime)
                })
            }
            return arr.toString()
        }

        fun parseAttachments(json: String?): List<HermesWebSocket.Attachment> {
            if (json.isNullOrBlank() || json == "[]") return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map {
                    val obj = arr.getJSONObject(it)
                    HermesWebSocket.Attachment(
                        name = obj.optString("name", ""),
                        url = obj.optString("url", ""),
                        size = obj.optLong("size", 0),
                        mime = obj.optString("mime", "")
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
