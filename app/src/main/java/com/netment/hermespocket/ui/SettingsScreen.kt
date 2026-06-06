package com.netment.hermespocket.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.ui.unit.sp

// ── AppSettings: Profile-based configuration ──────

object AppSettings {
    const val PREFS_NAME = "hermes_settings"

    // Legacy keys (keep for migration)
    private const val KEY_WS_URL = "ws_url"
    private const val KEY_HTTP_URL = "http_url"

    // Profile keys
    private const val KEY_ACTIVE_PROFILE = "active_profile"
    private const val KEY_DARK_THEME = "dark_theme"
    private const val KEY_TTS_ENABLED = "tts_enabled"

    // Default server URLs for each profile — change these to your own Hermes gateway
    const val DEFAULT_WORK_WS = "ws://your-server-ip:8643"
    const val DEFAULT_WORK_HTTP = "http://your-server-ip:8643"
    const val DEFAULT_HOME_WS = "ws://your-server-ip:8644"
    const val DEFAULT_HOME_HTTP = "http://your-server-ip:8644"

    val ALL_PROFILES = listOf("work", "home")

    fun getProfileName(profile: String): String = when (profile) {
        "home" -> "家里"
        else -> "工作"
    }

    // ── Active profile ─────────────────────────

    fun getActiveProfile(ctx: Context): String {
        // Migrate from legacy settings if needed
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val active = prefs.getString(KEY_ACTIVE_PROFILE, null)
        if (active != null) return active

        // First launch: try legacy URL, otherwise default to "work"
        val legacyWs = prefs.getString(KEY_WS_URL, null)
        if (legacyWs != null) {
            // Migrate legacy URL to work profile
            val legacyHttp = prefs.getString(KEY_HTTP_URL, DEFAULT_WORK_HTTP) ?: DEFAULT_WORK_HTTP
            prefs.edit()
                .putString("profile_work_ws", legacyWs)
                .putString("profile_work_http", legacyHttp)
                .putString(KEY_ACTIVE_PROFILE, "work")
                .remove(KEY_WS_URL).remove(KEY_HTTP_URL)
                .apply()
            return "work"
        }
        return "work"
    }

    fun setActiveProfile(ctx: Context, profile: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE_PROFILE, profile).apply()
    }

    // ── Profile URLs ───────────────────────────

    fun getWsUrl(ctx: Context): String = getWsUrl(ctx, getActiveProfile(ctx))

    fun getWsUrl(ctx: Context, profile: String): String {
        val default = if (profile == "home") DEFAULT_HOME_WS else DEFAULT_WORK_WS
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("profile_${profile}_ws", default) ?: default
    }

    fun getHttpUrl(ctx: Context): String = getHttpUrl(ctx, getActiveProfile(ctx))

    fun getHttpUrl(ctx: Context, profile: String): String {
        val default = if (profile == "home") DEFAULT_HOME_HTTP else DEFAULT_WORK_HTTP
        val raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("profile_${profile}_http", default) ?: default
        // Normalize: if user accidentally saved ws:// in the HTTP field
        return raw.replace(Regex("^ws://"), "http://").replace(Regex("^wss://"), "https://")
    }

    fun saveProfile(ctx: Context, profile: String, wsUrl: String, httpUrl: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("profile_${profile}_ws", wsUrl)
            .putString("profile_${profile}_http", httpUrl)
            .apply()
    }

    // ── Theme ───────────────────────────────────

    fun isDarkTheme(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_DARK_THEME, true)

    fun setDarkTheme(ctx: Context, dark: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_DARK_THEME, dark).apply()
    }

    fun isTtsEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_TTS_ENABLED, false)

    fun setTtsEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_TTS_ENABLED, enabled).apply()
    }

    // ── 备份/恢复 ──────────────────────────────

    fun exportSettings(ctx: Context): Map<String, String> {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val map = mutableMapOf<String, String>()
        // 逐 key 导出，排除系统 key
        for (key in prefs.all.keys) {
            val value = prefs.all[key]?.toString() ?: continue
            map[key] = value
        }
        return map
    }

    fun importSettings(ctx: Context, settings: Map<String, String>) {
        val editor = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        editor.clear()
        for ((key, value) in settings) {
            // 尝试恢复 boolean 类型
            when (value) {
                "true" -> editor.putBoolean(key, true)
                "false" -> editor.putBoolean(key, false)
                else -> editor.putString(key, value)
            }
        }
        editor.apply()
    }
}

