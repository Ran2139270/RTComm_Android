package com.rtcomm.app.data

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 待发媒体（图片/视频/文件）的上传管理器。
 *
 * 与「发送文本」不同，媒体需要先上传再发送。这里把上传放到**应用级协程**，
 * 因此退出会话、旋转屏幕都不会中断；进度写入 [AppState]（并随消息缓存持久化），
 * 重启后进入会话可自动续传未完成的上传。
 *
 * 每次上传都从本地源文件复制一份临时文件再上传：FileTransfer.upload 结束时会删除
 * 传入文件，复制可保证失败后本地源仍在、可重试。
 */
object UploadManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

    fun isUploading(localId: String): Boolean = jobs[localId]?.isActive == true

    /**
     * 开始/重试某个待发媒体的上传。
     * 用 @Synchronized + LAZY 启动：先登记 Job 再启动，避免「任务瞬间跑完后 cleanup
     * 与登记互相踩踏」的 check-then-put 竞态（TOCTOU）。
     */
    @Synchronized
    fun enqueue(conversationId: String, localId: String) {
        if (jobs[localId]?.isActive == true) return
        val flag = AtomicBoolean(false)
        cancelFlags[localId] = flag
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val msg = AppState.messagesOf(conversationId).firstOrNull { it.id == localId } ?: return@launch
            val srcPath = msg.localUploadPath
            val src = srcPath?.let { File(it) }
            if (src == null || !src.exists()) {
                AppState.setUploadFailed(conversationId, localId)
                cleanup(localId)
                return@launch
            }
            val temp = File(src.parentFile ?: src, "up_${localId}_${System.currentTimeMillis()}.part")
            var lastPersist = 0f
            try {
                src.copyTo(temp, overwrite = true)
                val picked = FileTransfer.Picked(
                    file = temp,
                    name = msg.file?.fileName ?: src.name,
                    size = src.length(),
                    mime = msg.file?.mimeType ?: "application/octet-stream",
                )
                val meta = FileTransfer.upload(
                    picked = picked,
                    conversationId = conversationId,
                    cancelled = { flag.get() },
                ) { done, total ->
                    val progress = if (total > 0) done.toFloat() / total else 0f
                    // 进度回调很密集：默认只改内存，每 10% 落盘一次。
                    val persist = progress - lastPersist >= 0.1f || progress >= 1f
                    if (persist) lastPersist = progress
                    AppState.updateUploadProgress(conversationId, localId, progress, total, persist)
                }
                val sent = Api.sendMessage(
                    conversationId = conversationId,
                    // 用 pending 的 content：表情为 "[表情]"、语音为 "[语音]"，普通媒体为文件名。
                    content = msg.content?.ifBlank { meta.fileName } ?: meta.fileName,
                    messageType = msg.messageType,
                    fileId = meta.id,
                    replyToMessageId = msg.replyToMessageId,
                    clientMessageId = localId,
                )
                if (sent != null) {
                    AppState.confirmPending(conversationId, localId, sent)
                    AppState.bumpConversation(conversationId, sent, false)
                    deleteSource(msg)
                } else {
                    AppState.setUploadFailed(conversationId, localId)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                AppState.setUploadFailed(conversationId, localId)
            } finally {
                temp.delete()
                cleanup(localId)
            }
        }
        jobs[localId] = job
        job.start()
    }

    /** 取消上传：中断任务、删除本地文件并移除占位消息。 */
    fun cancel(conversationId: String, localId: String) {
        cancelFlags[localId]?.set(true)
        jobs[localId]?.cancel()
        val msg = AppState.messagesOf(conversationId).firstOrNull { it.id == localId }
        if (msg != null) deleteSource(msg)
        AppState.removePending(conversationId, localId)
        cleanup(localId)
    }

    private fun cleanup(localId: String) {
        jobs.remove(localId)
        cancelFlags.remove(localId)
    }

    private fun deleteSource(m: Message) {
        runCatching { m.localUploadPath?.let { File(it).delete() } }
        runCatching {
            m.localPreviewUri?.let { uri ->
                Uri.parse(uri).path?.let { File(it).delete() }
            }
        }
    }
}
