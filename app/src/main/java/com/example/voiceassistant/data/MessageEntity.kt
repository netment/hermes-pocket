package com.example.voiceassistant.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 单表方案：一条行对应一个 [MessageItem]。
 *
 * type: "chat_msg" | "approval_item"
 * 不同 type 使用不同列组，其余为 null。
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val type: String,

    // ── 聊天气泡 ──
    val text: String? = null,
    val isUser: Boolean? = null,
    val attachmentsJson: String? = null,   // JSON array of HermesWebSocket.Attachment

    // ── 审批卡片 ──
    val command: String? = null,
    val description: String? = null,
    val approvalStatus: String? = null,    // PENDING / APPROVED / DENIED

    // ── 通用 ──
    val timestamp: Long,

    // ── 消息发送状态 (仅 chat_msg) ──
    val messageStatus: String? = null   // SENDING / SENT / FAILED
)
