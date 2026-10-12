package com.rtcomm.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import com.rtcomm.app.data.AppState
import com.rtcomm.app.ui.common.ExpandFlow

/**
 * 聊天页 + 合并转发「聊天记录」详情的共享元素展开。
 *
 * 复用 [ExpandFlow]：聊天页是列表层，转发卡片上报矩形并触发展开，[ForwardDetailScreen]
 * 作为详情层——与「会话列表 → 聊天页」完全同一套展开/回收动画。
 */
@Composable
fun ChatFlow(
    conversationId: String,
    onBack: () -> Unit,
    progress: () -> Float,
    onHeaderTitleBounds: (Rect) -> Unit,
) {
    ExpandFlow(
        titleOf = { "聊天记录" },
        listContent = { onOpen, onBounds, onTitleBounds ->
            ChatScreen(
                conversationId = conversationId,
                onBack = onBack,
                progress = progress,
                onHeaderTitleBounds = onHeaderTitleBounds,
                onForwardOpen = onOpen,
                onForwardBounds = onBounds,
                onForwardTitleBounds = onTitleBounds,
            )
        },
        detailContent = { id, detailProgress, onDetailHeaderTitleBounds, onClose ->
            // 转发内容只读、打开期间不变：按 id 取一次即可。
            val content = remember(id) {
                AppState.messages.value[conversationId]?.firstOrNull { it.id == id }?.content.orEmpty()
            }
            ForwardDetailScreen(
                content = content,
                progress = detailProgress,
                onHeaderTitleBounds = onDetailHeaderTitleBounds,
                onBack = onClose,
            )
        },
    )
}
