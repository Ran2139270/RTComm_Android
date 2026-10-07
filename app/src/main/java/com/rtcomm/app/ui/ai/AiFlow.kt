package com.rtcomm.app.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rtcomm.app.ui.common.ExpandFlow

/**
 * AI 机器人列表 → AI 会话页 的展开/回收动画。
 * 复用与聊天列表相同的 [ExpandFlow] 共享元素逻辑，观感一致。
 */
@Composable
fun AiFlow(
    listBottomPadding: Dp = 0.dp,
    onOverlayVisibleChange: (Boolean) -> Unit = {},
) {
    ExpandFlow(
        listBottomPadding = listBottomPadding,
        onOverlayVisibleChange = onOverlayVisibleChange,
        listContent = { onOpen, onBounds, onTitleBounds ->
            AiScreen(
                onOpenBot = onOpen,
                onBotBounds = onBounds,
                onBotTitleBounds = onTitleBounds,
            )
        },
        detailContent = { id, progress, onHeaderTitleBounds, onClose ->
            AiChatScreen(
                botId = id,
                onBack = onClose,
                progress = progress,
                onHeaderTitleBounds = onHeaderTitleBounds,
            )
        },
    )
}
