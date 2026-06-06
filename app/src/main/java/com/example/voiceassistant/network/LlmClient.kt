package com.example.voiceassistant.network

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Hermes Agent API 客户端（OpenAI 兼容 / HTTP SSE 流式）。
 * 通过 adb reverse 连接：手机 localhost:8642 → PC Hermes API Server
 */
class LlmClient(
    private val apiKey: String,
    private val baseUrl: String = "http://localhost:8642",
    private val model: String = "hermes-agent"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val responseChannel = Channel<String>(Channel.BUFFERED)
    val statusChannel = Channel<String>(Channel.CONFLATED)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val messages = mutableListOf<JSONObject>()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    init {
        // 系统提示
        messages.add(JSONObject().apply {
            put("role", "system")
            put("content", "你是宗平的私人语音助手，通过 Hermes Agent 运行。你有完整的工具访问权限——可以操作文件、运行命令、搜索网络。用中文回复，简洁口语化。")
        })
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        messages.add(JSONObject().apply {
            put("role", "user")
            put("content", text)
        })

        scope.launch {
            statusChannel.send("思考中...")
            try {
                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray(messages))
                    put("stream", true)
                    put("temperature", 0.7)
                    put("max_tokens", 2048)
                }

                val request = Request.Builder()
                    .url("$baseUrl/v1/chat/completions")
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(JSON))
                    .build()

                val call = client.newCall(request)
                val response = call.execute()

                if (!response.isSuccessful) {
                    val errorMsg = response.body?.string() ?: "HTTP ${response.code}"
                    statusChannel.send("错误: $errorMsg")
                    return@launch
                }

                val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
                val fullText = StringBuilder()

                reader.forEachLine { line ->
                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ")
                        if (data == "[DONE]") return@forEachLine
                        try {
                            val json = JSONObject(data)
                            val choices = json.getJSONArray("choices")
                            if (choices.length() > 0) {
                                val delta = choices.getJSONObject(0).optJSONObject("delta")
                                val content = delta?.optString("content", "") ?: ""
                                if (content.isNotEmpty()) {
                                    fullText.append(content)
                                    // 实时流式发送（不在这个case，我们最后发完整文本）
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }

                if (fullText.isNotEmpty()) {
                    messages.add(JSONObject().apply {
                        put("role", "assistant")
                        put("content", fullText.toString())
                    })
                    responseChannel.send(fullText.toString())
                    statusChannel.send("就绪")
                }
            } catch (e: Exception) {
                statusChannel.send("失败: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun disconnect() {
        scope.cancel()
    }
}
