package com.example.voiceassistant.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 对应 Hermes 的 session_id (UUID)，通过 WebSocket URL 传递 */
    val hermesSessionId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = false,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    /** 所属 Profile: "work" | "home" */
    val profile: String = "work"
)
