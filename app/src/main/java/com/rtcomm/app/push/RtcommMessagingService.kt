package com.rtcomm.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.rtcomm.app.data.AppState
import com.rtcomm.app.data.AuthStore
import com.rtcomm.app.notify.Notify

/**
 * 接收 FCM data-only 消息，复用本地 [Notify] 渲染通知（WS 断开/进程被杀时的兜底）。
 *
 * 关键：应用被杀后本服务会在**全新进程**中被系统拉起，此时 AppState 为空。
 * 这里按需从 [AuthStore] 恢复登录用户并 signIn，使 [Notify.showMessage] 的登录态
 * 校验通过；通知的隐私预览 / 免打扰 / 单会话静音等仍由 Notify 依本地偏好裁决。
 *
 * 服务端只推送给「无 WS 连接」的收件人（已排除发送者与机器人），故这里无需再判重。
 */
class RtcommMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        PushRegistrar.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] != "new_message") return
        val convId = data["conversationId"]?.takeIf { it.isNotBlank() } ?: return

        if (!ensureSignedIn()) return
        Notify.ensureChannels(applicationContext)

        // App 正在前台且正在看这个会话：交给应用内提示，不发系统通知。
        if (AppState.inForeground.value && convId == AppState.activeConversationId.value) return

        val conversationName = data["conversationName"].orEmpty()
        val senderName = data["senderName"].orEmpty()
        val conversationType = data["conversationType"].orEmpty()
        val messageType = data["messageType"].orEmpty().ifEmpty { "text" }
        val preview = data["preview"].orEmpty()

        val title = conversationName.ifBlank { senderName }.ifBlank { "新消息" }
        val text = preview.ifBlank { placeholderFor(messageType) }
        // 群聊带上发送者名；单聊标题已是对方昵称，无需重复。
        val body = if (conversationType == "group" && senderName.isNotBlank()) "$senderName：$text" else text

        Notify.showMessage(applicationContext, title, body, convId)
    }

    /** 全新进程中恢复登录态；无本地会话则返回 false（忽略此推送）。 */
    private fun ensureSignedIn(): Boolean {
        if (AppState.currentUser.value != null) return true
        val auth = AuthStore(applicationContext)
        val user = auth.user() ?: return false
        if (auth.token().isBlank()) return false
        auth.applyToApi()
        AppState.signIn(user)
        return true
    }

    private fun placeholderFor(messageType: String): String = when (messageType) {
        "image" -> "[图片]"
        "file" -> "[文件]"
        "audio", "voice" -> "[语音]"
        "video" -> "[视频]"
        else -> "新消息"
    }
}
