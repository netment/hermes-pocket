package com.netment.hermespocket.service

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceRecognitionEngine(private val context: Context) {
    companion object {
        private const val TAG = "VoiceEngine"
        private const val SAMPLE_RATE = 16000
        private const val MODEL_DIR = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09"
    }

    private var vad: Vad? = null
    private var recognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null

    @Volatile var isRecording = false
    var onAsrResult: ((String) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    var onAudioSaved: ((String) -> Unit)? = null  // callback: path to saved WAV

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val audioBuffer = mutableListOf<Short>()  // raw PCM samples

    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            onStatus?.invoke("初始化 VAD...")
            Log.i(TAG, "initVad: using silero_vad.onnx from assets")
            val vadConfig = getVadModelConfig(type = 0)!!
            vad = Vad(assetManager = context.assets, config = vadConfig)
            Log.i(TAG, "initVad done")

            onStatus?.invoke("初始化 ASR...")
            Log.i(TAG, "initAsr: using $MODEL_DIR from assets")

            val asrConfig = OfflineRecognizerConfig(
                featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig().apply {
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = "$MODEL_DIR/model.int8.onnx",
                        useInverseTextNormalization = true
                    )
                    tokens = "$MODEL_DIR/tokens.txt"
                }
            )
            recognizer = OfflineRecognizer(assetManager = context.assets, config = asrConfig)
            Log.i(TAG, "initAsr done")

            onStatus?.invoke("就绪")
        } catch (e: Exception) {
            Log.e(TAG, "init failed", e)
            onStatus?.invoke("失败: ${e.message}")
            throw e
        }
    }

    fun startListening() {
        if (isRecording) return
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2
        )
        audioRecord?.startRecording()
        isRecording = true
        audioBuffer.clear()
        vad?.reset()
        scope.launch { processAudioLoop() }
    }

    fun stopListening(): String? {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        // Save accumulated audio to WAV
        return if (audioBuffer.isNotEmpty()) {
            saveAudioAsWav()
        } else null
    }

    private suspend fun processAudioLoop() {
        val buffer = ShortArray(512)
        while (isRecording) {
            val ret = audioRecord?.read(buffer, 0, buffer.size) ?: break
            if (ret <= 0) continue
            // Accumulate raw PCM for later saving
            for (i in 0 until ret) audioBuffer.add(buffer[i])
            val samples = FloatArray(ret) { buffer[it] / 32768.0f }
            if (vad == null || recognizer == null) continue
            vad!!.acceptWaveform(samples)
            while (!vad!!.empty()) {
                val segment = vad!!.front()
                val stream = recognizer!!.createStream()
                stream.acceptWaveform(segment.samples, SAMPLE_RATE)
                recognizer!!.decode(stream)
                val result = recognizer!!.getResult(stream)
                stream.release()
                vad!!.pop()
                if (result.text.isNotBlank()) {
                    Log.i(TAG, "ASR: ${result.text}")
                    withContext(Dispatchers.Main) { onAsrResult?.invoke(result.text) }
                }
            }
        }
    }

    private fun saveAudioAsWav(): String {
        val dir = File(context.getExternalFilesDir(null), "training")
        dir.mkdirs()
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val wavFile = File(dir, "$timestamp.wav")

        val numSamples = audioBuffer.size
        val dataSize = numSamples * 2  // 16-bit = 2 bytes per sample
        val fileSize = 44 + dataSize

        FileOutputStream(wavFile).use { fos ->
            val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
            // RIFF header
            buf.put("RIFF".toByteArray())
            buf.putInt(fileSize - 8)
            buf.put("WAVE".toByteArray())
            // fmt chunk
            buf.put("fmt ".toByteArray())
            buf.putInt(16)           // chunk size
            buf.putShort(1)          // PCM format
            buf.putShort(1)          // mono
            buf.putInt(SAMPLE_RATE)
            buf.putInt(SAMPLE_RATE * 2)  // byte rate
            buf.putShort(2)          // block align
            buf.putShort(16)         // bits per sample
            // data chunk
            buf.put("data".toByteArray())
            buf.putInt(dataSize)
            // PCM samples
            for (sample in audioBuffer) {
                buf.putShort(sample)
            }
            fos.write(buf.array())
        }

        Log.i(TAG, "Saved audio: ${wavFile.absolutePath} (${numSamples} samples, ${dataSize} bytes)")
        val path = wavFile.absolutePath
        scope.launch(Dispatchers.Main) { onAudioSaved?.invoke(path) }
        return path
    }

    fun release() {
        stopListening()
        scope.cancel()
        recognizer?.release()
    }
}
