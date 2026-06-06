package com.example.voiceassistant.service

import android.Manifest
import android.app.PendingIntent
import android.content.*
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * 手机端工具注册。
 * 工具定义 → system prompt 注入 → 执行 → 返回结果。
 */
object PhoneTools {

    private const val TAG = "PhoneTools"

    /** 所有已注册工具 */
    val allTools: List<ToolDef> = listOf(
        ToolDef("get_location", "获取用户当前 GPS 位置", emptyList()),
        ToolDef("get_battery", "获取电池电量百分比和充电状态", emptyList()),
        ToolDef("get_network", "获取当前网络连接类型", emptyList()),
        ToolDef("get_device_info", "获取设备型号、系统版本、屏幕分辨率", emptyList()),
        ToolDef("get_storage", "获取手机存储空间使用情况", emptyList()),
        ToolDef("open_url", "在手机浏览器打开 URL", listOf("url")),
        ToolDef("set_clipboard", "将文本写入剪贴板", listOf("text")),
        ToolDef("get_clipboard", "读取剪贴板内容", emptyList()),
        ToolDef("list_calendar_events", "获取最近 N 天的日历事件", listOf("days")),
        ToolDef("add_calendar_event", "添加日历事件", listOf("title", "start_time", "end_time", "description")),
        ToolDef("set_alarm", "设置闹钟（时间格式 HH:mm）", listOf("time", "label")),
        ToolDef("take_screenshot", "截取当前屏幕（需 Android 5+）", emptyList()),
        ToolDef("get_contacts", "搜索通讯录联系人", listOf("query")),
        ToolDef("call_number", "拨打电话（需用户确认）", listOf("number")),
        ToolDef("send_sms", "发送短信（需用户确认）", listOf("number", "text")),
        ToolDef("toggle_flashlight", "开关手电筒", listOf("on")),
        ToolDef("open_app", "打开指定 App（如: 高德地图、微信）", listOf("name")),
    )

    data class ToolDef(val name: String, val description: String, val params: List<String>)

    /** 生成注入到 system prompt 的工具说明 */
    fun buildSystemPrompt(): String = buildString {
        appendLine("\n---")
        appendLine("## 可用手机工具")
        appendLine("你可以通过以下格式调用手机端工具：")
        appendLine()
        appendLine("```tool_call")
        appendLine("{\"name\": \"工具名\", \"args\": {\"参数\": \"值\"}}")
        appendLine("```")
        appendLine()
        allTools.forEach { tool ->
            val params = if (tool.params.isEmpty()) "" else tool.params.joinToString(", ") { "<$it>" }
            appendLine("- **${tool.name}**${if (params.isNotEmpty()) "($params)" else ""}: ${tool.description}")
        }
        appendLine("---")
    }

    /** 解析 Hermes 回复中的 <tool_call> 块 */
    fun extractToolCall(text: String): ToolCall? {
        val regex = Regex("```tool_call\\s*\\n([\\d\\D]*?)```")
        val match = regex.find(text) ?: return null
        return try {
            val json = JSONObject(match.groupValues[1])
            val name = json.optString("name", "")
            val args = json.optJSONObject("args") ?: JSONObject()
            ToolCall(name, args)
        } catch (e: Exception) {
            Log.w(TAG, "parse tool_call failed: ${e.message}")
            null
        }
    }

    data class ToolCall(val name: String, val args: JSONObject)

    /** 执行工具，返回结果文本 */
    fun execute(context: Context, call: ToolCall): String = try {
        when (call.name) {
            "get_location" -> getLocation(context)
            "get_battery" -> getBattery(context)
            "get_network" -> getNetwork(context)
            "get_device_info" -> getDeviceInfo(context)
            "get_storage" -> getStorage()
            "open_url" -> openUrl(context, call.args.optString("url", ""))
            "set_clipboard" -> setClipboard(context, call.args.optString("text", ""))
            "get_clipboard" -> getClipboard(context)
            "list_calendar_events" -> listCalendarEvents(context, call.args.optInt("days", 7))
            "add_calendar_event" -> addCalendarEvent(context, call.args)
            "set_alarm" -> setAlarm(context, call.args.optString("time", ""), call.args.optString("label", "有数提醒"))
            "take_screenshot" -> "截图功能需要前台权限，暂不支持后台调用。"
            "get_contacts" -> getContacts(context, call.args.optString("query", ""))
            "call_number" -> callNumber(context, call.args.optString("number", ""))
            "send_sms" -> sendSms(context, call.args.optString("number", ""), call.args.optString("text", ""))
            "toggle_flashlight" -> toggleFlashlight(context, call.args.optBoolean("on", true))
            "open_app" -> openApp(context, call.args.optString("name", ""))
            else -> "未知工具: ${call.name}"
        }
    } catch (e: Exception) {
        "工具执行失败: ${e.message}"
    }

