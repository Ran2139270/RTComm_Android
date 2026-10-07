package com.rtcomm.app.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/** WebSocket 事件（{event, data} 解析后的形态）。 */
data class WsEvent(val type: String, val data: Map<String, Any?>)

/**
 * 全局 WS 事件总线。屏幕可订阅特定事件（通话信令 / typing / ack / 终端输出等），
 * 常规事件（new_message 等）同时由 WsClient 写入 AppState。
 *
 * 消费者处理较慢时保留最新事件并丢弃最旧的，而不是静默失败：
 * 应用内横幅/通知这类 UI 事件只需要最新一条，积压旧事件没有意义。
 */
object WsBus {
    val events = MutableSharedFlow<WsEvent>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    fun emit(e: WsEvent) { events.tryEmit(e) }
}
