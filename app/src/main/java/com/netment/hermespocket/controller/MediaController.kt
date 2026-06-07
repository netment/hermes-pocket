package com.netment.hermespocket.controller

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.netment.hermespocket.network.HermesWebSocket
import com.netment.hermespocket.ui.*
import com.netment.hermespocket.util.FileUtils
import kotlinx.coroutines.*
import java.io.File

/**
 * 文件 / 图片 / 相机 / 分享处理。
 */
class MediaController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val chatController: ChatController,
) {
    companion object { private const val TAG = "MediaCtrl" }

    var pendingShareText by mutableStateOf<String?>(null)
    var pendingShareImageUri by mutableStateOf<Uri?>(null)
    var pendingCameraImageUri by mutableStateOf<Uri?>(null)
    var pendingTakePhoto by mutableStateOf(false)

    fun handleShareIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        when {
            (intent.type ?: "").startsWith("text/") -> {
                val t = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                val s = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                pendingShareText = if (s != null) "$s\n\n$t" else t
            }
            (intent.type ?: "").startsWith("image/") ->
                pendingShareImageUri = intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    fun processPendingShare() {
        pendingShareText?.let { text ->
            pendingShareText = null
            val d = "[分享] " + if (text.length > 200) text.take(200) + "…" else text
            val item = MessageItem.ChatMsg(ChatMessage(d, true))
            chatController.messages.add(0, item); chatController.saveMessage(item)
            HermesWebSocket.get()?.sendMessage(text, emptySet(), chatController.sessionController.wsSessionId)
        }
        pendingShareImageUri?.let { uri ->
            pendingShareImageUri = null
            val f = FileUtils.copyUriToCache(context, uri, "shared_image.jpg") ?: return@let
            val att = HermesWebSocket.Attachment(f.name, f.toURI().toString(), f.length(), "image/jpeg")
            val item = MessageItem.ChatMsg(ChatMessage("[分享了一张图片]", true, listOf(att)))
            chatController.messages.add(0, item); chatController.saveMessage(item)
            HermesWebSocket.get()?.sendMessage("[用户分享了一张图片]", emptySet(), chatController.sessionController.wsSessionId)
        }
    }

    fun handleFileUpload(uri: Uri) {
        val name = FileUtils.getFileName(context, uri) ?: "file"
        val file = FileUtils.copyUriToCache(context, uri, name) ?: return
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val msg = if (mime.startsWith("image/")) "[上传了图片: $name]"
                  else "[上传了文件: $name (${FileUtils.formatSize(file.length())})]"
        chatController.addOutgoingMessage(ChatMessage(msg, true,
            listOf(HermesWebSocket.Attachment(name, file.absolutePath, file.length(), mime))))
        scope.launch(Dispatchers.IO) {
            try {
                val r = HermesWebSocket.get()?.uploadFile(name, file.readBytes(), mime, AppSettings.getHttpUrl(context))
                if (r != null) withContext(Dispatchers.Main) { toast("文件上传成功") }
                else withContext(Dispatchers.Main) { toast("文件上传失败") }
            } catch (e: Exception) {
                Log.e(TAG, "File upload error", e)
                withContext(Dispatchers.Main) { toast("文件上传失败: ${e.message}") }
            }
        }
    }

    fun handleImagePick(uri: Uri) {
        val file = FileUtils.copyUriToCache(context, uri, "picked.jpg") ?: return
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        chatController.addOutgoingMessage(ChatMessage("[上传了一张图片]", true,
            listOf(HermesWebSocket.Attachment(file.name, file.toURI().toString(), file.length(), mime))))
        scope.launch(Dispatchers.IO) {
            val bytes = compressImage(file) ?: return@launch
            HermesWebSocket.get()?.uploadFile(file.name, bytes, "image/jpeg", AppSettings.getHttpUrl(context))
        }
    }

    fun launchCamera() {
        val dir = File(context.cacheDir, "photos"); dir.mkdirs()
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        pendingCameraImageUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun handleCameraPhoto(uri: Uri) {
        pendingCameraImageUri = null
        val file = FileUtils.copyUriToCache(context, uri, "photo.jpg") ?: return
        chatController.addOutgoingMessage(ChatMessage("[拍了一张照片]", true,
            listOf(HermesWebSocket.Attachment(file.name, file.toURI().toString(), file.length(), "image/jpeg"))))
        scope.launch(Dispatchers.IO) {
            val bytes = compressImage(file) ?: return@launch
            HermesWebSocket.get()?.uploadFile(file.name, bytes, "image/jpeg", AppSettings.getHttpUrl(context))
        }
    }

    private suspend fun compressImage(file: File): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            val maxDim = 1024
            val scale = if (opts.outWidth > opts.outHeight) opts.outWidth / maxDim else opts.outHeight / maxDim
            val sampleSize = if (scale < 1) 1 else scale.coerceAtMost(8)
            val loadOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            var bmp = BitmapFactory.decodeFile(file.absolutePath, loadOpts)
            if (bmp != null && (bmp.width > maxDim || bmp.height > maxDim)) {
                val r = maxDim.toFloat() / maxOf(bmp.width, bmp.height)
                val s = Bitmap.createScaledBitmap(bmp, (bmp.width * r).toInt(), (bmp.height * r).toInt(), true)
                if (s != bmp) bmp.recycle(); bmp = s
            }
            val baos = java.io.ByteArrayOutputStream()
            bmp?.compress(Bitmap.CompressFormat.JPEG, 70, baos); bmp?.recycle()
            baos.toByteArray()
        } catch (e: Exception) { Log.e(TAG, "compressImage", e); null }
    }

    private fun toast(msg: String) { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
}
