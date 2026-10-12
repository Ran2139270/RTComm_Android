package com.rtcomm.app.ws

import com.google.gson.Gson
import com.rtcomm.app.data.Api
import com.rtcomm.app.data.AppState
import com.rtcomm.app.data.Message
import com.rtcomm.app.data.WsBus
import com.rtcomm.app.data.WsEvent
import com.rtcomm.app.data.WsState
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 实时消息 WebSocket 客户端。
 *
 * 认证通过 WebSocket 握手 Authorization 头传递，令牌不再出现在 URL、代理日志或缓存键中。
 *
 * 特性：
 * - 幂等启动：已连接/正在连接时重复调用 [start] 不会建立第二条连接
 * - 连接代际：每次新建/关闭连接递增 generation，陈旧 socket 的回调不再改状态或触发重连
 * - 指数退避自动重连（1s → 30s，带抖动），绝不快速死循环
 * - 生命周期感知：pause() 进后台暂停重连；resume() 回前台恢复
 * - 常规事件写入 AppState；全部事件转发到 WsBus 供屏幕订阅
 * - 心跳 ping 保活
 */
object WsClient {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 长连接
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var attempt = 0

    /** 连接代际：每次新建或关闭连接时递增。 */
    @Volatile private var generation = 0L

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ws-reconnect").apply { isDaemon = true }
    }
    @Volatile private var pending: ScheduledFuture<*>? = null

    /** 启动（登录成功后调用）。幂等：已有连接/正在连接时直接返回。 */
    @Synchronized
    fun start() {
        if (running && !paused && ws != null) return
        if (running && !paused && AppState.wsState.value == WsState.Connecting) return
        running = true
        paused = false
        attempt = 0
        // 清掉可能残留的重连任务：否则它与本次 connect() 竞态，可能开出重复连接。
        pending?.cancel(false); pending = null
        connect()
    }

    /** 完全停止（登出时调用）。 */
    @Synchronized
    fun stop() {
        running = false
        paused = false
        pending?.cancel(false); pending = null
        closeSocket()
        AppState.wsState.value = WsState.Disconnected
    }

    /** 进入后台：服务持有连接时继续实时接收；否则暂停以节省资源。 */
    @Synchronized
    fun pause(keepAlive: Boolean = false) {
        if (!running || keepAlive) return
        paused = true
        pending?.cancel(false); pending = null
        closeSocket()
        AppState.wsState.value = WsState.Disconnected
    }

    /** 回到前台：恢复连接。 */
    @Synchronized
    fun resume() {
        if (!running) return
        if (!paused) return
        paused = false
        attempt = 0
        connect()
    }

    private fun closeSocket() {
        generation++ // 失效旧 socket 的一切回调
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
    }

    @Synchronized
    private fun connect() {
        if (!running || paused) return
        if (Api.token.isEmpty()) return
        val gen = ++generation
        AppState.wsState.value = WsState.Connecting
        val url = "${Api.baseUrl.trimEnd('/')}/ws"
        ws = client.newWebSocket(
            Request.Builder().url(url).addHeader("Authorization", "Bearer ${Api.token}").build(),
            listenerFor(gen),
        )
    }

    /** 每个连接一个带代际的监听器：只有当前代际才允许改状态/安排重连。 */
    private fun listenerFor(gen: Long) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (gen != generation) {
                runCatching { webSocket.close(1000, "stale") }
                return
            }
            attempt = 0
            AppState.wsState.value = WsState.Connected
            // 重连后补拉会话列表：离线期间其它端的已读/未读变化在此自愈。
            AppState.refreshConversations()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != generation) return
            val ev = parse(text) ?: return
            val type = (ev["event"] as? String) ?: return
            @Suppress("UNCHECKED_CAST")
            val data = (ev["data"] as? Map<String, Any?>) ?: emptyMap()
            dispatch(type, data)
            WsBus.emit(WsEvent(type, data))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (gen != generation) return
            if (ws === webSocket) ws = null
            AppState.wsState.value = WsState.Disconnected
            // 4003：服务端因禁用 / 改密 / 踢下线主动断开。立即停止重连并清登录态，回登录页。
            if (code == 4003) {
                running = false
                pending?.cancel(false); pending = null
                Api.onUnauthorized?.invoke()
                return
            }
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (gen != generation) return
            if (ws === webSocket) ws = null
            AppState.wsState.value = WsState.Disconnected
            scheduleReconnect()
        }
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (!running || paused) return
        attempt = (attempt + 1).coerceAtMost(8)
        val base = (1000L shl (attempt - 1)).coerceAtMost(30_000L) // 1s,2s,4s...上限 30s
        val jitter = Random.nextLong(0, 1000)
        val delay = base + jitter
        pending?.cancel(false)
        pending = scheduler.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
    }

    /** 发送帧；返回 false 代表调用方应使用 REST 兜底或提示稍后重试。 */
    fun send(event: String, data: Map<String, Any?> = emptyMap()): Boolean {
        val sock = ws ?: return false
        val frame = mapOf("event" to event, "data" to data)
        return try { sock.send(gson.toJson(frame)) } catch (_: Exception) { false }
    }

    /** 当前是否已建立连接（供调用方决定 WS / REST 兜底路径）。 */
    fun isConnected(): Boolean = AppState.wsState.value == com.rtcomm.app.data.WsState.Connected

    /** 常规事件写入 AppState；其余事件（通话/设备等）由屏幕通过 WsBus 消费。 */
    private fun dispatch(type: String, data: Map<String, Any?>) {
        when (type) {
            "connected" -> AppState.wsState.value = WsState.Connected
            "new_message" -> {
                val convId = data["conversationId"] as? String ?: return
                val msg = toMessage(data["message"]) ?: return
                val meId = AppState.currentUser.value?.id
                lastMsg = convId to msg
                // incorporateIncoming 会在“服务端已落库但响应丢失”时合并本地待发消息，
                // 避免同一条消息既显示本地气泡又追加服务端回显。
                AppState.incorporateIncoming(convId, msg)
                val active = AppState.activeConversationId.value
                // meId 尚未就绪时保守处理：不把消息计为未读，避免自己的消息被 +1。
                val increment = meId != null && msg.senderId != meId && convId != active
                AppState.bumpConversation(convId, msg, increment)
                // 新会话的第一条消息：本地还没有该会话，回查详情补进列表。
                if (AppState.conversations.value.none { it.id == convId }) {
                    AppState.refreshConversation(convId, dropIfInaccessible = false)
                }
            }
            "message_deleted" -> {
                val convId = data["conversationId"] as? String ?: return
                val messageId = data["messageId"] as? String ?: return
                AppState.markDeleted(convId, messageId)
            }
            "message_edited" -> {
                // 编辑后的消息整体下发，直接按 id 覆盖即可（upsert 语义）。
                val convId = data["conversationId"] as? String ?: return
                val msg = toMessage(data["message"]) ?: return
                AppState.upsertMessage(convId, msg)
            }
            "message_read" -> {
                // 服务端广播 {conversationId, userId, readAt}；只有自己（多设备）已读才清本地未读。
                val convId = data["conversationId"] as? String ?: return
                val uid = data["userId"] as? String ?: return
                if (uid == AppState.currentUser.value?.id) AppState.clearUnread(convId)
            }
            "conversation:updated" -> {
                // 事件只带成员 id 变更（addedMembers/removedMember/leftMember），会话内容需回查。
                val convId = data["conversationId"] as? String ?: return
                val me = AppState.currentUser.value?.id
                val left = data["leftMember"] as? String
                val removed = data["removedMember"] as? String
                if ((left != null && left == me) || (removed != null && removed == me)) {
                    AppState.removeConversation(convId)
                } else {
                    AppState.refreshConversation(convId, dropIfInaccessible = true)
                }
            }
            "typing" -> {
                val convId = data["conversationId"] as? String ?: return
                val uid = data["userId"] as? String ?: return
                val isTyping = data["isTyping"] as? Boolean ?: false
                AppState.setTyping(convId, uid, isTyping)
            }
            "user_online_status" -> {
                val uid = data["userId"] as? String ?: return
                val online = data["isOnline"] as? Boolean ?: false
                AppState.setOnline(uid, online)
            }
            // call:* / device:* 等由具体屏幕通过 WsBus 消费（通话记录、设备列表刷新）。
        }
    }

    private fun toMessage(any: Any?): Message? = try {
        gson.fromJson(gson.toJson(any), Message::class.java)
    } catch (_: Exception) { null }

    /** 最近一条消息缓存（供前台通知服务读取）。 */
    @Volatile private var lastMsg: Pair<String, Message>? = null

    /** 通知服务用：某会话最近收到的一条消息。 */
    fun lastMessageOf(convId: String): Message? = lastMsg?.takeIf { it.first == convId }?.second

    private fun parse(s: String): Map<String, Any?>? = try {
        @Suppress("UNCHECKED_CAST")
        gson.fromJson(s, Map::class.java) as Map<String, Any?>
    } catch (_: Exception) { null }
}