    // ── 工具实现 ──────────────────────────────

    private fun getLocation(ctx: Context): String {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (ctx is ComponentActivity) ActivityCompat.requestPermissions(ctx, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 0)
            return "已弹出位置权限授权框，请授权后再问我一次"
        }
        val lm = ctx.getSystemService<LocationManager>() ?: return "无法获取位置服务"
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: return "无法获取位置，请确保 GPS 已开启"
        return "位置: 纬度 ${"%.5f".format(loc.latitude)}, 经度 ${"%.5f".format(loc.longitude)}, 精度 ${"%.0f".format(loc.accuracy)}米"
    }

    private fun getBattery(ctx: Context): String {
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (scale > 0) level * 100 / scale else -1
        val status = when (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
            BatteryManager.BATTERY_STATUS_FULL -> "已充满"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
            else -> "未知"
        }
        return "电池: $pct%, 状态: $status"
    }

    private fun getNetwork(ctx: Context): String {
        val cm = ctx.getSystemService<android.net.ConnectivityManager>() ?: return "无法获取"
        val active = cm.activeNetworkInfo
        return if (active?.isConnected == true) "网络: ${active.typeName} (${active.subtypeName})" else "网络: 未连接"
    }

    private fun getDeviceInfo(ctx: Context): String {
        val dm = ctx.resources.displayMetrics
        return "设备: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, 屏幕: ${dm.widthPixels}x${dm.heightPixels} (${dm.densityDpi}dpi)"
    }

    private fun getStorage(): String {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.totalBytes
        val avail = stat.availableBytes
        return "存储: 可用 ${formatSize(avail)} / 总共 ${formatSize(total)}"
    }

    private fun openUrl(ctx: Context, url: String): String {
        if (url.isBlank()) return "URL 为空"
        val uri = Uri.parse(if (url.startsWith("http")) url else "https://$url")
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "已打开: $url"
    }

    private fun setClipboard(ctx: Context, text: String): String {
        if (text.isBlank()) return "文本为空"
        val cm = ctx.getSystemService<ClipboardManager>()!!
        cm.setPrimaryClip(ClipData.newPlainText("有数", text))
        return "已复制到剪贴板"
    }

    private fun getClipboard(ctx: Context): String {
        val cm = ctx.getSystemService<ClipboardManager>()!!
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: return "剪贴板为空"
        return "剪贴板内容: ${text.take(200)}"
    }

    private fun listCalendarEvents(ctx: Context, days: Int): String {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (ctx is ComponentActivity) ActivityCompat.requestPermissions(ctx, arrayOf(Manifest.permission.READ_CALENDAR), 0)
            return "已弹出日历权限授权框，请授权后再问我一次"
        }
        try {
            val start = System.currentTimeMillis()
            val end = start + days * 86400000L
            val uri = CalendarContract.Events.CONTENT_URI
            val proj = arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND)
            val sel = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
            val args = arrayOf(start.toString(), end.toString())
            val cursor = ctx.contentResolver.query(uri, proj, sel, args, "${CalendarContract.Events.DTSTART} ASC")
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            val sb = StringBuilder("最近 $days 天日历事件:\n")
            var count = 0
            cursor?.use {
                while (it.moveToNext() && count < 20) {
                    val title = it.getString(0) ?: "无标题"
                    val s = it.getLong(1)
                    val e = it.getLong(2)
                    sb.appendLine("- ${fmt.format(Date(s))}→${fmt.format(Date(e))}: $title")
                    count++
                }
            }
            return if (count == 0) "最近 $days 天无日历事件" else sb.toString()
        } catch (e: Exception) {
            return "读取日历失败: ${e.message}"
        }
    }

    private fun addCalendarEvent(ctx: Context, args: JSONObject): String {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (ctx is ComponentActivity) ActivityCompat.requestPermissions(ctx, arrayOf(Manifest.permission.WRITE_CALENDAR), 0)
            return "已弹出日历写入权限授权框，请授权后再问我一次"
        }
        try {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val title = args.optString("title", "事件")
            val start = fmt.parse(args.optString("start_time"))?.time ?: System.currentTimeMillis()
            val end = fmt.parse(args.optString("end_time"))?.time ?: (start + 3600000)
            val desc = args.optString("description", "")

            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                putExtra(CalendarContract.Events.DESCRIPTION, desc)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            return "已打开日历添加: $title (${args.optString("start_time")})"
        } catch (e: Exception) {
            return "添加日历失败: ${e.message}"
        }
    }

    private fun setAlarm(ctx: Context, time: String, label: String): String {
        try {
            val parts = time.split(":")
            val hour = parts[0].toInt()
            val minute = parts[1].toInt()
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            return "已设置闹钟: $time ($label)"
        } catch (e: Exception) {
            return "设置闹钟失败: ${e.message}"
        }
    }

    private fun getContacts(ctx: Context, query: String): String {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (ctx is ComponentActivity) ActivityCompat.requestPermissions(ctx, arrayOf(Manifest.permission.READ_CONTACTS), 0)
            return "已弹出通讯录权限授权框，请授权后再问我一次"
        }
        try {
            val uri = ContactsContract.Contacts.CONTENT_URI
            val proj = arrayOf(ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.HAS_PHONE_NUMBER)
            val sel = if (query.isNotBlank()) "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?" else null
            val selArgs = if (query.isNotBlank()) arrayOf("%$query%") else null
            val cursor = ctx.contentResolver.query(uri, proj, sel, selArgs, "${ContactsContract.Contacts.DISPLAY_NAME} ASC")
            val sb = StringBuilder(if (query.isNotBlank()) "搜索 \"$query\" 结果:\n" else "联系人列表:\n")
            var count = 0
            cursor?.use {
                while (it.moveToNext() && count < 15) {
                    val name = it.getString(0) ?: "未知"
                    sb.appendLine("- $name")
                    count++
                }
            }
            return if (count == 0) "未找到联系人" else sb.toString()
        } catch (e: Exception) {
            return "读取联系人失败: ${e.message}"
        }
    }

    private fun callNumber(ctx: Context, number: String): String {
        if (number.isBlank()) return "号码为空"
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$number")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        return "已打开拨号盘: $number"
    }

    private fun sendSms(ctx: Context, number: String, text: String): String {
        if (number.isBlank() || text.isBlank()) return "号码或内容为空"
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$number")
            putExtra("sms_body", text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        return "已打开短信编辑: 收件人 $number"
    }

    private fun toggleFlashlight(ctx: Context, on: Boolean): String {
        return if (on) "手电筒功能暂未实现直接控制" else "手电筒功能暂未实现"
    }

    private fun openApp(ctx: Context, name: String): String {
        if (name.isBlank()) return "请提供 App 名称"
        val pm = ctx.packageManager
        // 遍历所有已安装应用
        val packages = pm.getInstalledPackages(android.content.pm.PackageManager.GET_ACTIVITIES)
        val keywords = name.split(Regex("\\\\s+")).filter { it.length >= 2 }
        for (pi in packages) {
            val appInfo = pi.applicationInfo ?: continue
            val label = appInfo.loadLabel(pm).toString()
            val pkg = pi.packageName
            // 跳过系统组件
            if (label.isBlank()) continue
            Log.d(TAG, "openApp check: $label ($pkg)")
            if (label.equals(name, ignoreCase = true) || label.contains(name, ignoreCase = true) || pkg.contains(name, ignoreCase = true)
                || (keywords.isNotEmpty() && keywords.all { label.contains(it, ignoreCase = true) })) {
                val launch = pm.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return "已打开: $label"
                }
            }
        }
        return "未找到 App: $name"
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        else -> "%.1f KB".format(bytes / 1000.0)
    }
}