// ── SettingsScreen ─────────────────────────────

data class CapabilityInfo(
    val name: String,
    val icon: String = "📦",
    val description: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDashboard: (() -> Unit)? = null,
    onExportBackup: (() -> Unit)? = null,
    onImportBackup: (() -> Unit)? = null,
    capabilities: List<CapabilityInfo> = emptyList(),
    selectedSkills: Set<String> = emptySet(),
    onToggleSkill: ((String) -> Unit)? = null,
    currentProfile: String = "work",
    onSwitchProfile: ((String) -> Unit)? = null,
    ttsEnabled: Boolean = false,
    onToggleTts: ((Boolean) -> Unit)? = null
) {
    val ctx = LocalContext.current

    var workWs by remember { mutableStateOf(AppSettings.getWsUrl(ctx, "work")) }
    var workHttp by remember { mutableStateOf(AppSettings.getHttpUrl(ctx, "work")) }
    var homeWs by remember { mutableStateOf(AppSettings.getWsUrl(ctx, "home")) }
    var homeHttp by remember { mutableStateOf(AppSettings.getHttpUrl(ctx, "home")) }

    var workSaved by remember { mutableStateOf(false) }
    var homeSaved by remember { mutableStateOf(false) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color(0xFF2563EB),
        unfocusedBorderColor = Color(0xFF334155),
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedLabelColor = Color(0xFF93C5FD),
        unfocusedLabelColor = Color(0xFF64748B),
        focusedContainerColor = Color(0xFF1E293B),
        unfocusedContainerColor = Color(0xFF1E293B),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A2E), titleContentColor = Color.White
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回", tint = Color(0xFF94A3B8))
                    }
                }
            )
        },
        containerColor = Color(0xFF0F0F1A)
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── 当前 Profile ──
            Text("当前模式", color = Color(0xFF94A3B8), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppSettings.ALL_PROFILES.forEach { p ->
                    val isSelected = p == currentProfile
                    val name = AppSettings.getProfileName(p)
                    val emoji = if (p == "home") "🏠" else "🏢"
                    val desc = if (p == "home") "家里服务器" else "公司服务器"
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) Color(0xFF1E3A5F) else Color(0xFF1E293B),
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF3B82F6))
                                  else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (p != currentProfile) onSwitchProfile?.invoke(p)
                        }
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(emoji, fontSize = 36.sp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, color = if (isSelected) Color.White else Color(0xFFE2E8F0),
                                        fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                    if (isSelected) {
                                        Spacer(Modifier.width(8.dp))
                                        Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFF3B82F6)) {
                                            Text("当前", color = Color.White, fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                                        }
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(desc, color = if (isSelected) Color(0xFF93C5FD) else Color(0xFF64748B),
                                    fontSize = 12.sp)
                            }
                            if (isSelected) {
                                Text("✓", color = Color(0xFF3B82F6), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp).fillMaxWidth().background(Color(0xFF1E293B)))

            // ── 工作 Profile ──
            ProfileCard(
                title = "🏢 工作",
                wsUrl = workWs, onWsChange = { workWs = it; workSaved = false },
                httpUrl = workHttp, onHttpChange = { workHttp = it; workSaved = false },
                saved = workSaved,
                onSave = {
                    AppSettings.saveProfile(ctx, "work", workWs, workHttp)
                    workSaved = true
                },
                fieldColors = fieldColors
            )

            // ── 家里 Profile ──
            ProfileCard(
                title = "🏠 家里",
                wsUrl = homeWs, onWsChange = { homeWs = it; homeSaved = false },
                httpUrl = homeHttp, onHttpChange = { homeHttp = it; homeSaved = false },
                saved = homeSaved,
                onSave = {
                    AppSettings.saveProfile(ctx, "home", homeWs, homeHttp)
                    homeSaved = true
                },
                fieldColors = fieldColors
            )

            Spacer(Modifier.height(4.dp))

            // ── 数据仪表盘入口 ──
            if (onOpenDashboard != null) {
                Button(
                    onClick = onOpenDashboard,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("📊 数据仪表盘", fontSize = 15.sp, color = Color(0xFF93C5FD))
                }
            }

            // ── 能力管理 ──
            if (capabilities.isNotEmpty()) {
                Text("能力管理", color = Color(0xFF94A3B8), fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp))

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF1A1A2E),
                    modifier = Modifier.fillMaxWidth()
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(14.dp))
                ) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        val sorted = capabilities.sortedWith(compareByDescending<CapabilityInfo> { it.name in selectedSkills }.thenBy { it.name })
                        sorted.forEach { cap ->
                            val checked = cap.name in selectedSkills
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { onToggleSkill?.invoke(cap.name) }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(cap.icon, fontSize = 16.sp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(cap.name, color = if (checked) Color(0xFF4ADE80) else Color.White, fontSize = 14.sp)
                                    if (cap.description.isNotBlank()) {
                                        Text(cap.description, color = Color(0xFF64748B), fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                                Switch(
                                    checked = checked,
                                    onCheckedChange = { onToggleSkill?.invoke(cap.name) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF4ADE80),
                                        uncheckedThumbColor = Color(0xFF64748B),
                                        uncheckedTrackColor = Color(0xFF374151),
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // ── TTS 语音播放 ──
            Text("语音播放", color = Color(0xFF94A3B8), fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp))
            Row(
                Modifier.fillMaxWidth()
                    .clickable { onToggleTts?.invoke(!ttsEnabled) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🔊", fontSize = 16.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("CosyVoice 语音合成", color = if (ttsEnabled) Color(0xFF4ADE80) else Color.White, fontSize = 14.sp)
                    Text("使用 CosyVoice2 将回复转为语音播放", color = Color(0xFF64748B), fontSize = 11.sp, maxLines = 1)
                }
                Switch(
                    checked = ttsEnabled,
                    onCheckedChange = { onToggleTts?.invoke(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF4ADE80),
                        uncheckedThumbColor = Color(0xFF64748B),
                        uncheckedTrackColor = Color(0xFF374151),
                    )
                )
            }

            // ── 备份与恢复 ──
            Text("备份与恢复", color = Color(0xFF94A3B8), fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { onExportBackup?.invoke() },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF065F46)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("📤 备份数据", fontSize = 14.sp, color = Color(0xFF6EE7B7))
                }

                Button(
                    onClick = { onImportBackup?.invoke() },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C2D12)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("📥 恢复数据", fontSize = 14.sp, color = Color(0xFFFCA5A5))
                }
            }

            Spacer(Modifier.weight(1f))

            Text(
                "所有连接均通过 FRP 转发。切换 Profile 后返回主界面即可生效。",
                color = Color(0xFF64748B), fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ProfileCard(
    title: String,
    wsUrl: String, onWsChange: (String) -> Unit,
    httpUrl: String, onHttpChange: (String) -> Unit,
    saved: Boolean, onSave: () -> Unit,
    fieldColors: TextFieldColors
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1A1A2E),
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(14.dp))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = wsUrl,
                onValueChange = onWsChange,
                label = { Text("WebSocket") },
                placeholder = { Text("ws://your-server-ip:8643", color = Color(0xFF64748B)) },
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors,
                shape = RoundedCornerShape(10.dp),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
            )

            OutlinedTextField(
                value = httpUrl,
                onValueChange = onHttpChange,
                label = { Text("HTTP（文件下载）") },
                placeholder = { Text("http://your-server-ip:8643", color = Color(0xFF64748B)) },
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors,
                shape = RoundedCornerShape(10.dp),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
            )

            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().height(42.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(if (saved) "✓ 已保存" else "保存", fontSize = 14.sp)
            }
        }
    }
}
