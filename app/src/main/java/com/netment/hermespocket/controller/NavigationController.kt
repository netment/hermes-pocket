package com.netment.hermespocket.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 屏幕路由状态机。
 */
class NavigationController {

    var showSettings by mutableStateOf(false)
    var showSearch by mutableStateOf(false)
    var showDashboard by mutableStateOf(false)
    var showSessionList by mutableStateOf(true)
    var sessionSearchMode by mutableStateOf(false)

    fun navigateToSettings() {
        showSettings = true; showSessionList = false
    }

    fun navigateToChat() {
        showSessionList = false; showSearch = false; showDashboard = false; showSettings = false
    }

    fun navigateToSessionList() {
        showSessionList = true; showSearch = false; showDashboard = false; showSettings = false
    }

    fun navigateToDashboard() {
        showSettings = false; showDashboard = true
    }

    fun navigateToSearch(sessionScoped: Boolean = false) {
        showSearch = true; sessionSearchMode = sessionScoped; showSessionList = false
    }
}
