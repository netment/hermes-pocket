package com.netment.hermespocket.controller

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import com.netment.hermespocket.data.MessageRepository
import com.netment.hermespocket.data.SessionEntity
import com.netment.hermespocket.data.SessionRepository
import com.netment.hermespocket.ui.AppSettings
import com.netment.hermespocket.ui.SessionInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SessionController(
    private val sessionRepo: SessionRepository,
    private val msgRepo: MessageRepository,
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object { private const val TAG = "SessionCtrl" }

    val sessionList = mutableStateListOf<SessionInfo>()
    val archivedSessionList = mutableStateListOf<SessionInfo>()
    var currentSession by mutableStateOf<SessionEntity?>(null)
    var wsSessionId by mutableStateOf("")
    var activeProfile by mutableStateOf("work")
    var dashboardStats by mutableStateOf(MessageRepository.DashboardStats(0, 0, 0, 0))

    // ── refresh ──

    fun refreshSessionList() {
        scope.launch {
            val (active, archived) = sessionRepo.getSessionsWithPreview(activeProfile)
            sessionList.clear(); sessionList.addAll(active)
            archivedSessionList.clear(); archivedSessionList.addAll(archived)
        }
    }

    fun refreshDashboard() {
        scope.launch { dashboardStats = msgRepo.getDashboardStats(sessionRepo.count()) }
    }

    // ── ensure active ──

    suspend fun ensureActive(): SessionEntity {
        val s = sessionRepo.ensureActive(activeProfile)
        currentSession = s
        wsSessionId = s.hermesSessionId
        Log.i(TAG, "ensureActive: wsSessionId=${wsSessionId.take(8)}")
        return s
    }

    // ── CRUD ──

    fun switchSession(id: Long): SessionEntity? {
        val s = scope.let {
            // Use runBlocking-friendly approach: store result in scope
            var result: SessionEntity? = null
            scope.launch {
                sessionRepo.switchTo(id)
                result = sessionRepo.getByProfile(activeProfile).find { it.id == id }
                if (result != null) {
                    currentSession = result
                    wsSessionId = result!!.hermesSessionId
                    Log.i(TAG, "switchSession: wsSessionId=${wsSessionId.take(8)}")
                }
                refreshSessionList()
            }
            // We need a synchronous approach for the caller
            result
        }
        return s
    }

    // 供 Compose 回调使用的 suspend 版本
    suspend fun switchSessionAsync(id: Long): SessionEntity? {
        sessionRepo.switchTo(id)
        val s = sessionRepo.getByProfile(activeProfile).find { it.id == id }
        if (s != null) {
            currentSession = s
            wsSessionId = s.hermesSessionId
            Log.i(TAG, "switchSession: wsSessionId=${wsSessionId.take(8)}")
        }
        refreshSessionList()
        return s
    }

    suspend fun newSession(): SessionEntity {
        val count = sessionRepo.getByProfile(activeProfile).size
        val s = sessionRepo.create("${AppSettings.getProfileName(activeProfile)} 会话 ${count + 1}", activeProfile)
        currentSession = s
        wsSessionId = s.hermesSessionId
        Log.i(TAG, "newSession: wsSessionId=${wsSessionId.take(8)}")
        refreshSessionList(); refreshDashboard()
        return s
    }

    suspend fun deleteSession(id: Long): SessionEntity? {
        val all = sessionRepo.getByProfile(activeProfile)
        if (all.size <= 1) return null
        msgRepo.deleteBySession(id); sessionRepo.delete(id)
        var result: SessionEntity? = null
        if (currentSession?.id == id) {
            val t = all.first { it.id != id }
            sessionRepo.switchTo(t.id)
            currentSession = t
            wsSessionId = t.hermesSessionId
            result = t
            Log.i(TAG, "deleteSession: wsSessionId=${wsSessionId.take(8)}")
        }
        refreshSessionList(); refreshDashboard()
        return result
    }

    fun renameSession(id: Long, name: String) {
        scope.launch { sessionRepo.rename(id, name); refreshSessionList() }
    }

    fun pinSession(id: Long, pinned: Boolean) {
        scope.launch { sessionRepo.pin(id, pinned); refreshSessionList() }
    }

    suspend fun archiveSession(id: Long): SessionEntity? {
        sessionRepo.archive(id, true); refreshSessionList()
        var result: SessionEntity? = null
        if (currentSession?.id == id) {
            val a = sessionRepo.getByProfile(activeProfile)
            if (a.isNotEmpty()) {
                sessionRepo.switchTo(a.first().id)
                currentSession = a.first()
                wsSessionId = a.first().hermesSessionId
                result = a.first()
            }
        }
        return result
    }

    fun unarchiveSession(id: Long) {
        scope.launch { sessionRepo.archive(id, false); refreshSessionList() }
    }

    // ── profile ──

    suspend fun switchProfile(newProfile: String): SessionEntity {
        if (newProfile == activeProfile) return currentSession ?: ensureActive()
        activeProfile = newProfile
        AppSettings.setActiveProfile(context, newProfile)
        val s = sessionRepo.ensureActive(newProfile)
        currentSession = s
        wsSessionId = s.hermesSessionId
        refreshSessionList(); refreshDashboard()
        return s
    }

    // ── search ──

    suspend fun jumpToSessionFromSearch(id: Long): SessionEntity? {
        if (currentSession?.id == id) return null
        return switchSessionAsync(id)
    }

    // ── export helpers ──

    fun getActiveProfileName(): String = AppSettings.getProfileName(activeProfile)
}
