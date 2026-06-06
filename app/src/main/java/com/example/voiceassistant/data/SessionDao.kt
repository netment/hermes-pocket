package com.example.voiceassistant.data

import androidx.room.*

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: SessionEntity): Long

    /** 指定 Profile 的活跃会话列表：置顶在前，归档隐藏 */
    @Query("SELECT * FROM sessions WHERE profile = :profile AND isArchived = 0 ORDER BY isPinned DESC, createdAt ASC")
    suspend fun getByProfile(profile: String): List<SessionEntity>

    /** 指定 Profile 的已归档会话 */
    @Query("SELECT * FROM sessions WHERE profile = :profile AND isArchived = 1 ORDER BY createdAt ASC")
    suspend fun getArchived(profile: String): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): SessionEntity?

    @Transaction
    suspend fun setActive(sessionId: Long) {
        clearActive()
        activate(sessionId)
    }

    @Query("UPDATE sessions SET isActive = 0")
    suspend fun clearActive()

    @Query("UPDATE sessions SET isActive = 1 WHERE id = :id")
    suspend fun activate(id: Long)

    @Query("UPDATE sessions SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)

    // ── 置顶/归档 ──

    @Query("UPDATE sessions SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE sessions SET isArchived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    // ── 批量管理 ──

    @Query("DELETE FROM sessions WHERE id IN (:ids) AND isActive = 0")
    suspend fun deleteByIds(ids: List<Long>)

    // ── 仪表盘 (全部 profile) ──

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Int

    // ── 备份 ──

    @Query("SELECT * FROM sessions ORDER BY createdAt ASC")
    suspend fun getAll(): List<SessionEntity>

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}
