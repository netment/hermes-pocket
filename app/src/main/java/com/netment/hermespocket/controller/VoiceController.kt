package com.netment.hermespocket.controller

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.netment.hermespocket.service.VoiceRecognitionEngine

/**
 * 录音 + ASR，写入 ChatController 的文本状态。
 */
class VoiceController(
    private val context: Context,
    private val chatController: ChatController
) {
    var isRecording by mutableStateOf(false)
    var liveText by mutableStateOf("")
    val engine = VoiceRecognitionEngine(context)

    suspend fun setup() {
        engine.onStatus = { chatController.connectionStatus = it }
        engine.onAsrResult = { result ->
            liveText = result
            chatController.accumulatedText =
                if (chatController.accumulatedText.isEmpty()) result
                else "${chatController.accumulatedText} $result"
        }
        engine.initialize()
    }

    fun startRecording() {
        liveText = ""
        chatController.accumulatedText = ""
        chatController.pendingText = ""
        chatController.lastTrainingSampleId = null
        chatController.pendingAudioPath = null
        engine.startListening()
        isRecording = true
        chatController.connectionStatus = "录音中"
    }

    fun stopRecording() {
        val wavPath = engine.stopListening()
        isRecording = false
        chatController.pendingText = chatController.accumulatedText
        chatController.connectionStatus = "就绪"
        chatController.pendingAudioPath = wavPath
    }

    fun resetRecording() {
        engine.stopListening()
        liveText = ""
        chatController.accumulatedText = ""
        chatController.pendingText = ""
        chatController.lastTrainingSampleId = null
        chatController.pendingAudioPath = null
        engine.startListening()
    }

    fun release() { engine.release() }
}
