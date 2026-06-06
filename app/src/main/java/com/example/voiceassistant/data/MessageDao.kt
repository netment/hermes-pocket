package com.example.voiceassistant.data

import androidx.room.*

@Dao
interface MessageDao {

    @Insert
    suspend fun insert(entity: MessageEntity): Long

    /** 一次性加载指定会话的全部消息 */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getBySession(sessionId: Long): List<MessageEntity>

    /** 分页加载（用于首次加载 + 上滑更多），timestamp ASC 取最旧的在前 */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC LIMIT :limit OFFSET :offset")
    suspend fun getBySessionPaged(sessionId: Long, limit: Int, offset: Int): List<MessageEntity>

    /** 获取会话消息总数（判断是否还有更多） */
    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun countBySession(sessionId: Long): Int

    /** 更新审批卡片状态 */
    @Query("UPDATE messages SET approvalStatus = :status WHERE command = :command AND description = :description AND type = 'approval_item' AND sessionId = :sessionId")
    suspend fun updateApprovalStatus(sessionId: Long, command: String, description: String, status: String)

    /** 更新消息发送状态 */
    @Query("UPDATE messages SET messageStatus = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: Long, status: String)

    /** 将当前会话所有 PENDING 改为 DENIED */
    @Query("UPDATE messages SET approvalStatus = 'DENIED' WHERE approvalStatus = 'PENDING' AND type = 'approval_item' AND sessionId = :sessionId")
    suspend fun expireAllPending(sessionId: Long)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)

    // ── P0: 全文搜索 ────────────────────────────────

    /** 搜索所有会话的消息文本和命令描述（按时间倒序） */
    @Query("""
        SELECT m.*, s.name AS sessionName
        FROM messages m
        INNER JOIN sessions s ON m.sessionId = s.id
        WHERE (m.text LIKE '%' || :query || '%' OR m.command LIKE '%' || :query || '%' OR m.description LIKE '%' || :query || '%')
        ORDER BY m.timestamp DESC
        LIMIT :limit
    """)
    suspend fun search(query: String, limit: Int = 50): List<SearchResult>

    /** 搜索单个会话的消息（用于会话内搜索） */
    @Query("""
        SELECT m.*, s.name AS sessionName
        FROM messages m
        INNER JOIN sessions s ON m.sessionId = s.id
        WHERE m.sessionId = :sessionId
          AND (m.text LIKE '%' || :query || '%' OR m.command LIKE '%' || :query || '%' OR m.description LIKE '%' || :query || '%')
        ORDER BY m.timestamp DESC
        LIMIT :limit
    """)
    suspend fun searchBySession(query: String, sessionId: Long, limit: Int = 50): List<SearchResult>

    // ── P0: 数据仪表盘 ──────────────────────────────

    @Query("SELECT COUNT(*) FROM messages WHERE type = 'chat_msg'")
    suspend fun chatMessageCount(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE type = 'approval_item'")
    suspend fun approvalCount(): Int

    /** 清理当前会话中卡在 SENDING 的消息
     *  同时修复旧版 @Volatile bug 产生的假 FAILED */
    @Query("UPDATE messages SET messageStatus = NULL WHERE (messageStatus = 'SENDING' OR messageStatus = 'FAILED') AND type = 'chat_msg' AND isUser = 1 AND sessionId = :sessionId")
    suspend fun expireSending(sessionId: Long)
    @Query("SELECT COALESCE(SUM(LENGTH(COALESCE(text,'')) + LENGTH(COALESCE(command,'')) + LENGTH(COALESCE(description,'')) + LENGTH(COALESCE(attachmentsJson,''))), 0) FROM messages")
    suspend fun estimatedStorageBytes(): Long

    /** 清空指定会话的全部消息 */
    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun clearBySession(sessionId: Long)

    // ── 备份 ──

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    /** 获取指定会话的最后一条文本消息（用于会话列表预览） */
    @Query("SELECT COALESCE(text, '') FROM messages WHERE sessionId = :sessionId AND type = 'chat_msg' ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessageText(sessionId: Long): String?

    /** 获取指定会话的最后一条消息时间戳 */
    @Query("SELECT COALESCE(MAX(timestamp), 0) FROM messages WHERE sessionId = :sessionId")
    suspend fun getLastMessageTime(sessionId: Long): Long
}

/**
 * 搜索结果行，包含原始消息字段 + 会话名称。
 * Room 会将 INNER JOIN 结果映射到此 data class。
 */
data class SearchResult(
    val id: Long,
    val sessionId: Long,
    val type: String,
    val text: String?,
    val isUser: Boolean?,
    val attachmentsJson: String?,
    val command: String?,
    val description: String?,
    val approvalStatus: String?,
    val timestamp: Long,
    val sessionName: String
)
