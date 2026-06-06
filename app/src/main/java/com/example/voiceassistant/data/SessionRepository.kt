package com.example.voiceassistant.data

import com.example.voiceassistant.ui.SessionInfo

class SessionRepository(private val sessionDao: SessionDao, private val messageDao: MessageDao? = null) {

    suspend fun getByProfile(profile: String): List<SessionEntity> = sessionDao.getByProfile(profile)

    suspend fun getArchived(profile: String): List<SessionEntity> = sessionDao.getArchived(profile)

    suspend fun getActive(): SessionEntity? = sessionDao.getActive()

    suspend fun ensureActive(profile: String): SessionEntity {
        val active = sessionDao.getActive()
        // Only return active if it belongs to this profile
        if (active != null && active.profile == profile) return active
        // Wrong profile or no active — deactivate old, create new
        sessionDao.clearActive()
        val name = if (profile == "home") "家里 会话 1" else "工作 会话 1"
        val session = SessionEntity(
            name = name,
            hermesSessionId = java.util.UUID.randomUUID().toString(),
            profile = profile
        )
        val id = sessionDao.insert(session)
        sessionDao.setActive(id)
        return session.copy(id = id)
    }

    suspend fun create(name: String, profile: String): SessionEntity {
        val session = SessionEntity(
            name = name,
            hermesSessionId = java.util.UUID.randomUUID().toString(),
            profile = profile
        )
        val id = sessionDao.insert(session)
        sessionDao.setActive(id)
        return session.copy(id = id)
    }

    suspend fun switchTo(sessionId: Long) {
        sessionDao.setActive(sessionId)
    }

    suspend fun rename(id: Long, name: String) {
        sessionDao.rename(id, name)
    }

    suspend fun delete(id: Long) {
        sessionDao.delete(id)
    }

    suspend fun pin(id: Long, pinned: Boolean) {
        sessionDao.setPinned(id, pinned)
    }

    suspend fun archive(id: Long, archived: Boolean) {
        sessionDao.setArchived(id, archived)
    }

    suspend fun deleteByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        sessionDao.deleteByIds(ids)
    }

    suspend fun count(): Int = sessionDao.count()

    suspend fun getAll(): List<SessionEntity> = sessionDao.getAll()

    suspend fun deleteAllSessions() {
        sessionDao.deleteAll()
    }

    /** 直接插入 Entity（恢复用，保留原始字段） */
    suspend fun insertForImport(session: SessionEntity): Long {
        return sessionDao.insert(session)
    }

    /** 获取带预览信息的会话列表（活跃 + 归档） */
    suspend fun getSessionsWithPreview(profile: String): Pair<List<SessionInfo>, List<SessionInfo>> {
        val active = sessionDao.getByProfile(profile).map { entity ->
            val preview = messageDao?.getLastMessageText(entity.id) ?: ""
            val lastTime = messageDao?.getLastMessageTime(entity.id) ?: 0L
            SessionInfo(
                id = entity.id, name = entity.name,
                isActive = entity.isActive, isPinned = entity.isPinned,
                isArchived = false, preview = preview, lastMsgTime = lastTime
            )
        }
        val archived = sessionDao.getArchived(profile).map { entity ->
            val preview = messageDao?.getLastMessageText(entity.id) ?: ""
            val lastTime = messageDao?.getLastMessageTime(entity.id) ?: 0L
            SessionInfo(
                id = entity.id, name = entity.name,
                isActive = false, isPinned = false,
                isArchived = true, preview = preview, lastMsgTime = lastTime
            )
        }
        return Pair(active, archived)
    }
}
