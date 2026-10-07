package com.rtcomm.app.ws

import com.google.gson.Gson
import com.rtcomm.app.data.Api
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * 专用远程终端 WebSocket（握手使用 Authorization: Bearer JWT）。
 * 与主 WS 分离，随终端页面创建/销毁；断开时回调清理 UI 状态。
 * 事件：remote:session / remote:output / remote:exit / remote:error / remote:session_closed
 */
class TerminalClient(
    private val deviceId: String,
    private val onEvent: (type: String, data: Map<String, Any?>) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var closedByUser = false
    /** 连接代际：重连后忽略上一连接的迟到回调，避免旧事件污染新会话。 */
    @Volatile private var generation = 0L
    /** 服务端分配的会话 id（连接即自动建会话，remote:session 事件下发）。 */
    @Volatile var sessionId: String? = null
        private set

    private fun wsBase(): String {
        val b = Api.baseUrl.trimEnd('/')
        return when {
            b.startsWith("https://") -> "wss://" + b.removePrefix("https://")
            b.startsWith("http://") -> "ws://" + b.removePrefix("http://")
            else -> b
        }
    }

    fun connect() {
        val gen = ++generation
        val encodedId = java.net.URLEncoder.encode(deviceId, Charsets.UTF_8.name())
        val url = wsBase() + "/api/devices/" + encodedId + "/terminal"
        ws = client.newWebSocket(
            Request.Builder().url(url).addHeader("Authorization", "Bearer ${Api.token}").build(), object : WebSocketListener() {
            private fun stale() = gen != generation

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (stale()) return
                val ev = parse(text) ?: return
                val type = (ev["event"] as? String) ?: return
                @Suppress("UNCHECKED_CAST")
                val data = (ev["data"] as? Map<String, Any?>) ?: emptyMap()
                if (type == "remote:session") sessionId = data["sessionId"] as? String
                onEvent(type, data)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!stale()) onClosed()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (stale()) return
                if (!closedByUser) onEvent("remote:error", mapOf("error" to (t.message ?: "连接失败")))
                onClosed()
            }
        })
    }

    /** 返回 false 表示终端尚未连接或当前帧未送达，调用方可显示可重试提示。 */
    fun sendCommand(command: String): Boolean {
        val frame = mapOf("event" to "remote:command", "data" to mapOf("command" to command))
        return try { ws?.send(gson.toJson(frame)) ?: false } catch (_: Exception) { false }
    }

    // 注意：专用终端通道只接受 remote:command；
    // remote:resize 需经主 WS（WsClient）发送，由 UI 层组装 sessionId/deviceId。

    fun close() {
        closedByUser = true
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
    }

    private fun parse(s: String): Map<String, Any?>? = try {
        @Suppress("UNCHECKED_CAST")
        gson.fromJson(s, Map::class.java) as Map<String, Any?>
    } catch (_: Exception) { null }
}
