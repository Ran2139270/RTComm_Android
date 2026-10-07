package com.rtcomm.app.notify

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.rtcomm.app.R
import com.rtcomm.app.data.AuthStore
import com.rtcomm.app.data.Api
import com.rtcomm.app.data.AppState
import com.rtcomm.app.data.WsBus
import com.rtcomm.app.data.WsEvent
import com.rtcomm.app.ws.WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台服务：登录后启动，保持 WebSocket 长连接（进程存活期间），
 * 收到 new_message 且该会话不在前台时发本地通知。
 * 用户在「我的-通用」里关闭「自动连接实时通道」时停止。
 */
class RealtimeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var auth: AuthStore

    override fun onCreate() {
        super.onCreate()
        Notify.ensureChannels(this)
        // Android 12+ 后台启动 FGS、Android 14+ dataSync 配额都可能让 startForeground 抛异常；
        // 兜底后主动退出，避免「startForegroundService 未调 startForeground」直接崩溃。
        val foregroundStarted = runCatching {
            startForeground(Notify.ID_SERVICE, buildServiceNotification())
        }.isSuccess
        if (!foregroundStarted) {
            stopSelf()
            return
        }
        auth = AuthStore(applicationContext)
        auth.applyToApi()
        val user = auth.user()
        // 通知总开关关闭时服务没有存在的意义，直接退出，避免“关掉开关仍在后台连网/通知”。
        if (auth.token().isBlank() || user == null || !auth.autoConnectWs || !auth.notificationsEnabled) {
            stopSelf()
            return
        }
        AppState.signIn(user)
        // 必须先订阅事件总线，再启动连接：否则启动窗口内的 new_message 会静默丢失。
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            WsBus.events.collect { e -> handleEvent(e) }
        }
        if (!WsClient.isConnected()) WsClient.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
        // 服务销毁后若 App 不在前台，连接没有继续存在的理由：
        // 停掉 socket 与重连执行器，避免服务退出后仍残留长连接/重连循环。
        if (!AppState.inForeground.value) WsClient.stop()
    }

    private fun handleEvent(e: WsEvent) {
        if (e.type != "new_message") return
        // 每次通知前重新校验总开关，防止服务存活期间用户关闭通知后仍被打扰。
        if (!auth.notificationsEnabled) return
        val convId = e.data["conversationId"] as? String ?: return
        val msg = WsClient.lastMessageOf(convId) ?: return
        if (msg.senderId == AppState.currentUser.value?.id) return
        if (convId == AppState.activeConversationId.value) return
        // App 在前台：由应用内横幅（MainScaffold InAppBanner）提示，不发系统通知
        if (AppState.inForeground.value) return
        val conv = AppState.conversations.value.firstOrNull { it.id == convId }
        val title = conv?.name ?: "新消息"
        val body = when {
            msg.isDeleted -> "消息已撤回"
            msg.isSticker -> "[动画表情]"
            msg.messageType == "image" -> "[图片]"
            msg.messageType == "video" -> "[视频]"
            msg.messageType == "audio" -> "[语音]"
            msg.messageType == "file" -> "[文件] " + (msg.file?.fileName ?: "")
            else -> com.rtcomm.app.ui.common.Format.mdToPlain(msg.content)
        }
        Notify.showMessage(this, title, body.ifBlank { "新消息" }, convId)
    }

    private fun buildServiceNotification(): Notification {
        val pi = android.app.PendingIntent.getActivity(
            this, 0,
            android.content.Intent(this, com.rtcomm.app.MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return androidx.core.app.NotificationCompat.Builder(this, Notify.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_msg)
            .setContentTitle(getString(R.string.notif_service_title))
            .setContentText(getString(R.string.notif_service_text))
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, RealtimeService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RealtimeService::class.java)) }
        }
    }
}
